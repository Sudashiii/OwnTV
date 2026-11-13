package dev.owntv.launcher.adb

import android.content.Context
import dev.owntv.launcher.adb.AdbProtocol.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Runs the `adb shell` commands OwnTV shows on the TV itself: it connects to the TV's own adb daemon on
 * 127.0.0.1:5555 (network debugging, on by default with USB debugging on Chromecast with Google TV and
 * similar boxes) like a computer would. The first time, the TV asks "Allow debugging?" for OwnTV's key;
 * with "Always allow" ticked it never asks again. The key stays in OwnTV's private files.
 *
 * Only commands OwnTV itself shows can run: the UI passes the lines it displays, never typed text.
 */
class SelfAdb(context: Context) {
    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status

        /** The TV shows its "Allow debugging?" dialog for OwnTV's key. */
        data object WaitingForApproval : Status
        data object Ready : Status
        data class Failed(val problem: Problem) : Status
    }

    enum class Problem {
        /** Nothing listens on the port: debugging is off, or this TV needs network debugging turned on. */
        NotListening,

        /** The dialog was declined, or nobody answered it in time. */
        NotApproved,

        /** The port wants wireless debugging with pairing (TLS), which OwnTV does not speak. */
        NeedsPairing,

        /** Anything else: the connection broke. */
        Broken,
    }

    class AdbException(val problem: Problem, message: String, cause: Throwable? = null) : IOException(message, cause)

    private val keyDir = File(context.filesDir, "adb")
    private val mutex = Mutex()
    private val current = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = current.asStateFlow()

    /** Runs [command] (what follows `adb shell`) and returns its output and exit status. */
    suspend fun run(command: String): Result<AdbProtocol.ShellResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                current.value = Status.Connecting
                Connection(HOST, PORT).use { connection ->
                    connection.handshake(keys())
                    current.value = Status.Ready
                    AdbProtocol.parseShellOutput(connection.shell(command))
                }
            }.onFailure { error ->
                current.value = Status.Failed((error as? AdbException)?.problem ?: Problem.Broken)
            }
        }
    }

    /** Connects and runs a harmless command, to set up or check the connection. */
    suspend fun test(): Result<AdbProtocol.ShellResult> = run("echo ok")

    private fun keys(): KeyPair {
        val private = File(keyDir, "adbkey.pk8")
        val public = File(keyDir, "adbkey.x509")
        val factory = KeyFactory.getInstance("RSA")
        if (private.exists() && public.exists()) {
            runCatching {
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(public.readBytes())),
                    factory.generatePrivate(PKCS8EncodedKeySpec(private.readBytes())),
                )
            }
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        keyDir.mkdirs()
        private.writeBytes(pair.private.encoded)
        public.writeBytes(pair.public.encoded)
        return pair
    }

    private inner class Connection(host: String, port: Int) : Closeable {
        private val socket = Socket()
        private val input: DataInputStream
        private val output: BufferedOutputStream

        init {
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (e: ConnectException) {
                throw AdbException(Problem.NotListening, "Nothing listens on $host:$port", e)
            } catch (e: SocketTimeoutException) {
                throw AdbException(Problem.NotListening, "No answer on $host:$port", e)
            }
            socket.soTimeout = REPLY_TIMEOUT_MS
            input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            output = BufferedOutputStream(socket.getOutputStream())
        }

        fun send(message: Message) {
            output.write(AdbProtocol.encode(message))
            output.flush()
        }

        fun receive(): Message {
            val header = ByteArray(AdbProtocol.HEADER_SIZE)
            input.readFully(header)
            val decoded = AdbProtocol.decodeHeader(header) ?: throw AdbException(Problem.Broken, "Not an adb answer")
            val payload = ByteArray(decoded.length)
            input.readFully(payload)
            return Message(decoded.command, decoded.arg0, decoded.arg1, payload)
        }

        /** CNXN, then AUTH: the stored key's signature first, else the public key and the TV's dialog. */
        fun handshake(keys: KeyPair) {
            send(AdbProtocol.connect())
            var signed = false
            var offeredKey = false
            try {
                while (true) {
                    val message = receive()
                    when (message.command) {
                        AdbProtocol.CNXN -> return
                        AdbProtocol.STLS -> throw AdbException(Problem.NeedsPairing, "The TV wants wireless debugging with pairing")
                        AdbProtocol.AUTH -> {
                            if (message.arg0 != AdbProtocol.AUTH_TOKEN) continue
                            when {
                                !signed -> {
                                    signed = true
                                    send(Message(AdbProtocol.AUTH, AdbProtocol.AUTH_SIGNATURE, 0, sign(keys, message.payload)))
                                }
                                !offeredKey -> {
                                    offeredKey = true
                                    current.value = Status.WaitingForApproval
                                    socket.soTimeout = APPROVAL_TIMEOUT_MS
                                    send(Message(AdbProtocol.AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, AdbProtocol.publicKeyPayload(keys.public as RSAPublicKey, KEY_NAME)))
                                }
                                else -> throw AdbException(Problem.NotApproved, "The TV did not allow OwnTV's key")
                            }
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                throw AdbException(if (offeredKey) Problem.NotApproved else Problem.Broken, "No answer from the TV", e)
            } catch (e: EOFException) {
                throw AdbException(if (offeredKey) Problem.NotApproved else Problem.Broken, "The TV closed the connection", e)
            } finally {
                socket.soTimeout = REPLY_TIMEOUT_MS
            }
        }

        /** Opens `shell:` with [command] and collects what it writes until the TV closes the stream. */
        fun shell(command: String): String {
            send(AdbProtocol.open(LOCAL_ID, AdbProtocol.shellService(command)))
            val out = ByteArrayOutputStream()
            try {
                while (true) {
                    val message = receive()
                    when (message.command) {
                        AdbProtocol.WRTE -> {
                            out.write(message.payload)
                            send(Message(AdbProtocol.OKAY, LOCAL_ID, message.arg0))
                        }
                        AdbProtocol.CLSE -> {
                            send(Message(AdbProtocol.CLSE, LOCAL_ID, message.arg0))
                            break
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                throw AdbException(Problem.Broken, "The command did not finish", e)
            } catch (e: EOFException) {
                // Some adbd versions close the socket right after the stream; keep what arrived.
            }
            return out.toString(Charsets.UTF_8.name())
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private fun sign(keys: KeyPair, token: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, keys.private)
            doFinal(AdbProtocol.signaturePayload(token))
        }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        private const val LOCAL_ID = 1
        private const val KEY_NAME = "OwnTV@this-tv"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val REPLY_TIMEOUT_MS = 15_000
        private const val APPROVAL_TIMEOUT_MS = 90_000
    }
}
