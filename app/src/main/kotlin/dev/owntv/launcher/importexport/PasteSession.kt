package dev.owntv.launcher.importexport

import android.content.Context
import android.net.ConnectivityManager
import dev.owntv.layout.codec.DecodeResult
import dev.owntv.layout.validation.Severity
import dev.owntv.layout.validation.ValidationIssue
import dev.owntv.layout.validation.toAiFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.security.SecureRandom

/** Owns the [PasteServer] while the Import screen is open and forwards pasted layouts to the UI. */
class PasteSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val provider: Provider,
) {
    interface Provider {
        fun pastePage(): String
        fun layoutJson(): String
        fun aiPrompt(): String
        fun decode(text: String): DecodeResult
    }

    data class State(
        val running: Boolean = false,
        val url: String? = null,
        val hasLanAddress: Boolean = true,
        val message: String? = null,
    )

    @Serializable
    private data class PasteResponse(
        val ok: Boolean,
        val message: String,
        val issues: List<ValidationIssue>,
        val feedback: String,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _received = MutableSharedFlow<DecodeResult>(extraBufferCapacity = 4)
    val received: SharedFlow<DecodeResult> = _received.asSharedFlow()

    private val mutex = Mutex()
    private var server: PasteServer? = null
    private var timeout: Job? = null
    private val json = Json { encodeDefaults = true }

    fun start() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (server != null) return@withLock
                val token = newToken()
                val handler = object : PasteServer.Handler {
                    override fun layoutJson() = provider.layoutJson()
                    override fun aiPrompt() = provider.aiPrompt()
                    override suspend fun receive(text: String): String {
                        val result = provider.decode(text)
                        _received.emit(result)
                        return json.encodeToString(PasteResponse.serializer(), result.toResponse())
                    }
                }
                try {
                    val created = PasteServer(provider.pastePage(), token, handler)
                    val port = created.start(PasteServer.PREFERRED_PORT)
                    server = created
                    val ip = lanIpv4()
                    _state.value = State(
                        running = true,
                        url = "http://${ip ?: "<tv-ip>"}:$port/?t=$token",
                        hasLanAddress = ip != null,
                    )
                    timeout = scope.launch {
                        delay(TIMEOUT_MS)
                        stop(message = "The paste page stopped after 10 minutes. Leave and reopen Import to start it again.")
                    }
                } catch (e: Exception) {
                    _state.value = State(message = "Could not start the paste page: ${e.message}")
                }
            }
        }
    }

    fun stop(message: String? = null) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                timeout?.cancel()
                timeout = null
                server?.stop()
                server = null
                _state.value = State(message = message)
            }
        }
    }

    private fun DecodeResult.toResponse(): PasteResponse {
        val errors = issues.count { it.severity == Severity.Error }
        val message = when {
            errors > 0 -> "The layout has $errors error(s). Fix them and send again."
            else -> "Sent. Check the preview on the TV and choose Apply."
        }
        return PasteResponse(ok = ok, message = message, issues = issues, feedback = issues.toAiFeedback())
    }

    private fun lanIpv4(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun newToken(): String {
        val bytes = ByteArray(16).also(SecureRandom()::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}
