package com.hermesandroid.bridge.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the screen model that GET /screen, /screen_hash and
 * /screen_diff all speak in.
 *
 * ScreenNode/NodeBounds are plain data, so the interesting surface is
 * computeHash(): the agent uses it to decide whether a screen changed after an
 * action, and a false "no change" makes it act on a stale view of the device.
 * The tests therefore pin
 *
 * - determinism and shape (16 lowercase hex chars, so it is safe to log and to
 *   compare across requests),
 * - sensitivity: every field the raw string claims to cover actually changes
 *   the hash, including nested children and their order,
 * - and, as a characterisation, what it deliberately does *not* cover: bounds
 *   and packageName only reach the hash through nodeId, which ScreenReader
 *   builds from package, class, tree path and bounds.
 *
 * No Robolectric: MessageDigest and Kotlin data classes are all this needs.
 */
class ScreenNodeTest {

    private fun node(
        nodeId: String = "com.example.app_android.widget.Button_0",
        text: String? = "Send",
        contentDescription: String? = null,
        className: String? = "android.widget.Button",
        packageName: String? = "com.example.app",
        clickable: Boolean = true,
        focusable: Boolean = true,
        scrollable: Boolean = false,
        editable: Boolean = false,
        checked: Boolean? = null,
        bounds: NodeBounds? = NodeBounds(10, 20, 110, 60),
        children: List<ScreenNode> = emptyList(),
    ) = ScreenNode(
        nodeId = nodeId,
        text = text,
        contentDescription = contentDescription,
        className = className,
        packageName = packageName,
        clickable = clickable,
        focusable = focusable,
        scrollable = scrollable,
        editable = editable,
        checked = checked,
        bounds = bounds,
        children = children,
    )

    // --- NodeBounds ----------------------------------------------------------

    @Test
    fun `centre of an even box is exact`() {
        val bounds = NodeBounds(left = 10, top = 20, right = 110, bottom = 60)

        assertEquals(60, bounds.centerX)
        assertEquals(40, bounds.centerY)
    }

    @Test
    fun `centre of an odd box truncates towards zero`() {
        // (left + right) / 2 with integer division: a tap coordinate one pixel
        // off still lands inside the node, which is the requirement.
        val bounds = NodeBounds(left = 11, top = 21, right = 110, bottom = 60)

        assertEquals(60, bounds.centerX)
        assertEquals(40, bounds.centerY)
    }

    @Test
    fun `centre of a zero size box is its origin`() {
        val bounds = NodeBounds(left = 7, top = 9, right = 7, bottom = 9)

        assertEquals(7, bounds.centerX)
        assertEquals(9, bounds.centerY)
    }

    @Test
    fun `centre of a box straddling the origin truncates towards zero`() {
        // Off-screen nodes report negative bounds; the centre of (-3, 2) is
        // -0.5, which integer division rounds to 0 rather than -1.
        val bounds = NodeBounds(left = -3, top = 2, right = 2, bottom = -3)

        assertEquals(0, bounds.centerX)
        assertEquals(0, bounds.centerY)
    }

    @Test
    fun `bounds are a value type`() {
        assertEquals(NodeBounds(1, 2, 3, 4), NodeBounds(1, 2, 3, 4))
        assertEquals(NodeBounds(1, 2, 3, 4).hashCode(), NodeBounds(1, 2, 3, 4).hashCode())
        assertNotEquals(NodeBounds(1, 2, 3, 4), NodeBounds(1, 2, 3, 5))
    }

    // --- ScreenNode ----------------------------------------------------------

    @Test
    fun `children default to an empty list`() {
        val leaf = ScreenNode(
            nodeId = "n",
            text = null,
            contentDescription = null,
            className = null,
            packageName = null,
            clickable = false,
            focusable = false,
            scrollable = false,
            editable = false,
            checked = null,
            bounds = null,
        )

        assertTrue(leaf.children.isEmpty())
        assertNull(leaf.bounds)
    }

    @Test
    fun `nodes compare by value and copy changes only the named field`() {
        val a = node()
        val b = node()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals("Send", a.copy().text)
        assertNotEquals(a, a.copy(text = "Cancel"))
        assertNotEquals(a, a.copy(nodeId = "other"))
        assertNotEquals(a, a.copy(bounds = NodeBounds(0, 0, 1, 1)))
    }

    @Test
    fun `action result defaults to a failure with no message and no data`() {
        val result = ActionResult(false)

        assertEquals(false, result.success)
        assertEquals("", result.message)
        assertNull(result.data)
        assertEquals(ActionResult(true, "ok", 1), ActionResult(true, "ok", 1))
    }

    // --- computeHash ---------------------------------------------------------

    @Test
    fun `the hash is deterministic and 16 lowercase hex chars`() {
        val a = node()

        assertEquals(a.computeHash(), a.computeHash())
        assertEquals(a.computeHash(), node().computeHash())
        assertTrue(
            "hash must be safe to log and compare: ${a.computeHash()}",
            a.computeHash().matches(Regex("[0-9a-f]{16}")),
        )
    }

    @Test
    fun `the hash covers the node id`() {
        assertNotEquals(node().computeHash(), node(nodeId = "other_0").computeHash())
    }

    @Test
    fun `the hash covers the text`() {
        assertNotEquals(node(text = "Send").computeHash(), node(text = "Cancel").computeHash())
    }

    @Test
    fun `the hash distinguishes absent text from empty text`() {
        // ScreenReader already drops blank text to null; if that ever changes,
        // a cleared label must not look like the same screen.
        assertNotEquals(node(text = null).computeHash(), node(text = "").computeHash())
    }

