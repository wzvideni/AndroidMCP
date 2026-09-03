package com.wzvideni.androidmcp.hook

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.EditText
import android.widget.TextView
import com.wzvideni.androidmcp.model.RectBounds
import com.wzvideni.androidmcp.model.UiNode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extracts in-memory View hierarchy and Jetpack Compose semantics tree directly inside the target process.
 */
object ViewTreeExtractor {

    fun extractFromActivity(activity: Activity): UiNode {
        val decorView = activity.window?.decorView ?: return UiNode(
            id = 0,
            className = "EmptyDecorView",
            packageName = activity.packageName
        )
        val idCounter = AtomicInteger(1)
        return extractViewNode(activity.packageName, decorView, idCounter)
    }

    fun extractFromProcess(packageName: String): UiNode {
        val idCounter = AtomicInteger(1)
        val rootChildren = mutableListOf<UiNode>()
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstanceMethod = wmgClass.getMethod("getInstance")
            val wmg = getInstanceMethod.invoke(null)
            val mViewsField = wmgClass.getDeclaredField("mViews")
            mViewsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val views = (mViewsField.get(wmg) as? ArrayList<View>) ?: emptyList()
            for (v in views) {
                if (v.visibility == View.VISIBLE) {
                    rootChildren.add(extractViewNode(packageName, v, idCounter))
                }
            }
        } catch (_: Throwable) {
        }

