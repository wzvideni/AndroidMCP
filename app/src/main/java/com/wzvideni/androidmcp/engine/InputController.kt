package com.wzvideni.androidmcp.engine

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import com.wzvideni.androidmcp.hook.HookClientManager
import com.wzvideni.androidmcp.model.HookIpcRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InputController {

    private const val TAG = "InputController"

    suspend fun click(
        x: Float,
        y: Float,
        targetPackage: String? = null,
        targetId: String? = null,
        viewId: Int? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // 1. Try LSPosed in-process click if target app is hooked
        if (!targetPackage.isNullOrBlank() && (!targetId.isNullOrBlank() || viewId != null)) {
            val hookResp = HookClientManager.sendCommand(
                targetPackage,
                HookIpcRequest(
                    action = "CLICK_VIEW",
                    targetPackage = targetPackage,
                    targetId = targetId,
                    viewId = viewId
                )
            )
            if (hookResp.success) {
                return@withContext true to "LSPosed in-process click dispatched: ${hookResp.message}"
            }
        }

        // 2. Try Shizuku fast input
        if (ShizukuBridge.hasPermission()) {
            val ok = ShizukuBridge.injectTap(x, y)
            if (ok) return@withContext true to "Shizuku tap dispatched at ($x, $y)"
        }

        // 3. Try Root input
        if (RootBridge.isRootAvailable()) {
            val (code, out) = RootBridge.exec("input tap ${x.toInt()} ${y.toInt()}")
            if (code == 0) return@withContext true to "Root tap dispatched at ($x, $y)"
        }

        // 4. Try Accessibility gesture
        val a11y = McpAccessibilityService.instance
        if (a11y != null) {
            val ok = a11y.click(x, y)
            if (ok) return@withContext true to "Accessibility gesture click dispatched at ($x, $y)"
        }

        false to "No available privilege backend (Shizuku, Root, or Accessibility) to execute click"
    }

    suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // 1. Try Shizuku
        if (ShizukuBridge.hasPermission()) {
            val ok = ShizukuBridge.injectSwipe(x1, y1, x2, y2, durationMs)
            if (ok) return@withContext true to "Shizuku swipe ($x1, $y1) -> ($x2, $y2)"
        }

        // 2. Try Root
        if (RootBridge.isRootAvailable()) {
            val (code, _) = RootBridge.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
            if (code == 0) return@withContext true to "Root swipe ($x1, $y1) -> ($x2, $y2)"
        }

        // 3. Try Accessibility
        val a11y = McpAccessibilityService.instance
        if (a11y != null) {
            val ok = a11y.swipe(x1, y1, x2, y2, durationMs)
            if (ok) return@withContext true to "Accessibility swipe ($x1, $y1) -> ($x2, $y2)"
        }

        false to "No available privilege backend to execute swipe"
    }

    suspend fun inputText(text: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // 1. Try Accessibility text injection (fast & handles unicode/chinese directly)
        val a11y = McpAccessibilityService.instance
        if (a11y != null && a11y.inputText(text)) {
            return@withContext true to "Accessibility text buffer set: '$text'"
        }

        // 2. Try Shizuku input text
        if (ShizukuBridge.hasPermission()) {
            val escaped = text.replace(" ", "%s").replace("'", "\\'")
            val ok = ShizukuBridge.injectText(escaped)
            if (ok) return@withContext true to "Shizuku text typed"
        }

        // 3. Try Root input text
        if (RootBridge.isRootAvailable()) {
            val escaped = text.replace(" ", "%s").replace("'", "\\'")
            val (code, _) = RootBridge.exec("input text '$escaped'")
            if (code == 0) return@withContext true to "Root text typed"
        }

        false to "No available privilege backend to input text"
    }

    suspend fun pressKey(key: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val (keyCode, globalAction) = parseKey(key)

        // Try Accessibility global actions for navigation keys
        val a11y = McpAccessibilityService.instance
        if (a11y != null && globalAction != null) {
            val ok = a11y.performGlobalAction(globalAction)
            if (ok) return@withContext true to "Accessibility global action performed for $key"
        }

        // Try Shizuku key injection
        if (ShizukuBridge.hasPermission()) {
            val ok = ShizukuBridge.injectKeyEvent(keyCode)
            if (ok) return@withContext true to "Shizuku keyevent $keyCode ($key)"
        }

        // Try Root key injection
        if (RootBridge.isRootAvailable()) {
            val (code, _) = RootBridge.exec("input keyevent $keyCode")
            if (code == 0) return@withContext true to "Root keyevent $keyCode ($key)"
        }

        false to "Failed to dispatch key $key"
    }

    private fun parseKey(key: String): Pair<Int, Int?> {
        return when (key.uppercase()) {
            "BACK" -> KeyEvent.KEYCODE_BACK to AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> KeyEvent.KEYCODE_HOME to AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS", "APP_SWITCH" -> KeyEvent.KEYCODE_APP_SWITCH to AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> KeyEvent.KEYCODE_NOTIFICATION to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "POWER" -> KeyEvent.KEYCODE_POWER to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "ENTER" -> KeyEvent.KEYCODE_ENTER to null
            "DEL", "DELETE", "BACKSPACE" -> KeyEvent.KEYCODE_DEL to null
            "TAB" -> KeyEvent.KEYCODE_TAB to null
            "VOLUME_UP" -> KeyEvent.KEYCODE_VOLUME_UP to null
            "VOLUME_DOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN to null
            "VOLUME_MUTE" -> KeyEvent.KEYCODE_VOLUME_MUTE to null
            else -> {
                val num = key.toIntOrNull()
                (num ?: KeyEvent.KEYCODE_UNKNOWN) to null
            }
        }
    }
}
