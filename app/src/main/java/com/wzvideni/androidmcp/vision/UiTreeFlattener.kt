package com.wzvideni.androidmcp.vision

import com.wzvideni.androidmcp.model.UiNode

object UiTreeFlattener {

    /**
     * Collects all visible & interactive/meaningful nodes in DFS order.
     */
    fun flattenInteractive(root: UiNode): List<UiNode> {
        val list = mutableListOf<UiNode>()
        walk(root, list)
        return list
    }

    private fun walk(node: UiNode, output: MutableList<UiNode>) {
        if (!node.visible) return

        val isMeaningful = node.clickable ||
                node.editable ||
                node.scrollable ||
                node.checkable ||
                !node.text.isNullOrBlank() ||
                !node.description.isNullOrBlank()

        if (isMeaningful && node.bounds.width > 0 && node.bounds.height > 0) {
            output.add(node)
        }

        for (child in node.children) {
            walk(child, output)
        }
    }

    /**
     * Converts a UI tree to an optimized, compact text summary for LLM prompt context.
     */
    fun toCompactPrompt(root: UiNode, title: String? = null): String {
        val flattened = flattenInteractive(root)
        val sb = StringBuilder()
        if (title != null) {
            sb.append("=== UI Hierarchy: $title ===\n")
        }
        sb.append("Interactive Elements (${flattened.size}):\n")
        for (node in flattened) {
            sb.append(node.toCompactString()).append("\n")
        }
        return sb.toString()
    }

    /**
     * Finds a node by its numeric Set-of-Mark ID
     */
    fun findNodeById(root: UiNode, targetId: Int): UiNode? {
        if (root.id == targetId) return root
        for (child in root.children) {
            val found = findNodeById(child, targetId)
            if (found != null) return found
        }
        return null
    }
}
