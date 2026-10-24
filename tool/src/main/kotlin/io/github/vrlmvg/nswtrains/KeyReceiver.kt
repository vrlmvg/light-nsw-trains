package io.github.vrlmvg.nswtrains

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFERRED_PORT = 8123
private const val MAX_WRONG_CODES = 5
private const val MAX_HEADER_BYTES = 8 * 1024
private const val MAX_BODY_BYTES = 8 * 1024
private const val SOCKET_TIMEOUT_MS = 15_000

class TooManyWrongCodesException : Exception("Too many wrong addresses were tried.")

/**
 * A tiny one-shot HTTP server on the local network that lets the user paste their API key into
 * a browser on a computer instead of typing it on the phone.
 *
 * The page lives under a random 4-digit path, which works as a one-time code: requests for any
 * other path count as wrong guesses, and the server gives up after [MAX_WRONG_CODES] of them.
 * It only runs while the "Send from computer" screen is open.
 */
class KeyReceiver {
    val code: String = (SecureRandom().nextInt(9000) + 1000).toString()
    private val server = runCatching { ServerSocket(PREFERRED_PORT) }.getOrElse { ServerSocket(0) }
    val port: Int = server.localPort
    private var wrongCodes = 0

    /** Serves requests until a key is submitted, then returns it. */
    suspend fun awaitKey(): String = withContext(Dispatchers.IO) { serve() }

    private fun serve(): String {
        while (true) {
            val key = server.accept().use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                runCatching { handle(socket) }.getOrNull()
            }
            if (key != null) return key
            if (wrongCodes >= MAX_WRONG_CODES) throw TooManyWrongCodesException()
        }
    }

    fun close() = runCatching { server.close() }

    private fun handle(socket: Socket): String? {
        val input = BufferedInputStream(socket.getInputStream())
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val (method, rawPath) = lines.first().split(" ").takeIf { it.size >= 2 } ?: return null
        val path = rawPath.substringBefore('?').trimEnd('/')
        val headers = lines.drop(1).mapNotNull { line ->
            line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
        }.toMap()

        if (path == "/favicon.ico") {
            respond(socket, 404, "Not found")
            return null
        }
        if (path != "/$code") {
            wrongCodes++
            respond(socket, 404, page("Wrong address", "<p>Check the address shown on your Light Phone.</p>"))
            return null
        }
        return when (method) {
            "GET" -> {
                respond(socket, 200, FORM_PAGE)
                null
            }
            "POST" -> {
                val length = headers["content-length"]?.toIntOrNull()?.takeIf { it in 0..MAX_BODY_BYTES }
                if (length == null) {
                    respond(socket, 413, page("Too long", "<p>That doesn't look like an API key.</p>"))
                    return null
                }
                val body = readBody(input, length) ?: return null
                val key = formField(body, "key")?.let(::cleanKey)
                if (key.isNullOrEmpty()) {
                    respond(socket, 200, FORM_PAGE)
                    null
                } else {
                    respond(socket, 200, page("Key sent", "<p>Your API key is on your Light Phone. You can close this page.</p>"))
                    key
                }
            }
            else -> {
                respond(socket, 405, "Method not allowed")
                null
            }
        }
    }

    /** Reads up to the blank line that ends the request headers. */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        val terminator = "\r\n\r\n"
        while (buffer.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buffer.write(b)
            matched = if (b.toChar() == terminator[matched]) matched + 1 else if (b == '\r'.code) 1 else 0
            if (matched == terminator.length) return buffer.toString(Charsets.ISO_8859_1.name()).trimEnd()
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): String? {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) return null
            read += n
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun respond(socket: Socket, status: Int, html: String) {
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Payload Too Large"
        }
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    companion object {
        /**
         * The phone's address on the local network, or null when it isn't connected to one.
         * Prefers Wi-Fi over other interfaces such as mobile data.
         */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name.startsWith("wlan") }
                .flatMap { iface -> iface.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        /** Accepts a pasted Authorization header value as well as the bare key. */
        fun cleanKey(raw: String): String =
            raw.trim().removePrefix("Authorization:").trim()
                .let { if (it.startsWith("apikey ", ignoreCase = true)) it.substring(7) else it }
                .filterNot(Char::isWhitespace)

        private fun formField(body: String, name: String): String? =
            body.split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it[0] == name }
                ?.getOrNull(1)
                ?.let { URLDecoder.decode(it, Charsets.UTF_8.name()) }

        private fun page(title: String, content: String) = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>NSW Trains · $title</title>
            <style>
              :root { color-scheme: light dark; }
              body { font: 17px/1.5 -apple-system, system-ui, sans-serif; max-width: 34rem; margin: 3rem auto; padding: 0 16px; background: Canvas; color: CanvasText; }
              h1 { font-size: 1.5rem; font-weight: 600; }
              p { opacity: .75; }
              textarea { width: 100%; box-sizing: border-box; min-height: 7rem; font: 15px ui-monospace, monospace; padding: 10px; border: 1px solid GrayText; border-radius: 6px; background: Canvas; color: CanvasText; }
              button { margin-top: 1rem; font: inherit; font-weight: 600; padding: 10px 22px; border: 0; border-radius: 6px; background: CanvasText; color: Canvas; cursor: pointer; }
            </style></head>
            <body><h1>$title</h1>$content</body></html>
        """.trimIndent()

        private val FORM_PAGE = page(
            "Send API key",
            """
            <p>Paste your Transport for NSW API key below. It goes straight to your Light Phone on this network.</p>
            <form method="post">
              <textarea name="key" autofocus required autocomplete="off" spellcheck="false" placeholder="eyJhbGciOi…"></textarea>
              <button type="submit">Send to phone</button>
            </form>
            """.trimIndent(),
        )
    }
}
