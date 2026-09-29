package com.hermesandroid.bridge.executor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.model.computeHash
import com.hermesandroid.bridge.security.SensitiveText
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object ActionExecutor {

    suspend fun tap(x: Int? = null, y: Int? = null, nodeId: String? = null): ActionResult =
        GestureExecutor.tap(x, y, nodeId)

    suspend fun tapText(text: String, exact: Boolean = false): ActionResult =
        GestureExecutor.tapText(text, exact)

    suspend fun typeText(text: String, clearFirst: Boolean = false): ActionResult =
        TextEntryExecutor.typeText(text, clearFirst)

    suspend fun swipe(direction: String, distance: String = "medium"): ActionResult =
        GestureExecutor.swipe(direction, distance)

    fun openApp(packageName: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "App not found: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return ActionResult(true, "Opening $packageName")
    }

    fun pressKey(key: String): ActionResult =
        SystemKeyExecutor.pressKey(key)

    suspend fun waitForElement(
        text: String? = null,
        className: String? = null,
        timeoutMs: Int = 5000
    ): ActionResult {
        val interval = 500L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            val nodes = ScreenReader.readCurrentScreen(false)
            val found = findInTree(nodes, text, className)
            if (found != null) {
                return ActionResult(true, "Element found", found)
            }
            delay(interval)
            elapsed += interval
        }
        return ActionResult(false, "Timeout waiting for element (text=$text, class=$className)")
    }

    suspend fun takeScreenshot(): ActionResult =
        ScreenshotExecutor.takeScreenshot()

    fun getInstalledApps(): List<Map<String, String>> {
        val service = BridgeAccessibilityService.instance ?: return emptyList()
        val pm = service.packageManager
        // Use queryIntentActivities to get all launchable apps (works on Android 11+)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(launchIntent, 0).mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
            mapOf(
                "packageName" to appInfo.packageName,
                "label" to pm.getApplicationLabel(appInfo).toString()
            )
        }.distinctBy { it["packageName"] }.sortedBy { it["label"] }
    }

    suspend fun scroll(direction: String, nodeId: String? = null): ActionResult =
        GestureExecutor.scroll(direction, nodeId)

    fun clipboardRead(): ActionResult =
        TextEntryExecutor.clipboardRead()

    fun clipboardWrite(text: String): ActionResult =
        TextEntryExecutor.clipboardWrite(text)

    suspend fun longPress(x: Int? = null, y: Int? = null, nodeId: String? = null, duration: Long = 500): ActionResult =
        GestureExecutor.longPress(x, y, nodeId, duration)

    suspend fun drag(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 500): ActionResult =
        GestureExecutor.drag(startX, startY, endX, endY, duration)

    fun findNodes(text: String? = null, className: String? = null, clickable: Boolean? = null, limit: Int = 20): ActionResult {
        val nodes = ScreenReader.searchNodes(text, className, clickable, limit)
        if (nodes.isEmpty()) {
            return ActionResult(false, "No matching nodes found")
        }
        return ActionResult(true, "Found ${nodes.size} nodes", mapOf("nodes" to nodes, "count" to nodes.size))
    }

    fun diffScreen(previousHash: String): ActionResult {
        val nodes = ScreenReader.readCurrentScreen(false)
        if (nodes.isEmpty()) {
            return ActionResult(false, "No screen content")
        }
        val currentHash = nodes.joinToString("|") { it.computeHash() }
        if (currentHash == previousHash) {
            return ActionResult(true, "No changes detected", mapOf("changed" to false, "hash" to currentHash))
        }
        val nodeCount = countNodes(nodes)
        return ActionResult(true, "Screen changed", mapOf(
            "changed" to true,
            "hash" to currentHash,
            "nodeCount" to nodeCount
        ))
    }

    private fun countNodes(nodes: List<com.hermesandroid.bridge.model.ScreenNode>): Int {
        var count = 0
        fun walk(ns: List<com.hermesandroid.bridge.model.ScreenNode>) {
            for (n in ns) {
                count++
                walk(n.children)
            }
        }
        walk(nodes)
        return count
    }

    suspend fun pinch(x: Int, y: Int, scale: Float = 1.5f, duration: Long = 300): ActionResult =
        GestureExecutor.pinch(x, y, scale, duration)

    fun describeNode(nodeId: String): ActionResult {
        val node = NodeLookup.findNodeById(nodeId)
            ?: return ActionResult(false, "Node not found: $nodeId")
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        // PII-1: describe a secret field, never disclose it. Everything needed to
        // act on the node stays (bounds, class, viewIdResourceName, editable);
        // text/contentDescription/hintText do not, because the hint and the
        // content description routinely carry the mask or format of the secret.
        val secret = SensitiveText.isSecret(node)
        val result = mutableMapOf<String, Any?>(
            "nodeId" to nodeId,
            "className" to node.className?.toString(),
            "packageName" to node.packageName?.toString(),
            "text" to SensitiveText.resultText(node),
            "contentDescription" to SensitiveText.resultContentDescription(node),
            "hintText" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SensitiveText.hintText(node, node.hintText)
            } else null,
            "bounds" to mapOf("left" to r.left, "top" to r.top, "right" to r.right, "bottom" to r.bottom),
            "clickable" to node.isClickable,
            "longClickable" to node.isLongClickable,
            "focusable" to node.isFocusable,
            "editable" to node.isEditable,
            "scrollable" to node.isScrollable,
            "checkable" to node.isCheckable,
            "checked" to if (node.isCheckable) node.isChecked else null,
            "enabled" to node.isEnabled,
            "selected" to node.isSelected,
            "childCount" to node.childCount,
            "viewIdResourceName" to node.viewIdResourceName,
            "password" to secret
        )
        node.recycle()
        return ActionResult(true, "Node details", result)
    }

    fun location(): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        if (!service.hasSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) &&
            !service.hasSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return ActionResult(false, "Location permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.")
        }
        return try {
            val lm = service.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val providers = lm.getProviders(true)
            var best: android.location.Location? = null
            for (provider in providers) {
                @Suppress("DEPRECATION")
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.accuracy < best.accuracy) {
                    best = loc
                }
            }
            if (best == null) {
                return ActionResult(false, "No location available. Enable GPS/Location.")
            }
            ActionResult(true, "Location", mapOf(
                "latitude" to best.latitude,
                "longitude" to best.longitude,
                "accuracy" to best.accuracy,
                "altitude" to best.altitude,
                "provider" to (best.provider ?: "unknown"),
                "timestamp" to best.time
            ))
        } catch (e: SecurityException) {
            ActionResult(false, "Location permission denied: ${e.message}")
        }
    }

    /**
     * Prefix of the broadcast action the sentIntent PendingIntents report the
     * radio result on. The action a given send listens on is this plus a per-call
     * UUID — see [smsSentReportAction].
     */
    private const val SMS_SENT_REPORT_ACTION_PREFIX =
        "com.hermesandroid.bridge.action.SMS_SENT_REPORT"

    /**
     * How long to wait for the radio's send report. Bounded, because a device
     * with no signal must not hang the request. `internal` so tests can shorten
     * it — the same seam idea as [CommandDispatcher.batteryContext].
     */
    internal var smsSendReportTimeoutMs: Long = 10_000L

    /**
     * Send an SMS and report what the radio actually did (review L2).
     *
     * `sendTextMessage` returns once the message is queued, so this divides the
     * body, passes a `sentIntent` per segment, and waits — bounded — for the
     * reports. A report that says the send failed is `success: false`; no report
     * inside the bound is `success: true` with status `queued_unconfirmed` and an
     * explicit do-not-resend (review F14), because the alternative read as "try
     * again" and the recipient got the message twice. The recipient and the body
     * are never echoed back (AGENTS.md PII rule).
     */
    fun sendSms(to: String, body: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        if (!service.hasSelfPermission(android.Manifest.permission.SEND_SMS)) {
            return ActionResult(false, "SEND_SMS permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.")
        }
        val smsManager = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                service.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            return ActionResult(false, "SMS unavailable on this device (${e.javaClass.simpleName})")
        }

        // Divide first. A body over the single-segment limit passed to
        // sendTextMessage fails at the radio layer; over the limit it must go
        // through sendMultipartTextMessage.
        val parts: ArrayList<String> = try {
            smsManager.divideMessage(body) ?: arrayListOf(body)
        } catch (e: Exception) {
            arrayListOf(body)
        }

        val timeoutMs = smsSendReportTimeoutMs
        val report = SmsSendReport(parts.size)
        // F15: one action per call, so this send's reports cannot be counted
        // toward another one. A context-registered receiver gets every broadcast
        // matching its filter, and every send used to register the same one.
        val reportAction = smsSentReportAction(java.util.UUID.randomUUID().toString())
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // The filter already scopes delivery to this call, so this is
                // defence in depth: a report addressed to somebody else is not
                // this call's answer, whatever resultCode it carries.
                val intentAction = intent?.action
                if (intentAction != null && intentAction != reportAction) return
                // resultCode is the platform's own send-report code.
                report.onResult(resultCode)
            }
        }
        val registered = try {
            val filter = android.content.IntentFilter(reportAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // targetSdk 34 requires the export flag. NOT_EXPORTED is correct:
                // the platform fires our own PendingIntent, which sends with the
                // creator's identity, so the report still reaches us while no
                // other app can spoof one.
                service.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                service.registerReceiver(receiver, filter)
            }
            true
        } catch (e: Exception) {
            // Without a receiver there is no report at all, so the outcome is the
            // unconfirmed one — never a claimed success.
            false
        }

        return try {
            val sentIntents = ArrayList<android.app.PendingIntent>(parts.size)
            for (index in parts.indices) {
                sentIntents += smsSentReportIntent(service, reportAction, index)
            }
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(to, null, body, sentIntents.firstOrNull(), null)
            }
            if (registered) {
                // F36: ONE snapshot, not `await()` followed by two separate counter
                // reads. The receiver is still registered when the bound runs out,
                // so a late RESULT_OK landing between the reads turned a partly
                // delivered message into confirmed=0/reported=parts — the shape
                // describe() maps to "failed, a retry is safe". The tally's
                // completeness (F30) and its confirmed count (F23) now describe the
                // same instant by construction.
                SmsSendReport.describe(report.awaitTally(timeoutMs), parts.size, timeoutMs)
            } else {
                // F27: with no receiver no report can arrive, so waiting out the
                // bound would spend ten seconds of the caller's budget learning
                // nothing — and then answer with wording that claims we waited for
                // one. The send itself still happens: an unobservable report is not
                // a reason to withhold the message.
                SmsSendReport.describeUnobservable(parts.size, "no send-report receiver")
            }
        } catch (e: SecurityException) {
            ActionResult(false, "SMS permission denied: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            // AOSP throws this for an invalid destination, and its message
            // quotes the address — so report the class and never the message.
            ActionResult(false, "SMS rejected by the platform (${e.javaClass.simpleName})")
        } catch (e: Exception) {
            ActionResult(false, "SMS send failed (${e.javaClass.simpleName})")
        } finally {
            if (registered) runCatching { service.unregisterReceiver(receiver) }
        }
    }

    /**
     * The broadcast action one send's reports arrive on (review F15).
     *
     * Every call used to share [SMS_SENT_REPORT_ACTION_PREFIX] and requestCodes
     * `0..n-1`. Two consequences, both silent:
     *  - two overlapping sends each registered a receiver for the same action, and
     *    a registered receiver gets *every* matching broadcast, so each call
     *    counted the other's segment reports — one part of a two-part send could
     *    be confirmed by an unrelated message, or failed by one;
     *  - `PendingIntent` identity is `requestCode` plus `Intent.filterEquals`, and
     *    `filterEquals` ignores extras, so call B's segment-0 PendingIntent *was*
     *    call A's. With `FLAG_IMMUTABLE` the extras cannot be updated, so B would
     *    have been handed A's intent outright.
     *
     * A UUID in the action closes both: the filter matches only this call's
     * reports, and the intents no longer compare equal across calls. Internal for
     * `ActionExecutorSendSmsTest`, which asserts the uniqueness and the shape.
     */
    internal fun smsSentReportAction(callId: String): String =
        "$SMS_SENT_REPORT_ACTION_PREFIX.$callId"

    private fun smsSentReportIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            context,
            // One per segment, so each part's report is distinct. Safe to restart
            // at 0 for every call now that the action carries the call's identity.
            requestCode,
            Intent(action).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun makeCall(number: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val hasCallPermission = service.hasSelfPermission(android.Manifest.permission.CALL_PHONE)
        val intent = Intent(if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL).apply {
            data = android.net.Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            service.startActivity(intent)
            ActionResult(true, if (hasCallPermission) "Calling" else "Opened dialer (grant CALL_PHONE permission to auto-dial)")
        } catch (e: SecurityException) {
            ActionResult(false, "Call failed: ${e.message}")
        }
    }

    fun mediaControl(action: String): ActionResult =
        SystemKeyExecutor.mediaControl(action)

    fun searchContacts(query: String, limit: Int = 20): ActionResult {
        val safeLimit = limit.coerceAtMost(100).coerceAtLeast(1)
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        if (!service.hasSelfPermission(android.Manifest.permission.READ_CONTACTS)) {
            return ActionResult(false, "READ_CONTACTS permission not granted. Grant it in Settings > Apps > Hermes Bridge > Permissions.")
        }
        return try {
            val results = mutableListOf<Map<String, String?>>()
            val safeQuery = android.net.Uri.encode(query)
            val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.Contacts.CONTENT_FILTER_URI, safeQuery)
            val projection = arrayOf(
                android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME
            )
            val cursor = service.contentResolver.query(uri, projection, null, null, "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} ASC")
            cursor?.use {
                val idIdx = it.getColumnIndex(android.provider.ContactsContract.Contacts._ID)
                val nameIdx = it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                while (it.moveToNext() && results.size < safeLimit) {
                    val contactId = it.getString(idIdx) ?: continue
                    val name = it.getString(nameIdx) ?: continue
                    val phoneNumbers = mutableListOf<String>()
                    val phoneCursor = service.contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )
                    phoneCursor?.use { pc ->
                        val numIdx = pc.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (pc.moveToNext()) {
                            pc.getString(numIdx)?.let { num -> phoneNumbers.add(num) }
                        }
                    }
                    results.add(mapOf("id" to contactId, "name" to name, "phones" to phoneNumbers.joinToString(", ")))
                }
            }
            if (results.isEmpty()) {
                ActionResult(false, "No contacts found matching '$query'")
            } else {
                ActionResult(true, "Found ${results.size} contacts", mapOf("contacts" to results, "count" to results.size))
            }
        } catch (e: SecurityException) {
            ActionResult(false, "READ_CONTACTS permission denied: ${e.message}")
        }
    }

    fun sendIntent(action: String, dataUri: String? = null, extras: Map<String, String>? = null, packageOverride: String? = null): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        // Reject activity-launch actions that could be used to install/uninstall
        // packages, factory reset, or otherwise damage the device. These have no
        // legitimate agent use case via a raw intent (the dedicated call/send_sms
        // tools cover telephony, and settings can be navigated to via open_app).
        // Custom namespace actions (containing a dot) are NOT exempt here, because
        // package-manager actions like android.intent.action.DELETE are also dotted.
        val blockedIntents = setOf(
            // Package management — install/uninstall from arbitrary sources
            "android.intent.action.INSTALL_PACKAGE",
            "android.intent.action.DELETE",
            "android.intent.action.UNINSTALL_PACKAGE",
            "android.intent.action.PACKAGE_INSTALL",
            // Device wipe / factory reset
            "android.intent.action.MASTER_CLEAR",
            "android.intent.action.FACTORY_RESET",
            "android.intent.action.ACTION_SHUTDOWN",
            // Telephony — direct dial without confirmation
            "android.intent.action.CALL",
            "android.intent.action.CALL_PRIVILEGED",
            // Dangerous settings toggles — permissions, accessibility, dev options
            "android.settings.ACCESSIBILITY_SETTINGS",
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            "android.settings.APPLICATION_DEVELOPMENT_SETTINGS",
            "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
            "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
            "android.settings.MANAGE_OVERLAY_PERMISSION",
            "android.settings.action.MANAGE_OVERLAY_PERMISSION",
            "android.settings.NOTIFICATION_LISTENER_SETTINGS",
            "android.settings.USAGE_ACCESS_SETTINGS",
            "android.settings.VR_LISTENER_SETTINGS",
            "android.settings.WIFI_SETTINGS",
            "android.settings.WIRELESS_SETTINGS",
            "android.settings.AIRPLANE_MODE_SETTINGS",
            "android.settings.DATA_ROAMING_SETTINGS",
            "android.settings.SECURITY_SETTINGS",
            "android.settings.PRIVACY_SETTINGS",
            "android.settings.BIOMETRIC_ENROLL",
            "android.settings.ADD_ACCOUNT_SETTINGS",
            "android.settings.SYNC_SETTINGS",
            // Unknown app sources (alternate action name)
            "android.provider.action.MANAGE_UNKNOWN_APP_SOURCES",
            // App ops — can grant dangerous permissions
            "android.settings.APP_OPS_SETTINGS"
        )
        // Block any android.settings.* action that isn't explicitly allowlisted
        val blockedPrefixes = listOf(
            "android.settings.",
            "android.provider.action."
        )
        if (action.isBlank()) {
            return ActionResult(false, "Intent action is empty")
        }
        if (action in blockedIntents) {
            return ActionResult(false, "Intent action '$action' is blocked for safety")
        }
        if (blockedPrefixes.any { action.startsWith(it) }) {
            return ActionResult(false, "Intent action '$action' is blocked for safety (settings/provider actions are not allowed)")
        }

        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (dataUri != null) {
                // Denylist dangerous URI schemes that can bypass the action blocklist.
                // An attacker can use a benign action like VIEW with a malicious URI
                // (intent:// redirects, content:// providers, market:// deep links,
                //  tel:, sms:, smsto:, mms:, mmsto:).
                //
                // CTRL-2: normalise ONCE, before any check. Leading whitespace used
                // to defeat scheme extraction completely (" tel:+19005550100" yields
                // scheme " tel"), and the content:// prefix check ran against the
                // raw input, so "  content://sms/inbox" walked straight through.
                val normalizedUri = dataUri.trim()
                val lowerUri = normalizedUri.lowercase()
                val blockedUriSchemes = setOf(
                    "intent", "market", "tel", "sms", "smsto", "mms", "mmsto"
                )
                // Personal-data providers. Two entries ("content://settings" and
                // "content://com.android.contacts") do not amount to "block
                // dangerous content providers": SMS, MMS, the call log, telephony
                // and the other contacts authorities were all accepted.
                val blockedUriPrefixes = listOf(
                    "content://settings",
                    "content://sms",
                    "content://mms",
                    "content://mms-sms",
                    "content://call_log",
                    "content://telephony",
                    "content://contacts",
                    "content://icc",
                    "content://com.android.contacts",
                    "content://com.android.calllog",
                    "content://com.android.mms",
                    "content://com.android.mms-sms",
                    "content://com.android.providers.telephony",
                    "content://com.android.providers.contacts"
                )
                // Handle both standard schemes (http://, intent://) and single-colon
                // schemes (tel:, smsto:, mmsto:). substringBefore("://") alone fails
                // for single-colon URIs because "://" is absent, returning the full
                // string (e.g. "tel:123456") instead of just "tel".
                val scheme = lowerUri.substringBefore("://").substringBefore(":").trim()
                if (scheme in blockedUriSchemes) {
                    return ActionResult(false, "URI scheme '$scheme' is blocked for safety")
                }
                if (blockedUriPrefixes.any { lowerUri.startsWith(it) }) {
                    return ActionResult(false, "Content provider URI is blocked for safety")
                }
                setData(android.net.Uri.parse(normalizedUri))
            }
            extras?.forEach { (key, value) ->
                putExtra(key, value)
            }
            if (packageOverride != null) {
                setPackage(packageOverride)
            }
        }
        return try {
            service.startActivity(intent)
            ActionResult(true, "Intent sent: $action")
        } catch (e: Exception) {
            ActionResult(false, "Intent failed: ${e.message}")
        }
    }

    fun sendBroadcast(action: String, extras: Map<String, String>? = null): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        // Block known-dangerous system broadcasts that can cause DoS or abuse
        // device resources when fired from an unprivileged bridge context.
        // Custom namespace actions (containing a dot) are allowed.
        val blockedBroadcasts = setOf(
            "android.intent.action.MEDIA_MOUNTED",
            "android.intent.action.MEDIA_UNMOUNTED",
            "android.intent.action.MEDIA_EJECT",
            "android.intent.action.MEDIA_SCANNER_FINISHED",
            "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "android.intent.action.PACKAGE_ADDED",
            "android.intent.action.PACKAGE_REMOVED",
            "android.intent.action.PACKAGE_REPLACED",
            "android.intent.action.PACKAGE_DATA_CLEARED",
            "android.intent.action.DEVICE_STORAGE_LOW",
            "android.intent.action.DEVICE_STORAGE_OK",
            "android.intent.action.ACTION_SHUTDOWN",
            "android.intent.action.MASTER_CLEAR",
            "android.intent.action.FACTORY_RESET"
        )
        if (action.isBlank()) {
            return ActionResult(false, "Broadcast action is empty")
        }
        if (action in blockedBroadcasts) {
            return ActionResult(false, "Broadcast action '$action' is blocked for safety")
        }

        return try {
            val intent = Intent(action)
            extras?.forEach { (key, value) -> intent.putExtra(key, value) }
            service.sendBroadcast(intent)
            ActionResult(true, "Broadcast sent: $action")
        } catch (e: SecurityException) {
            ActionResult(false, "Broadcast failed: ${e.message}")
        }
    }

    fun screenHash(): ActionResult {
        val nodes = ScreenReader.readCurrentScreen(false)
        if (nodes.isEmpty()) {
            return ActionResult(false, "No screen content")
        }
        val hash = nodes.joinToString("|") { it.computeHash() }
        val count = nodes.sumOf { countNodes(it) }
        return ActionResult(true, "Screen hash", mapOf("hash" to hash, "nodeCount" to count))
    }

    private fun countNodes(node: ScreenNode): Int {
        return 1 + node.children.sumOf { countNodes(it) }
    }

    suspend fun readWidgets(): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(homeIntent)
        delay(1000)

        val windows = service.windows
        val roots = windows.mapNotNull { it.root }
        val widgets = mutableListOf<Map<String, Any?>>()
        for (root in roots) {
            collectWidgetInfo(root, widgets, 0)
            root.recycle()
        }
        windows.forEach { it.recycle() }

        return ActionResult(true, "Found ${widgets.size} widget elements", mapOf("widgets" to widgets, "count" to widgets.size))
    }

    private fun collectWidgetInfo(node: AccessibilityNodeInfo, widgets: MutableList<Map<String, Any?>>, depth: Int) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val className = node.className?.toString() ?: ""

        if ((depth <= 3 && (text != null || desc != null) && className.contains("Widget", ignoreCase = true)) || (depth <= 2 && (text != null || desc != null))) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            widgets.add(mapOf(
                "text" to text,
                "contentDescription" to desc,
                "className" to className,
                "bounds" to "${r.left},${r.top},${r.right},${r.bottom}",
                "packageName" to (node.packageName?.toString() ?: "")
            ))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectWidgetInfo(child, widgets, depth + 1)
            child.recycle()
        }
    }

    private fun findInTree(
        nodes: List<com.hermesandroid.bridge.model.ScreenNode>,
        text: String?,
        className: String?
    ): com.hermesandroid.bridge.model.ScreenNode? {
        for (node in nodes) {
            val textMatch = text == null || node.text?.contains(text, true) == true ||
                    node.contentDescription?.contains(text, true) == true
            val classMatch = className == null || node.className == className
            if (textMatch && classMatch) return node
            val childMatch = findInTree(node.children, text, className)
            if (childMatch != null) return childMatch
        }
        return null
    }

    @Volatile private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false

    private suspend fun ensureTts(): Boolean {
        val service = BridgeAccessibilityService.instance ?: return false
        if (tts == null || !ttsReady) {
            ttsReady = suspendCancellableCoroutine { cont ->
                tts = android.speech.tts.TextToSpeech(service.applicationContext, android.speech.tts.TextToSpeech.OnInitListener { status ->
                    val ready = status == android.speech.tts.TextToSpeech.SUCCESS
                    ttsReady = ready
                    cont.resume(ready)
                })
            }
        }
        return ttsReady
    }

    suspend fun speak(text: String, queue: Int = android.speech.tts.TextToSpeech.QUEUE_ADD): ActionResult {
        if (!ensureTts()) {
            return ActionResult(false, "TTS not available")
        }
        tts?.speak(text, queue, null, "hermes_speak_${System.currentTimeMillis()}")
        return ActionResult(true, "Speaking: ${text.take(50)}")
    }

    fun stopSpeaking(): ActionResult {
        tts?.stop()
        return ActionResult(true, "Stopped speaking")
    }

    fun shutdownTts() {
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun Context.hasSelfPermission(permission: String): Boolean {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
            checkSelfPermission(permission)
    }
}
