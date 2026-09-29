package com.hermesandroid.bridge.executor

import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.service.BridgeAccessibilityService

/**
 * Node lookup by the stable node ID format [ScreenReader] publishes.
 *
 * Implementation detail of [ActionExecutor] — callers go through the facade.
 */
internal object NodeLookup {

    fun findNodeById(nodeId: String): AccessibilityNodeInfo? {
        val service = BridgeAccessibilityService.instance ?: return null
        val windows = service.windows
        val roots = windows.mapNotNull { it.root }
        var found: AccessibilityNodeInfo? = null
        for ((wi, root) in roots.withIndex()) {
            val matches = findNodeByIdInTree(root, nodeId, "$wi")
            if (matches.isNotEmpty()) {
                found = matches.first()
                if (found !== roots[wi]) roots[wi].recycle()
                for (r in roots.subList(wi + 1, roots.size)) r.recycle()
                break
            }
            root.recycle()
        }
        windows.forEach { it.recycle() }
        return found
    }

    /**
     * DFS search matching the stable ID format from ScreenReader.buildNode.
     *
     * Memory contract: the returned node(s) are intentionally NOT recycled — the caller
     * owns them and must recycle when done. Unmatched child nodes traversed during the
     * DFS are recycled, including intermediate ancestors on the path to a match.
     * Siblings after the first match are skipped
     * (early break) and left for the system to reclaim.
     */
    private fun findNodeByIdInTree(
        info: AccessibilityNodeInfo, targetId: String, path: String
    ): List<AccessibilityNodeInfo> {
        val r = android.graphics.Rect()
        info.getBoundsInScreen(r)
        val id = "${info.packageName ?: "?"}_${info.className ?: "?"}_${path}_${r.left}_${r.top}_${r.right}_${r.bottom}"
        if (id == targetId) return listOf(info)
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until info.childCount) {
            val child = info.getChild(i) ?: continue
            val found = findNodeByIdInTree(child, targetId, "${path}_$i")
            if (found.isNotEmpty()) {
                results.addAll(found)
                // Recycle the intermediate ancestor unless the child itself is a match
                if (found.none { it === child }) child.recycle()
                break
            } else {
                child.recycle()
            }
        }
        return results
    }
}
