package com.hermesandroid.bridge

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * [runTest] with a real-time dispatch timeout that survives a cold JVM.
 *
 * `runTest`'s default `dispatchTimeoutMs` is 10 s of *wall clock*. mockk's
 * first-time proxy generation for a large Android class such as
 * `AccessibilityNodeInfo` can exceed that on a loaded machine: the test body
 * still runs to completion (measured 17.6 s cold vs 82 ms once the proxy is
 * cached), but `runTest` has already failed it with `UncompletedCoroutinesError`.
 *
 * 120 s still catches a genuine hang while not penalising cold-start cost.
 */
const val BRIDGE_TEST_DISPATCH_TIMEOUT_MS = 120_000L

fun bridgeTest(body: suspend TestScope.() -> Unit) =
    runTest(dispatchTimeoutMs = BRIDGE_TEST_DISPATCH_TIMEOUT_MS, testBody = body)
