package dev.owntv.launcher.importexport

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest

/**
 * A small HTTP server on the local network that serves the phone paste page. Every API call needs
 * the random token from the QR code. It runs only while the Import screen is open.
 */
class PasteServer(
    private val page: String,
    private val token: String,
    private val handler: Handler,
) {
    interface Handler {
        fun layoutJson(): String
        fun aiPrompt(): String
        /** Validates a pasted layout and returns the JSON response for the phone. */
        suspend fun receive(text: String): String
    }

    private var server: EmbeddedServer<*, *>? = null

    /** Starts on [preferredPort], or on a free port if that one is taken. Returns the port. Blocking. */
    suspend fun start(preferredPort: Int): Int {
        val started = runCatching { create(preferredPort).also { it.start(wait = false) } }
            .getOrElse { create(0).also { it.start(wait = false) } }
        server = started
        return started.engine.resolvedConnectors().first().port
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
    }

    private fun create(port: Int) = embeddedServer(CIO, port = port, host = "0.0.0.0") {
        routing {
            get("/") {
                call.respondText(page, ContentType.Text.Html)
            }
            get("/api/layout") {
                if (!call.authorized()) return@get call.forbidden()
                call.respondText(handler.layoutJson(), ContentType.Application.Json)
            }
            get("/api/ai-prompt") {
                if (!call.authorized()) return@get call.forbidden()
                call.respondText(handler.aiPrompt(), ContentType.Text.Plain)
            }
            post("/api/layout") {
                if (!call.authorized()) return@post call.forbidden()
                val length = call.request.contentLength()
                if (length != null && length > MAX_BODY) {
                    return@post call.respondText("Layout too large", status = HttpStatusCode.PayloadTooLarge)
                }
                val text = call.receiveText()
                if (text.length > MAX_BODY) {
                    return@post call.respondText("Layout too large", status = HttpStatusCode.PayloadTooLarge)
                }
                call.respondText(handler.receive(text), ContentType.Application.Json)
            }
        }
    }

    private fun ApplicationCall.authorized(): Boolean {
        val given = request.queryParameters["t"] ?: request.headers["X-OwnTV-Token"] ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    private suspend fun ApplicationCall.forbidden() =
        respondText("Wrong or missing token. Scan the QR code on the TV again.", status = HttpStatusCode.Forbidden)

    companion object {
        const val PREFERRED_PORT = 8765
        const val MAX_BODY = 1_000_000L
    }
}
