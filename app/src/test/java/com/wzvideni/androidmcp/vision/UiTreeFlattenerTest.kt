package com.wzvideni.androidmcp.vision

import com.wzvideni.androidmcp.model.RectBounds
import com.wzvideni.androidmcp.model.UiNode
import org.junit.Assert.*
import org.junit.Test

class UiTreeFlattenerTest {

    @Test
    fun testFlattenInteractive() {
        val child1 = UiNode(
            id = 1,
            text = "Click Me",
            clickable = true,
            bounds = RectBounds(10, 10, 100, 50)
        )
        val child2 = UiNode(
            id = 2,
            text = null,
            clickable = false,
            bounds = RectBounds(0, 0, 0, 0)
        )
        val child3 = UiNode(
            id = 3,
            description = "Search Icon",
            clickable = true,
            bounds = RectBounds(100, 10, 150, 50)
        )
        val root = UiNode(
            id = 0,
            className = "android.widget.FrameLayout",
            bounds = RectBounds(0, 0, 1080, 1920),
            children = listOf(child1, child2, child3)
        )

        val flattened = UiTreeFlattener.flattenInteractive(root)
        assertEquals(2, flattened.size)
        assertEquals(1, flattened[0].id)
        assertEquals("Click Me", flattened[0].text)
        assertEquals(3, flattened[1].id)
        assertEquals("Search Icon", flattened[1].description)
    }

    @Test
    fun testFindNodeById() {
        val target = UiNode(
            id = 42,
            text = "Target Found",
            clickable = true,
            bounds = RectBounds(50, 50, 200, 100)
        )
        val root = UiNode(
            id = 0,
            bounds = RectBounds(0, 0, 1080, 1920),
            children = listOf(
                UiNode(
                    id = 1,
                    bounds = RectBounds(0, 0, 500, 500),
                    children = listOf(target)
                )
            )
        )

        val found = UiTreeFlattener.findNodeById(root, 42)
        assertNotNull(found)
        assertEquals("Target Found", found?.text)

        val notFound = UiTreeFlattener.findNodeById(root, 999)
        assertNull(notFound)
    }

    @Test
    fun testToCompactPrompt() {
        val root = UiNode(
            id = 1,
            className = "android.widget.Button",
            text = "Submit",
            clickable = true,
            bounds = RectBounds(10, 20, 110, 70)
        )
        val prompt = UiTreeFlattener.toCompactPrompt(root, "Test Screen")
        assertTrue(prompt.contains("=== UI Hierarchy: Test Screen ==="))
        assertTrue(prompt.contains("Interactive Elements (1):"))
        assertTrue(prompt.contains("[1] Button \"Submit\" [clickable] [10,20][110,70]"))
    }
}
