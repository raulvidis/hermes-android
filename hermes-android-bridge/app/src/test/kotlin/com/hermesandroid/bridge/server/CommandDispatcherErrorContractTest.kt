package com.hermesandroid.bridge.server

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.bridgeTest
import io.ktor.serialization.gson.gson
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Review L1: the two transports answered the same failure differently.
 *
 * `CommandDispatcher` is documented as the single source of truth shared by both,
 * and both do call it — but only the relay caught. `RelayClient.handleMessage`
 * wraps `dispatch` and answers `{"error": …}` with status 500; `BridgeRouter` had
 * no try/catch and `BridgeServer` installed only `ContentNegotiation`, so an
 * exception escaped to Ktor's default handler as a **non-JSON** 500. A tool that
 * parses the body therefore worked over the relay and broke over direct USB/LAN
 * for the identical request.
 *
 * These pin one contract for both: a JSON object with an `error` key, at 500.
 * The `error` value is the exception's *class* name, never its message — an
 * exception message can carry an SMS recipient, a file path or a host:port, which
 * is exactly what the PII convention keeps out of responses and logs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CommandDispatcherErrorContractTest {

    private var engine: ApplicationEngine? = null

    @After
    fun tearDown() {
        engine?.stop(200, 500)
        engine = null
    }

    /** A request that reaches a route and throws there: `?.asString` on a JsonNull. */
    private fun nullPackageBody(): JsonObject = JsonObject().apply { add("package", JsonNull.INSTANCE) }

    @Test
    fun `a throwing route answers a JSON error instead of propagating`() = bridgeTest {
        val (result, status) = CommandDispatcher.dispatch(
            "POST",
            "/open_app",
            JsonObject(),
            nullPackageBody(),
            authenticated = true,
        )

        assertEquals(500, status)
        @Suppress("UNCHECKED_CAST")
        val fields = result as Map<String, Any>
        assertEquals(setOf("error"), fields.keys)
        assertNotNull(fields["error"])
        assertTrue("the error must name a class: ${fields["error"]}", (fields["error"] as String).isNotEmpty())
    }

    @Test
    fun `the error body carries no message text`() = bridgeTest {
        val (result, _) = CommandDispatcher.dispatch(
            "POST",
            "/open_app",
            JsonObject(),
            nullPackageBody(),
            authenticated = true,
        )

        // A message here could quote a recipient, a path or a host:port. The class
        // name says enough to diagnose and leaks nothing.
        @Suppress("UNCHECKED_CAST")
        val fields = result as Map<String, Any>
        val error = fields["error"] as String
        assertTrue("expected a bare class name, got '$error'", error.matches(Regex("[A-Za-z0-9_\$.]+")))
        assertTrue(
            "the error must not read like a sentence: '$error'",
            !error.contains(' '),
        )
    }

    @Test
    fun `a well-formed request is unaffected`() = bridgeTest {
        val (result, status) = CommandDispatcher.dispatch("GET", "/ping", JsonObject(), JsonObject(), false)

        assertEquals(200, status)
        @Suppress("UNCHECKED_CAST")
        assertEquals("ok", (result as Map<String, Any>)["status"])
    }

    // ── The HTTP transport specifically ──────────────────────────────────────

    @Test
    fun `direct HTTP answers the same JSON error the relay does`() {
        // No new dependency: ktor-server-test-host is not one of this project's,
        // so the routing is exercised against a real Netty engine on an ephemeral
        // port. configureRouting() is installed without BridgeServer's auth
        // interceptor, which is a separate concern.
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            // Mirror what BridgeServer.start() installs: without ContentNegotiation
            // Ktor cannot serialise the error map and answers 406 instead, which
            // would test the harness rather than the transport.
            install(ContentNegotiation) { gson { serializeNulls() } }
            configureRouting()
        }.start(wait = false)
        engine = server
        // resolvedConnectors() is suspend in Ktor 2.x; the port is only known
        // after the engine binds, which is why the test asks for port 0.
        val port = runBlocking { server.resolvedConnectors() }.first().port

        val connection = URL("http://127.0.0.1:$port/open_app").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.outputStream.use { it.write("""{"package":null}""".toByteArray()) }

        val status = connection.responseCode
        val rawBody = ByteArrayOutputStream()
        (if (status >= 400) connection.errorStream else connection.inputStream)?.use { it.copyTo(rawBody) }
        val text = rawBody.toString("UTF-8")
        val contentType = connection.contentType
        connection.disconnect()

        assertEquals("a throwing route must be a 500, not a connection failure", 500, status)
        assertTrue(
            "the body must be JSON so a tool can parse it either way, got: $text",
            (contentType ?: "").contains("application/json", ignoreCase = true) ||
                text.trim().startsWith("{"),
        )
        val parsed = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        assertNotNull("the body did not parse as a JSON object: $text", parsed)
        assertTrue("the body must carry an 'error' key, got: $text", parsed!!.has("error"))
    }
}
