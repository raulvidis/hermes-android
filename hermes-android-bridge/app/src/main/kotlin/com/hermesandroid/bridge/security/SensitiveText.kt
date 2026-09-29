package com.hermesandroid.bridge.security

import android.view.accessibility.AccessibilityNodeInfo

/**
 * PII-1: the single redaction choke point between the accessibility tree and the
 * JSON that leaves the device.
 *
 * `AccessibilityNodeInfo.isPassword` was never consulted anywhere in the bridge,
 * so the contents of a password / OTP / PIN field were serialised into `/screen`,
 * `/find_nodes`, `/describe_node` and `/events` — transmitted to the relay,
 * stored in the agent transcript and forwarded to the model provider, with no
 * indication to the user that it happened.
 *
 * Contract, deliberately narrow:
 *  - a secret field **stays in the tree** (nodeId, class, bounds, editable) so an
 *    agent can still see that the field exists and act on it by node id;
 *  - its `text` becomes [REDACTED];
 *  - `contentDescription` and `hintText` are dropped, because they are not
 *    password-marked and routinely carry the mask or format of the secret;
 *  - text *searches* match the redacted value, so `find_nodes`/`tap_text` cannot
 *    be used as a character-by-character oracle against the field.
 *
 * Screenshots and screen recordings capture rendered pixels and cannot be
 * redacted here — see SECURITY.md.
 */
object SensitiveText {

    /** Placeholder emitted in place of a secret field's text. */
    const val REDACTED = "[redacted]"

    /** True when [node] is a password / secret input field. */
    fun isSecret(node: AccessibilityNodeInfo?): Boolean = node?.isPassword == true

    /**
     * Text for the screen tree: the real text, [REDACTED] for a secret field, and
     * null when blank (unchanged from the previous behaviour for normal fields).
     */
    fun text(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        if (isSecret(node)) return REDACTED
        return node.text?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * contentDescription for the screen tree. Dropped entirely for a secret field.
     */
    fun contentDescription(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        if (isSecret(node)) return null
        return node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
    }

    /** Unfiltered text for search-result maps ([REDACTED] for a secret field). */
    fun resultText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        if (isSecret(node)) return REDACTED
        return node.text?.toString()
    }

    /** Unfiltered contentDescription for search-result maps (null if secret). */
    fun resultContentDescription(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        if (isSecret(node)) return null
        return node.contentDescription?.toString()
    }

    /** hintText, suppressed for a secret field. Callers pass the platform value. */
    fun hintText(node: AccessibilityNodeInfo?, hint: CharSequence?): String? {
        if (node == null || isSecret(node)) return null
        return hint?.toString()
    }

    /**
     * The string a text search may match against: never the secret itself, so a
     * search cannot be turned into an oracle. Preserves the previous
     * text-then-contentDescription fallback for everything else.
     */
    fun searchableText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        if (isSecret(node)) return REDACTED
        return node.text?.toString() ?: node.contentDescription?.toString() ?: ""
    }
}