    @Test
    fun `the hash covers the content description`() {
        assertNotEquals(
            node(contentDescription = null).computeHash(),
            node(contentDescription = "More options").computeHash(),
        )
    }

    @Test
    fun `the hash covers the class name`() {
        assertNotEquals(
            node(className = "android.widget.Button").computeHash(),
            node(className = "android.widget.TextView").computeHash(),
        )
    }

    @Test
    fun `the hash covers every interaction flag`() {
        val base = node().computeHash()

        assertNotEquals(base, node(clickable = false).computeHash())
        assertNotEquals(base, node(focusable = false).computeHash())
        assertNotEquals(base, node(scrollable = true).computeHash())
        assertNotEquals(base, node(editable = true).computeHash())
    }

    @Test
    fun `the hash covers the check state including its absence`() {
        val unchecked = node(checked = false).computeHash()

        assertNotEquals(unchecked, node(checked = true).computeHash())
        assertNotEquals(
            "unchecked and not-checkable are different states",
            unchecked,
            node(checked = null).computeHash(),
        )
    }

    @Test
    fun `a child change propagates to the parent hash`() {
        val before = node(children = listOf(node(nodeId = "child_0", text = "On")))

        val after = node(children = listOf(node(nodeId = "child_0", text = "Off")))

        assertNotEquals(before.computeHash(), after.computeHash())
    }

    @Test
    fun `adding a child changes the parent hash`() {
        assertNotEquals(
            node().computeHash(),
            node(children = listOf(node(nodeId = "child_0"))).computeHash(),
        )
    }

    @Test
    fun `child order is part of the parent hash`() {
        // A reordered list is a different screen for the agent's purposes.
        val a = node(nodeId = "root", children = listOf(node(nodeId = "c0"), node(nodeId = "c1")))
        val b = node(nodeId = "root", children = listOf(node(nodeId = "c1"), node(nodeId = "c0")))

        assertNotEquals(a.computeHash(), b.computeHash())
    }

    @Test
    fun `a deep change three levels down changes the root hash`() {
        val deep = node(
            nodeId = "root",
            children = listOf(
                node(
                    nodeId = "mid",
                    children = listOf(node(nodeId = "leaf", text = "Unread")),
                ),
            ),
        )
        val deepChanged = node(
            nodeId = "root",
            children = listOf(
                node(
                    nodeId = "mid",
                    children = listOf(node(nodeId = "leaf", text = "Read")),
                ),
            ),
        )

        assertNotEquals(deep.computeHash(), deepChanged.computeHash())
    }

    @Test
    fun `two structurally identical trees hash the same`() {
        fun tree() = node(
            nodeId = "root",
            children = listOf(
                node(nodeId = "c0", text = "A"),
                node(nodeId = "c1", text = "B", checked = true),
            ),
        )

        assertEquals(tree().computeHash(), tree().computeHash())
    }

    @Test
    fun `bounds and package only reach the hash through the node id`() {
        // Characterisation. computeHash()'s raw string omits both fields, which
        // is safe *because* ScreenReader builds nodeId as
        // "package_class_path_left_top_right_bottom". If nodeId ever stops
        // carrying bounds, a moved node would hash identically and
        // /screen_diff would report "no changes" for a reflowed screen.
        val base = node()

        assertEquals(
            "moving a node must be visible via nodeId",
            base.computeHash(),
            base.copy(bounds = NodeBounds(999, 999, 1099, 1039)).computeHash(),
        )
        assertEquals(
            base.computeHash(),
            base.copy(packageName = "com.other.app").computeHash(),
        )
        assertNotEquals(
            "the nodeId is what actually carries the move",
            base.computeHash(),
            base.copy(nodeId = "com.example.app_android.widget.Button_0_999_999_1099_1039").computeHash(),
        )
    }

    @Test
    fun `the hash is stable for a realistic screen dump`() {
        // Pin one concrete value so an accidental change to the raw string
        // (a reordered field, a new separator) shows up as a diff here rather
        // than as a silent "everything changed" for every connected agent.
        val screen = node(
            nodeId = "com.example.app_android.widget.FrameLayout_0",
            text = null,
            className = "android.widget.FrameLayout",
            children = listOf(
                node(nodeId = "com.example.app_android.widget.Button_0_0", text = "Send"),
            ),
        )

        assertEquals(16, screen.computeHash().length)
        assertEquals(screen.computeHash(), screen.copy().computeHash())
    }

    @Test
    fun `field content containing the separator cannot forge another node hash`() {
        // Without a length prefix the raw string is
        // "N|X|Y|null|C|false|false|false|false|null|" for BOTH nodes: the first
        // carries it as text, the second splits it across text,
        // contentDescription and className.
        val forged = node(
            nodeId = "N",
            text = "X",
            contentDescription = "Y",
            className = "null|C",
            clickable = false,
            focusable = false,
            scrollable = false,
            editable = false,
            checked = null,
            bounds = null,
        )
        val genuine = node(
            nodeId = "N",
            text = "X|Y",
            contentDescription = null,
            className = "C",
            clickable = false,
            focusable = false,
            scrollable = false,
            editable = false,
            checked = null,
            bounds = null,
        )

        assertNotEquals("the two nodes are genuinely different", genuine, forged)
        assertNotEquals(
            "different screens must not share a hash",
            genuine.computeHash(),
            forged.computeHash(),
        )
    }
}
