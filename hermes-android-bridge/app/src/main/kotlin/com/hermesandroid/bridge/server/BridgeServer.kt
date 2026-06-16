package com.hermesandroid.bridge.server

import com.hermesandroid.bridge.auth.PairingManager
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*

object BridgeServer {
    private var server: ApplicationEngine? = null

    // Per-IP auth throttle — defends the 0.0.0.0:8765 bind against
    // brute-forcing the pairing code (see AuthRateLimiter).
    private val authRateLimiter = AuthRateLimiter()

    fun start(port: Int = 8765) {
        if (server != null) return
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                    serializeNulls()
                }
            }
            // Auth interceptor — every request must have a valid Bearer token.
            // /ping is allowed without auth so the agent can discover the
            // bridge, but it does not return sensitive data.
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()
                if (path == "/ping") return@intercept

                val ip = call.request.local.remoteHost
                if (authRateLimiter.isBlocked(ip)) {
                    call.respond(HttpStatusCode.TooManyRequests, mapOf(
                        "error" to "Too many failed authentication attempts. Try again later."
                    ))
                    finish()
                    return@intercept
                }

                val authHeader = call.request.header(HttpHeaders.Authorization)
                if (!PairingManager.validateToken(authHeader)) {
                    authRateLimiter.recordFailure(ip)
                    call.respond(HttpStatusCode.Unauthorized, mapOf(
                        "error" to "Invalid or missing pairing code",
                        "hint" to "Set ANDROID_BRIDGE_TOKEN to the code shown in the Hermes Bridge app"
                    ))
                    finish()
                }
            }
            configureRouting()
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
