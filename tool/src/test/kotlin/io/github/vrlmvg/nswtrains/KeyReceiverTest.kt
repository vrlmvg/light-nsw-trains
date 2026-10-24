package io.github.vrlmvg.nswtrains

import java.net.InetAddress
import java.net.Socket
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Talks to a real [KeyReceiver] over loopback. */
class KeyReceiverTest {
    private val loopback = InetAddress.getLoopbackAddress()

    private fun request(port: Int, raw: String): String =
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply { write(raw.toByteArray()); flush() }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun get(port: Int, path: String) = request(port, "GET $path HTTP/1.1\r\nHost: phone\r\n\r\n")

    private fun post(port: Int, path: String, key: String): String {
        val body = "key=" + URLEncoder.encode(key, "UTF-8")
        return request(port, "POST $path HTTP/1.1\r\nHost: phone\r\nContent-Length: ${body.length}\r\n\r\n$body")
    }

    @Test
    fun receivesKeyPostedToTheCodePath() = runBlocking<Unit> {
        val receiver = KeyReceiver(loopback, preferredPort = 0)
        try {
            val key = async(Dispatchers.IO) { receiver.awaitKey() }
            assertTrue(get(receiver.port, "/${receiver.code}").startsWith("HTTP/1.1 200"))
            // Not a key: the form comes back and the server keeps waiting.
            assertTrue(post(receiver.port, "/${receiver.code}", "héllo wörld").contains("doesn't look like an API key"))
            post(receiver.port, "/${receiver.code}", "  Authorization: apikey eyJhbGciOiJIUzI1NiJ9.abc  ")
            assertEquals("eyJhbGciOiJIUzI1NiJ9.abc", withTimeout(5_000) { key.await() })
        } finally {
            receiver.close()
        }
    }

    @Test
    fun stopsAfterTooManyWrongCodes() = runBlocking<Unit> {
        val receiver = KeyReceiver(loopback, preferredPort = 0)
        try {
            val key = async(Dispatchers.IO) { runCatching { receiver.awaitKey() } }
            val wrong = if (receiver.code == "1000") "/1001" else "/1000"
            repeat(5) { assertTrue(get(receiver.port, wrong).startsWith("HTTP/1.1 404")) }
            assertFailsWith<TooManyWrongCodesException> { withTimeout(5_000) { key.await() }.getOrThrow() }
        } finally {
            receiver.close()
        }
    }

    @Test
    fun faviconIsNotAGuess() = runBlocking<Unit> {
        val receiver = KeyReceiver(loopback, preferredPort = 0)
        try {
            val key = async(Dispatchers.IO) { receiver.awaitKey() }
            repeat(6) { get(receiver.port, "/favicon.ico") }
            post(receiver.port, "/${receiver.code}", "eyJhbGciOiJIUzI1NiJ9.abc")
            assertEquals("eyJhbGciOiJIUzI1NiJ9.abc", withTimeout(5_000) { key.await() })
        } finally {
            receiver.close()
        }
    }

    @Test
    fun plausibleKeys() {
        assertTrue(KeyReceiver.isPlausibleKey("eyJhbGciOiJIUzI1NiJ9.abc"))
        assertFalse(KeyReceiver.isPlausibleKey("short"))
        assertFalse(KeyReceiver.isPlausibleKey("has\u0000control-char"))
        assertFalse(KeyReceiver.isPlausibleKey("ünïcode-key-value"))
    }
}
