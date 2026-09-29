package com.hermesandroid.bridge.server

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermesandroid.bridge.BridgeApplication
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.audio.MicrophoneRecordingFiles
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.CancellationException

/**
 * Test seam for the /mic_file route's Context. Production reads
 * [BridgeApplication.instance]; Robolectric never runs Application.onCreate
 * (and its setter is private), so tests point this at the Robolectric
 * application — the same device as CommandDispatcher.batteryContext.
 * Default behaviour is unchanged.
 */
internal var micFileContext: () -> Context = { BridgeApplication.instance }

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
        get("/mic_file") {
            guarded {
                val requestedName = call.request.queryParameters["name"]
                val file = MicrophoneRecordingFiles.resolve(
                    micFileContext(),
                    requestedName,
                )
                if (file == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Recording not found"))
                    return@guarded
                }

                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header(HttpHeaders.ContentType, "audio/wav")
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"${file.name}\"",
                )
                call.respondFile(file)
            }
        }

        route("{path...}") {
            handle {
                guarded {
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
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid JSON body: ${e.message}"),
                        )
                        return@guarded
                    }

                    val authenticated = PairingManager.validateToken(
                        call.request.header(HttpHeaders.Authorization)
                    )

                    val (result, status) =
                        CommandDispatcher.dispatch(method, path, params, body, authenticated)
                    call.respond(HttpStatusCode.fromValue(status), result)
                }
            }
        }
    }
}

/**
 * Answer anything that escapes a route as the same JSON 500 the relay gives
 * (review L1). [CommandDispatcher.dispatch] already catches route failures, so
 * this covers what happens outside it — `/mic_file` resolving its directory, a
 * response that fails to serialise — instead of letting Ktor's default handler
 * emit a non-JSON 500 that a tool cannot parse.
 *
 * `ktor-server-status-pages` is not a dependency of this project, so the guard is
 * here rather than in a `StatusPages` block; the body and status come from
 * [CommandDispatcher.errorBody], which is what keeps the two transports in step.
 */
private suspend fun PipelineContext<Unit, ApplicationCall>.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cause: Throwable) {
        // runCatching: the response may already be committed (a stream that broke
        // part-way), and failing to report the failure must not mask it.
        runCatching {
            call.respond(
                HttpStatusCode.InternalServerError,
                CommandDispatcher.errorBody(cause),
            )
        }
    }
}
