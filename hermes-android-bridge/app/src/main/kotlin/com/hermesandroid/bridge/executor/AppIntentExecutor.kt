package com.hermesandroid.bridge.executor

import android.content.Intent
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.service.BridgeAccessibilityService

/**
 * App launch and raw intent/broadcast senders: openApp, getInstalledApps,
 * sendIntent and sendBroadcast.
 *
 * Both senders carry the safety denylists — blocked actions, the
 * android.settings./android.provider.action. prefixes, and the URI scheme and
 * personal-data-provider rules — kept together in this one file so a security
 * review can read them in one place.
 *
 * Implementation detail of [ActionExecutor] — callers go through the facade.
 */
internal object AppIntentExecutor {

    fun openApp(packageName: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "App not found: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return ActionResult(true, "Opening $packageName")
    }

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
            // Device wipe / factory reset / power
            "android.intent.action.MASTER_CLEAR",
            "android.intent.action.FACTORY_RESET",
            "android.intent.action.ACTION_SHUTDOWN",
            "android.intent.action.REBOOT",
            "android.intent.action.REQUEST_SHUTDOWN",
            "com.android.internal.intent.action.REQUEST_SHUTDOWN",
            // Telephony — direct dial without confirmation
            "android.intent.action.CALL",
            "android.intent.action.CALL_PRIVILEGED",
            "android.intent.action.CALL_EMERGENCY",
            // Default-app / role changes (e.g. taking over the default SMS app)
            "android.provider.Telephony.ACTION_CHANGE_DEFAULT",
            "android.telecom.action.CHANGE_DEFAULT_DIALER",
            "android.app.role.action.REQUEST_ROLE",
            // Runtime-permission management UI
            "android.intent.action.MANAGE_PERMISSIONS",
            "android.intent.action.MANAGE_APP_PERMISSIONS",
            "android.intent.action.MANAGE_PERMISSION_APPS",
            "android.intent.action.MANAGE_DEFAULT_APP",
            // Device admin, screen lock and provisioning. Listed one by one
            // rather than as an android.app.action. prefix: that namespace
            // also holds harmless actions.
            "android.app.action.ADD_DEVICE_ADMIN",
            "android.app.action.SET_NEW_PASSWORD",
            "android.app.action.SET_NEW_PARENT_PROFILE_PASSWORD",
            "android.app.action.START_ENCRYPTION",
            "android.app.action.PROVISION_MANAGED_PROFILE",
            "android.app.action.PROVISION_MANAGED_DEVICE",
            "android.app.action.PROVISION_FINANCED_DEVICE",
            "android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE",
            "android.app.action.CONFIRM_DEVICE_CREDENTIAL",
            "android.app.action.CONFIRM_FRP_CREDENTIAL",
            // Credential storage — install/reset CA and user certificates
            "android.credentials.INSTALL",
            "android.credentials.INSTALL_AS_USER",
            "android.credentials.RESET",
            "android.credentials.UNLOCK",
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
                //  tel:, sms:, smsto:, mms:, mmsto:, and the sip:/voicemail:/wtai:
                //  dialer entry points).
                //
                // CTRL-2: normalise ONCE, before any check. Leading whitespace used
                // to defeat scheme extraction completely (" tel:+19005550100" yields
                // scheme " tel"), and the content:// prefix check ran against the
                // raw input, so "  content://sms/inbox" walked straight through.
                val normalizedUri = dataUri.trim()
                val lowerUri = normalizedUri.lowercase()
                val blockedUriSchemes = setOf(
                    "intent", "market", "tel", "sms", "smsto", "mms", "mmsto",
                    "sip", "voicemail", "wtai"
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
                // A content URI may name its provider with a "<userId>@" prefix
                // (content://0@com.android.contacts/...), which the resolver
                // strips but the raw prefix check above does not. Re-check the
                // parsed authority with that qualifier removed.
                if (scheme == "content") {
                    val authority = android.net.Uri.parse(normalizedUri).authority
                        ?.substringAfterLast('@')?.trim()?.lowercase()
                    if (authority != null && blockedUriPrefixes.any { "content://$authority".startsWith(it) }) {
                        return ActionResult(false, "Content provider URI is blocked for safety")
                    }
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
            "android.intent.action.FACTORY_RESET",
            "android.intent.action.REBOOT",
            "android.intent.action.REQUEST_SHUTDOWN",
            "com.android.internal.intent.action.REQUEST_SHUTDOWN"
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
        } catch (e: Exception) {
            // SecurityException (a protected broadcast) is the common case, but
            // anything thrown here must become an error result, as in
            // sendIntent, rather than escape to the transport as a 500.
            ActionResult(false, "Broadcast failed: ${e.message}")
        }
    }
}
