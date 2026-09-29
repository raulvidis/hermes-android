package com.hermesandroid.bridge.model

data class ScreenNode(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String?,
    val clickable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checked: Boolean?,
    val bounds: NodeBounds?,
    val children: List<ScreenNode> = emptyList()
)

data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

fun ScreenNode.computeHash(): String {
    val childHashes = children.joinToString(",") { it.computeHash() }
    val raw = listOf(
        nodeId, text, contentDescription, className,
        clickable, focusable, scrollable, editable, checked, childHashes
    ).joinToString("|") { hashField(it) }
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
}

/**
 * Encode one hash field unambiguously: `-` for null, otherwise the value
 * prefixed with its own length.
 *
 * Joining the raw values let field content shift the layout - a `|` inside
 * `text`, or a literal "null", could make two genuinely different nodes hash
 * identically. A length prefix cannot be forged by field content, because the
 * reader always knows where the value ends. This changes every hash once; see
 * CHANGELOG for the `/screen_diff` impact across the upgrade.
 */
private fun hashField(value: Any?): String {
    if (value == null) return "-"
    val text = value.toString()
    return "${text.length}:$text"
}

data class ActionResult(
    val success: Boolean,
    val message: String = "",
    val data: Any? = null
)