        return UiNode(
            id = 0,
            className = "ProcessWindowRoot",
            packageName = packageName,
            children = rootChildren
        )
    }

    private fun extractViewNode(
        packageName: String,
        view: View,
        idCounter: AtomicInteger
    ): UiNode {
        val loc = IntArray(2)
        try {
            view.getLocationOnScreen(loc)
        } catch (_: Throwable) {
        }
        val bounds = if (view.width > 0 && view.height > 0) {
            RectBounds(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        } else {
            val rect = Rect()
            view.getGlobalVisibleRect(rect)
            RectBounds(rect.left, rect.top, rect.right, rect.bottom)
        }

        val resIdName: String? = if (view.id != View.NO_ID && view.id > 0) {
            try {
                view.resources.getResourceName(view.id)
            } catch (_: Throwable) {
                view.id.toString()
            }
        } else null

        var text: String? = null
        var isEditable = false
        if (view is TextView) {
            text = view.text?.toString()
            isEditable = view is EditText
        }

        val desc = view.contentDescription?.toString()
        val isChecked = (view as? Checkable)?.isChecked ?: false

        // Check if this is an AndroidComposeView
        val className = view.javaClass.name
        val extras = mutableMapOf<String, String>()
        if (view.tag != null) {
            extras["tag"] = view.tag.toString()
        }

        val children = mutableListOf<UiNode>()

        if (className.contains("AndroidComposeView")) {
            // Compose hierarchy extraction via reflection
            try {
                val composeChildren = extractComposeSemantics(view, idCounter)
                children.addAll(composeChildren)
            } catch (e: Throwable) {
                extras["compose_error"] = e.message ?: "error"
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child != null && child.visibility == View.VISIBLE) {
                    children.add(extractViewNode(packageName, child, idCounter))
                }
            }
        }

        return UiNode(
            id = idCounter.getAndIncrement(),
            text = text,
            description = desc,
            resourceId = resIdName,
            className = className,
            packageName = packageName,
            bounds = bounds,
            clickable = view.isClickable,
            scrollable = view.isScrollContainer || view.canScrollVertically(1) || view.canScrollVertically(-1),
            editable = isEditable,
            checkable = view is Checkable,
            checked = isChecked,
            enabled = view.isEnabled,
            focused = view.isFocused,
            selected = view.isSelected,
            visible = view.visibility == View.VISIBLE,
            children = children,
            extras = if (extras.isNotEmpty()) extras else null
        )
    }

    /**
     * Extracts Compose semantics tree using reflection on AndroidComposeView
     */
    private fun extractComposeSemantics(composeView: View, idCounter: AtomicInteger): List<UiNode> {
        val result = mutableListOf<UiNode>()
        try {
            val semanticsOwnerField = composeView.javaClass.getDeclaredField("semanticsOwner")
            semanticsOwnerField.isAccessible = true
            val semanticsOwner = semanticsOwnerField.get(composeView) ?: return emptyList()

            val rootNodeMethod = semanticsOwner.javaClass.getMethod("getUnmergedRootSemanticsNode")
            val rootNode = rootNodeMethod.invoke(semanticsOwner) ?: return emptyList()

            walkComposeNode(rootNode, composeView, idCounter, result)
        } catch (_: Throwable) {
            // Fallback if internal API signature changes
        }
        return result
    }

    private fun walkComposeNode(
        node: Any,
        composeView: View,
        idCounter: AtomicInteger,
        output: MutableList<UiNode>
    ) {
        try {
            val nodeClass = node.javaClass
            val boundsMethod = nodeClass.getMethod("getBoundsInRoot")
            val boundsObj = boundsMethod.invoke(node) // Rect or androidx.compose.ui.geometry.Rect

            val loc = IntArray(2)
            composeView.getLocationOnScreen(loc)

            var left = loc[0]
            var top = loc[1]
            var right = loc[0]
            var bottom = loc[1]

            if (boundsObj != null) {
                val boundsClass = boundsObj.javaClass
                val bLeft = boundsClass.getMethod("getLeft").invoke(boundsObj) as? Float ?: 0f
                val bTop = boundsClass.getMethod("getTop").invoke(boundsObj) as? Float ?: 0f
                val bRight = boundsClass.getMethod("getRight").invoke(boundsObj) as? Float ?: 0f
                val bBottom = boundsClass.getMethod("getBottom").invoke(boundsObj) as? Float ?: 0f

                left += bLeft.toInt()
                top += bTop.toInt()
                right += bRight.toInt()
                bottom += bBottom.toInt()
            }

            // Get config / semantics properties
            val configMethod = nodeClass.getMethod("getConfig")
            val config = configMethod.invoke(node)

            var text: String? = null
            var desc: String? = null
            var testTag: String? = null
            var isClickable = false

            if (config != null) {
                val str = config.toString()
                if (str.contains("Text")) {
                    text = extractPropString(str, "Text")
                }
                if (str.contains("ContentDescription")) {
                    desc = extractPropString(str, "ContentDescription")
                }
                if (str.contains("TestTag")) {
                    testTag = extractPropString(str, "TestTag")
                }
                if (str.contains("OnClick")) {
                    isClickable = true
                }
            }

            val childListMethod = nodeClass.getMethod("getChildren")
            val childrenObj = childListMethod.invoke(node) as? List<*>

            val childNodes = mutableListOf<UiNode>()
            if (childrenObj != null) {
                for (child in childrenObj) {
                    if (child != null) {
                        walkComposeNode(child, composeView, idCounter, childNodes)
                    }
                }
            }

            output.add(
                UiNode(
                    id = idCounter.getAndIncrement(),
                    text = text,
                    description = desc,
                    resourceId = testTag,
                    className = "ComposeNode",
                    packageName = composeView.context.packageName,
                    bounds = RectBounds(left, top, right, bottom),
                    clickable = isClickable,
                    scrollable = false,
                    editable = false,
                    enabled = true,
                    visible = true,
                    children = childNodes,
                    extras = if (testTag != null) mapOf("testTag" to testTag) else null
                )
            )
        } catch (_: Throwable) {
        }
    }

    private fun extractPropString(configStr: String, key: String): String? {
        val idx = configStr.indexOf(key)
        if (idx < 0) return null
        val sub = configStr.substring(idx)
        val eqIdx = sub.indexOf("=")
        val commaIdx = sub.indexOf(",")
        return if (eqIdx in 0 until commaIdx) {
            sub.substring(eqIdx + 1, commaIdx).trim('[', ']', ' ')
        } else null
    }
}
