package com.hermesandroid.bridge.executor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.service.BridgeAccessibilityService

/**
 * Permission-gated device services that read or act on user data: location,
 * SMS, calls and contacts. Every action checks its runtime permission first
 * and fails with an actionable message instead of throwing, and no response
 * echoes a recipient, a body, a number or a location back (AGENTS.md PII).
 *
 * Also owns the SMS send-report plumbing: the per-call broadcast action, the
 * PendingIntent factory, and the bounded wait that turns the radio's answer
 * into an [SmsSendReport] tally.
 *
 * Implementation detail of [ActionExecutor] — callers go through the facade.
 */
internal object DeviceServicesExecutor {

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

    private fun Context.hasSelfPermission(permission: String): Boolean {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
            checkSelfPermission(permission)
    }
}
