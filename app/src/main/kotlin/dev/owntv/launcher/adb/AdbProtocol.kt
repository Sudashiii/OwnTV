package dev.owntv.launcher.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The parts of the adb wire protocol OwnTV needs to run a shell command on its own TV: messages, the RSA
 * public key format adbd stores, and the signed auth token. Pure: no sockets, no Android.
 * See system/core/adb/protocol.txt in AOSP.
 */
object AdbProtocol {
    const val CNXN = 0x4e584e43
    const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f
    const val OKAY = 0x59414b4f
    const val CLSE = 0x45534c43
    const val WRTE = 0x45545257
    const val STLS = 0x534c5453

    /** Protocol version that skips payload checksums (adb 1.0.40+, every Google TV). */
    const val VERSION = 0x01000001
    const val MAX_PAYLOAD = 256 * 1024
    const val HEADER_SIZE = 24

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** SHA-1 DigestInfo prefix: adbd verifies the token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    data class Message(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = ByteArray(0)) {
        override fun equals(other: Any?) = other is Message && command == other.command && arg0 == other.arg0 &&
            arg1 == other.arg1 && payload.contentEquals(other.payload)

        override fun hashCode() = ((command * 31 + arg0) * 31 + arg1) * 31 + payload.contentHashCode()

        override fun toString() = "${name(command)}($arg0, $arg1, ${payload.size} bytes)"
    }

    fun encode(message: Message): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + message.payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(message.command)
        buffer.putInt(message.arg0)
        buffer.putInt(message.arg1)
        buffer.putInt(message.payload.size)
        buffer.putInt(message.payload.sumOf { it.toInt() and 0xff })
        buffer.putInt(message.command.inv())
        buffer.put(message.payload)
        return buffer.array()
    }

    /** The header's fields; null when its magic does not match (not an adb stream). */
    data class Header(val command: Int, val arg0: Int, val arg1: Int, val length: Int)

    fun decodeHeader(bytes: ByteArray): Header? {
        val buffer = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.getInt()
        val arg0 = buffer.getInt()
        val arg1 = buffer.getInt()
        val length = buffer.getInt()
        buffer.getInt() // checksum, not checked from VERSION on
        val magic = buffer.getInt()
        if (magic != command.inv() || length < 0 || length > MAX_PAYLOAD * 4) return null
        return Header(command, arg0, arg1, length)
    }

    fun connect(): Message = Message(CNXN, VERSION, MAX_PAYLOAD, "host::owntv\u0000".toByteArray())

    fun open(localId: Int, service: String): Message = Message(OPEN, localId, 0, (service + "\u0000").toByteArray())

    /** What adbd's PKCS#1 v1.5 check expects to find inside the signature: DigestInfo + the token. */
    fun signaturePayload(token: ByteArray): ByteArray = SHA1_DIGEST_INFO + token

    /**
     * [key] in adbd's own format (mincrypt's RSAPublicKey: word count, n0inv, modulus and R² mod n little
     * endian, exponent), base64, then " name" and a NUL, as `adb` writes `adbkey.pub`.
     */
    fun publicKeyPayload(key: RSAPublicKey, name: String): ByteArray {
        val words = key.modulus.bitLength() / 32
        val buffer = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = key.modulus.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(words * 32).modPow(BigInteger.valueOf(2), key.modulus)
        buffer.putInt(words)
        buffer.putInt(n0inv.toInt())
        buffer.put(littleEndian(key.modulus, words * 4))
        buffer.put(littleEndian(rr, words * 4))
        buffer.putInt(key.publicExponent.toInt())
        return (Base64.getEncoder().encodeToString(buffer.array()) + " " + name + "\u0000").toByteArray()
    }

    private fun littleEndian(value: BigInteger, size: Int): ByteArray {
        val big = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(big.size <= size) { "value does not fit in $size bytes" }
        return ByteArray(size) { i -> if (i < big.size) big[big.size - 1 - i] else 0 }
    }

    /** Marker appended to every command, so the exit status comes back with the output. */
    const val EXIT_MARKER = "__owntv_exit="

    fun shellService(command: String): String = "shell:$command; echo $EXIT_MARKER\$?"

    data class ShellResult(val output: String, val exitCode: Int?)

    /** Splits what `shellService` printed into the command's own output and its exit status. */
    fun parseShellOutput(raw: String): ShellResult {
        val index = raw.lastIndexOf(EXIT_MARKER)
        if (index < 0) return ShellResult(raw.trim(), null)
        val code = raw.substring(index + EXIT_MARKER.length).trim().takeWhile { it.isDigit() }.toIntOrNull()
        return ShellResult(raw.substring(0, index).trim(), code)
    }

    /** The shell command inside a line shown as `adb shell …`, or null for other adb commands (push, install). */
    fun shellCommandOf(line: String): String? =
        line.trim().removePrefix("adb shell ").takeIf { it != line.trim() && it.isNotBlank() }

    fun name(command: Int): String = when (command) {
        CNXN -> "CNXN"
        AUTH -> "AUTH"
        OPEN -> "OPEN"
        OKAY -> "OKAY"
        CLSE -> "CLSE"
        WRTE -> "WRTE"
        STLS -> "STLS"
        else -> "0x" + command.toUInt().toString(16)
    }
}
