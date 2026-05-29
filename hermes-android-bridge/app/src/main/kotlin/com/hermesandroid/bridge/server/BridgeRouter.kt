package com.hermesandroid.bridge.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.auth.PairingManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Local HTTP server routing. This is a thin transport adapter: it parses each request
 * into (method, path, params, body) and hands it to [CommandDispatcher], which holds the
 * single source of truth for command behaviour shared with the WebSocket relay path.
 *
 * Auth is enforced by the interceptor in BridgeServer.kt; here we only compute the
 * authenticated flag so `/ping` can report it accurately.
 */
fun Application.configureRouting() {
    routing {
        route("{path...}") {
            handle {
                val method = call.request.httpMethod.value.uppercase()
                val segments = call.parameters.getAll("path") ?: emptyList()
                val path = "/" + segments.joinToString("/")

                val params = JsonObject().apply {
                    call.request.queryParameters.names().forEach { name ->
                        call.request.queryParameters[name]?.let { addProperty(name, it) }
                    }
                }

                val body = try {
                    val text = call.receiveText()
                    if (text.isBlank()) JsonObject() else JsonParser.parseString(text).asJsonObject
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON body: ${e.message}"))
                    return@handle
                }

                val authenticated = PairingManager.validateToken(
                    call.request.header(HttpHeaders.Authorization)
                )

                val (result, status) = CommandDispatcher.dispatch(method, path, params, body, authenticated)
                call.respond(HttpStatusCode.fromValue(status), result)
            }
        }
    }
}
