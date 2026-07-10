package com.hermesandroid.bridge.executor

import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.NodeBounds
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.service.BridgeAccessibilityService

object ScreenReader {

    /** Android System UI package: status bar, navigation bar, etc. Filtered out of
     *  screen dumps by default for token efficiency. Navigation (back/home/recents)
     *  is available via android_press_key, so nav-bar nodes aren't needed. */
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /**
     * @param includeBounds include pixel bounds for each node
     * @param includeSystemUi include System UI nodes (status bar, nav bar).
     *  Defaults to false: these are non-app chrome that wastes tokens and churns
     *  screen hashes (clock/battery update every minute).
     */
    fun readCurrentScreen(includeBounds: Boolean, includeSystemUi: Boolean = false): List<ScreenNode> {
        val service = BridgeAccessibilityService.instance
            ?: return listOf()

        val windows = service.windows
        val roots = windows.mapNotNull { it.root }
        val result = roots.mapIndexed { i, root -> buildNode(root, includeBounds, "$i", includeSystemUi) }
            .filterNotNull()
        roots.forEach { it.recycle() }
        windows.forEach { it.recycle() }
        return result
    }

    private fun buildNode(info: AccessibilityNodeInfo, includeBounds: Boolean, path: String = "0", includeSystemUi: Boolean = false): ScreenNode? {
        // Filter System UI (status bar, nav bar) unless explicitly included.
        // Returning null drops the whole subtree (status/nav bar live under systemui roots).
        if (!includeSystemUi && info.packageName?.toString() == SYSTEM_UI_PACKAGE) return null

        // Always get bounds for stable ID generation
        val r = android.graphics.Rect()
        info.getBoundsInScreen(r)
        val rect = if (includeBounds) NodeBounds(r.left, r.top, r.right, r.bottom) else null

        // Stable ID: path in tree + bounds (survives re-reads unlike hashCode)
        val nodeId = "${info.packageName ?: "?"}_${info.className ?: "?"}_${path}_${r.left}_${r.top}_${r.right}_${r.bottom}"

        val children = (0 until info.childCount)
            .mapNotNull { i ->
                val child = info.getChild(i) ?: return@mapNotNull null
                val node = buildNode(child, includeBounds, "${path}_$i", includeSystemUi)
                child.recycle()
                node
            }

        return ScreenNode(
            nodeId = nodeId,
            text = info.text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = info.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            className = info.className?.toString(),
            packageName = info.packageName?.toString(),
            clickable = info.isClickable,
            focusable = info.isFocusable,
            scrollable = info.isScrollable,
            editable = info.isEditable,
            checked = if (info.isCheckable) info.isChecked else null,
            bounds = rect,
            children = children
        )
    }

    fun findNodeByText(
        text: String,
        exact: Boolean = false
    ): AccessibilityNodeInfo? {
        val service = BridgeAccessibilityService.instance ?: return null
        val windows = service.windows
        val roots = windows.mapNotNull { it.root }
        var found: AccessibilityNodeInfo? = null
        var foundIndex = -1
        for ((i, root) in roots.withIndex()) {
            val result = findNodeByTextDfs(root, text, exact)
            if (result != null) {
                found = result
                foundIndex = i
                // Don't recycle root if the found node IS the root
                if (result !== root) root.recycle()
                break
            }
            root.recycle()
        }
        // Recycle remaining unprocessed roots after early break
        // Guard: when no match is found (foundIndex == -1), all roots were already
        // recycled in the loop above — the cleanup would re-recycle them.
        if (foundIndex >= 0) {
            for (i in (foundIndex + 1) until roots.size) {
                roots[i].recycle()
            }
        }
        windows.forEach { it.recycle() }
        return found
    }

    private fun findNodeByTextDfs(
        node: AccessibilityNodeInfo,
        text: String,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if ((exact && nodeText == text) || (!exact && nodeText.contains(text, ignoreCase = true))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextDfs(child, text, exact)
            if (found != null) {
                // Recycle the intermediate ancestor unless the child itself is the match
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    fun searchNodes(textFilter: String? = null, classNameFilter: String? = null, clickableFilter: Boolean? = null, limit: Int = 20): List<Map<String, Any?>> {
        val service = BridgeAccessibilityService.instance ?: return emptyList()
        val results = mutableListOf<Map<String, Any?>>()
        val windows = service.windows
        val roots = windows.mapNotNull { it.root }
        for ((wi, root) in roots.withIndex()) {
            searchNodesDfs(root, textFilter, classNameFilter, clickableFilter, limit, results, "$wi")
            root.recycle()
            if (results.size >= limit) break
        }
        windows.forEach { it.recycle() }
        return results
    }

    private fun searchNodesDfs(
        node: AccessibilityNodeInfo,
        textFilter: String?,
        classNameFilter: String?,
        clickableFilter: Boolean?,
        limit: Int,
        results: MutableList<Map<String, Any?>>,
        path: String
    ) {
        if (results.size >= limit) return
        val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val nodeClass = node.className?.toString() ?: ""
        val matches = (textFilter == null || nodeText.contains(textFilter, ignoreCase = true)) &&
                (classNameFilter == null || nodeClass.contains(classNameFilter, ignoreCase = true)) &&
                (clickableFilter == null || node.isClickable == clickableFilter)
        if (matches) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            results.add(mapOf(
                "nodeId" to "${node.packageName ?: "?"}_${node.className ?: "?"}_${path}_${r.left}_${r.top}_${r.right}_${r.bottom}",
                "text" to node.text?.toString(),
                "contentDescription" to node.contentDescription?.toString(),
                "className" to nodeClass,
                "clickable" to node.isClickable,
                "bounds" to "${r.left},${r.top},${r.right},${r.bottom}"
            ))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchNodesDfs(child, textFilter, classNameFilter, clickableFilter, limit, results, "${path}_$i")
            child.recycle()
        }
    }
}
