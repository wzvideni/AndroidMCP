package com.wzvideni.androidmcp.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.content.Context
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.wzvideni.androidmcp.model.RectBounds
import com.wzvideni.androidmcp.model.UiNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("AccessibilityPolicy")
class McpAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "McpA11yService"
        var instance: McpAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null

        fun isPermissionGranted(context: Context): Boolean {
            val pkgName = context.packageName
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val enabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            return enabled == 1 && flat != null && flat.contains(pkgName)
        }

        suspend fun autoGrantPermission(context: Context): Boolean {
            if (isRunning) return true
            val componentName = "${context.packageName}/${McpAccessibilityService::class.java.name}"
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val updated = if (current.isBlank()) componentName else if (!current.contains(componentName)) "$current:$componentName" else current

            if (ShizukuBridge.hasPermission()) {
                ShizukuBridge.exec("settings", "put", "secure", "enabled_accessibility_services", updated)
                ShizukuBridge.exec("settings", "put", "secure", "accessibility_enabled", "1")
                if (isPermissionGranted(context)) return true
            }

            if (RootBridge.isRootAvailable()) {
                RootBridge.exec("settings put secure enabled_accessibility_services \"$updated\"")
                RootBridge.exec("settings put secure accessibility_enabled 1")
                if (isPermissionGranted(context)) return true
            }

            return isRunning || isPermissionGranted(context)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "McpAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        Log.i(TAG, "McpAccessibilityService destroyed")
    }

    @Volatile
    var currentPackageName: String? = null
        private set

    @Volatile
    var currentActivityName: String? = null
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                val cls = event.className?.toString()
                if (!pkg.isNullOrBlank()) {
                    currentPackageName = pkg
                }
                if (!cls.isNullOrBlank() && !cls.startsWith("android.view.") && !cls.startsWith("android.widget.")) {
                    currentActivityName = cls
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "McpAccessibilityService interrupted")
    }

    suspend fun dumpHierarchy(retries: Int = 2): UiNode? {
        for (attempt in 0..retries) {
            val root = rootInActiveWindow
                ?: windows.firstOrNull { it.isFocused }?.root
                ?: windows.firstOrNull { it.isActive }?.root
                ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
                ?: windows.firstOrNull { it.root != null }?.root

            if (root != null) {
                val idCounter = AtomicInteger(1)
                return traverseNode(root, idCounter)
            }
            if (attempt < retries) {
                delay(80)
            }
        }
        return null
    }

    private fun traverseNode(node: AccessibilityNodeInfo, idCounter: AtomicInteger): UiNode {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = RectBounds(rect.left, rect.top, rect.right, rect.bottom)

        val childrenList = mutableListOf<UiNode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                childrenList.add(traverseNode(child, idCounter))
            }
        }

        return UiNode(
            id = idCounter.getAndIncrement(),
            text = node.text?.toString(),
            description = node.contentDescription?.toString(),
            resourceId = node.viewIdResourceName,
            className = node.className?.toString(),
            packageName = node.packageName?.toString(),
            bounds = bounds,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            focused = node.isFocused,
            selected = node.isSelected,
            visible = node.isVisibleToUser,
            children = childrenList
        )
    }

    suspend fun click(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val deferred = CompletableDeferred<Boolean>()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        deferred.await()
    }

    suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300
    ): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val deferred = CompletableDeferred<Boolean>()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        deferred.await()
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 800): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(400))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val deferred = CompletableDeferred<Boolean>()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        deferred.await()
    }

    suspend fun dragAndDrop(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        holdMs: Long = 400,
        dragMs: Long = 500
    ): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, (holdMs + dragMs).coerceAtLeast(400))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val deferred = CompletableDeferred<Boolean>()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        deferred.await()
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun takeScreenshotA11y(): Bitmap? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Bitmap?>()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )
                    val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    deferred.complete(copy)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshotA11y failed with code $errorCode")
                    deferred.complete(null)
                }
            }
        )
        deferred.await()
    }
}
