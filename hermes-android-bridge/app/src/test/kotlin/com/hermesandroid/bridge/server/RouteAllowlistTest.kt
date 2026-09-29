package com.hermesandroid.bridge.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin-side analogue of the relay's allowlist test (commit 92b4204,
 * tests/test_android_relay.py): the endpoints docs/architecture.md documents
 * must be exactly the set the bridge actually serves — in both directions.
 *
 * 92b4204 closed a gap where GET /battery was documented (and dispatched by
 * the local server) but missing from the RELAY's route allowlist, so the
 * default transport silently refused it — nothing failed loudly, the phone
 * just never saw the request. The Kotlin allowlist under test here is
 * CommandDispatcher's route table (the single source of truth both
 * transports dispatch through) plus BridgeRouter's dedicated /mic_file
 * download route. A route documented but missing from this set is exactly
 * the unreachable-over-the-default-transport defect; a route in the set but
 * missing from the docs is an undocumented surface.
 */
class RouteAllowlistTest {

    private val dispatcherPath =
        "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/server/CommandDispatcher.kt"
    private val routerPath =
        "hermes-android-bridge/app/src/main/kotlin/com/hermesandroid/bridge/server/BridgeRouter.kt"

    @Test
    fun `the documented endpoints are exactly the served allowlist`() {
        val root = repoRoot()
        val documented = documentedEndpoints(File(root, "docs/architecture.md").readText())
        val served = servedRoutes(
            File(root, dispatcherPath).readText(),
            File(root, routerPath).readText(),
        )

        val documentedButUnreachable = documented - served
        val reachableButUndocumented = served - documented
        assertTrue(
            "docs/architecture.md and the served routes disagree — " +
                "documented-but-unreachable (the 92b4204 defect class): $documentedButUnreachable; " +
                "reachable-but-undocumented: $reachableButUndocumented",
            documentedButUnreachable.isEmpty() && reachableButUndocumented.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ docs

    /**
     * Extracts (method, path) pairs from the "HTTP bridge endpoints" line,
     * e.g. "GET: `/a`, `/b`. POST: `/c`. Both: `/clipboard`."
     */
    private fun documentedEndpoints(doc: String): Set<Pair<String, String>> {
        val line = doc.lineSequence().firstOrNull { it.contains("HTTP bridge endpoints") }
            ?: throw AssertionError("docs/architecture.md lost its endpoint list")

        val rest = line.substringAfter("GET:")
        assertTrue("endpoint line lost its POST section: $line", ". POST:" in rest)
        val afterPost = rest.substringAfter(". POST:")
        assertTrue("endpoint line lost its Both section: $line", ". Both:" in afterPost)

        val getPart = rest.substringBefore(". POST:")
        val postPart = afterPost.substringBefore(". Both:")
        val bothPart = afterPost.substringAfter(". Both:").trimEnd('.', ' ')

        val endpoints = mutableSetOf<Pair<String, String>>()
        backtickPaths(getPart).forEach { endpoints += "GET" to it }
        backtickPaths(postPart).forEach { endpoints += "POST" to it }
        backtickPaths(bothPart).forEach {
            endpoints += "GET" to it
            endpoints += "POST" to it
        }
        return endpoints
    }

    private fun backtickPaths(section: String): List<String> =
        Regex("`(/[^`]+)`").findAll(section).map { it.groupValues[1] }.toList()

    // ---------------------------------------------------------------- served

    /**
     * The (method, path) pairs the Kotlin bridge serves: every dispatch
     * branch in CommandDispatcher plus BridgeRouter's dedicated /mic_file
     * route (a file download that bypasses the dispatcher) and the catch-all
     * that forwards everything else into it.
     */
    private fun servedRoutes(dispatcherSource: String, routerSource: String): Set<Pair<String, String>> {
        val routes = Regex("method == \"(GET|POST)\" && path == \"(/[^\"]+)\"")
            .findAll(dispatcherSource)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toMutableSet()

        assertTrue(
            "/mic_file must stay registered as a dedicated download route",
            routerSource.contains("get(\"/mic_file\")"),
        )
        routes += "GET" to "/mic_file"

        assertTrue(
            "the catch-all that routes into CommandDispatcher must stay registered",
            routerSource.contains("route(\"{path...}\")"),
        )
        return routes
    }

    // ------------------------------------------------------------------ root

    /**
     * Unit tests run with the app module as working directory; the repo root
     * is a parent of it. Walk upwards rather than guessing the depth so the
     * test survives a moved working directory.
     */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "docs/architecture.md").isFile && File(dir, "hermes-android-bridge").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "repo root not found above ${System.getProperty("user.dir")} " +
                "(need docs/architecture.md and hermes-android-bridge/)",
        )
    }
}
