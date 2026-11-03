package com.wzvideni.androidmcp.engine

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import com.wzvideni.androidmcp.hook.HookClientManager
import com.wzvideni.androidmcp.model.HookIpcRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

object InputController {


    suspend fun click(
        x: Float,
        y: Float,
        targetPackage: String? = null,
        targetId: String? = null,
        viewId: Int? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // 1. Try LSPosed in-process click if target app is hooked
        if (!targetPackage.isNullOrBlank() && (!targetId.isNullOrBlank() || viewId != null)) {
            try {
                val hookResp = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    HookClientManager.sendCommand(
                        targetPackage,
                        HookIpcRequest(
                            action = "CLICK_VIEW",
                            targetPackage = targetPackage,
                            targetId = targetId,
                            viewId = viewId
                        )
                    )
                }
                if (hookResp?.success == true) {
                    return@withContext true to "LSPosed in-process click dispatched: ${hookResp.message}"
                }
            } catch (_: Throwable) {}
        }

        // 2. Try Shizuku fast input
        if (ShizukuBridge.hasPermission()) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(3500L) {
                    ShizukuBridge.injectTap(x, y)
                } ?: false
                if (ok) return@withContext true to "Shizuku tap dispatched at ($x, $y)"
            } catch (_: Throwable) {}
        }

        // 3. Try Root input
        if (RootBridge.isRootAvailable()) {
            try {
                val (code, _) = RootBridge.exec("input tap ${x.toInt()} ${y.toInt()}", timeoutMs = 3500L)
                if (code == 0) return@withContext true to "Root tap dispatched at ($x, $y)"
            } catch (_: Throwable) {}
        }

        // 4. Try Accessibility gesture
        val a11y = McpAccessibilityService.instance
        if (a11y != null) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(3500L) {
                    a11y.click(x, y)
                } ?: false
                if (ok) return@withContext true to "Accessibility gesture click dispatched at ($x, $y)"
            } catch (_: Throwable) {}
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
        val actionTimeout = (durationMs + 3000L).coerceAtLeast(4000L)

        // 1. Try Shizuku
        if (ShizukuBridge.hasPermission()) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(actionTimeout) {
                    ShizukuBridge.injectSwipe(x1, y1, x2, y2, durationMs)
                } ?: false
                if (ok) return@withContext true to "Shizuku swipe ($x1, $y1) -> ($x2, $y2)"
            } catch (_: Throwable) {}
        }

        // 2. Try Root
        if (RootBridge.isRootAvailable()) {
            try {
                val (code, _) = RootBridge.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs", timeoutMs = actionTimeout)
                if (code == 0) return@withContext true to "Root swipe ($x1, $y1) -> ($x2, $y2)"
            } catch (_: Throwable) {}
        }

        // 3. Try Accessibility
        val a11y = McpAccessibilityService.instance
        if (a11y != null) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(actionTimeout) {
                    a11y.swipe(x1, y1, x2, y2, durationMs)
                } ?: false
                if (ok) return@withContext true to "Accessibility swipe ($x1, $y1) -> ($x2, $y2)"
            } catch (_: Throwable) {}
        }

        false to "No available privilege backend to execute swipe"
    }

    suspend fun longPress(
        x: Float,
        y: Float,
        durationMs: Long = 800,
        targetPackage: String? = null,
        targetId: String? = null,
        viewId: Int? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val actionTimeout = (durationMs + 3000L).coerceAtLeast(4000L)

        // 1. Try LSPosed in-process long click if target app is hooked
        if (!targetPackage.isNullOrBlank() && (!targetId.isNullOrBlank() || viewId != null)) {
            try {
                val hookResp = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                    HookClientManager.sendCommand(
                        targetPackage,
                        HookIpcRequest(
                            action = "LONG_CLICK_VIEW",
                            targetPackage = targetPackage,
                            targetId = targetId,
                            viewId = viewId
                        )
                    )
                }
                if (hookResp?.success == true) {
                    return@withContext true to "LSPosed in-process long-click dispatched: ${hookResp.message}"
                }
            } catch (_: Throwable) {}
        }

        // 2. Try Shizuku fast input
        if (ShizukuBridge.hasPermission()) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(actionTimeout) {
                    ShizukuBridge.injectSwipe(x, y, x, y, durationMs)
                } ?: false
                if (ok) return@withContext true to "Shizuku long press dispatched at ($x, $y) for ${durationMs}ms"
            } catch (_: Throwable) {}
        }

        // 3. Try Root input
        if (RootBridge.isRootAvailable()) {
            try {
                val (code, _) = RootBridge.exec("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} $durationMs", timeoutMs = actionTimeout)
                if (code == 0) return@withContext true to "Root long press dispatched at ($x, $y) for ${durationMs}ms"
            } catch (_: Throwable) {}
        }

        // 4. Try Accessibility gesture
        val a11y = McpAccessibilityService.instance
        if (a11y != null) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(actionTimeout) {
                    a11y.longPress(x, y, durationMs)
                } ?: false
                if (ok) return@withContext true to "Accessibility long press dispatched at ($x, $y) for ${durationMs}ms"
            } catch (_: Throwable) {}
        }

        false to "No available privilege backend to execute long press"
    }

    suspend fun dragAndDrop(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        holdMs: Long = 400,
        dragMs: Long = 500
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val totalDuration = holdMs + dragMs
        val actionTimeout = (totalDuration + 3000L).coerceAtLeast(4000L)

        // 1. Try Shizuku
        if (ShizukuBridge.hasPermission()) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(actionTimeout) {
                    ShizukuBridge.injectSwipe(x1, y1, x2, y2, totalDuration)
                } ?: false
                if (ok) return@withContext true to "Shizuku drag ($x1, $y1) -> ($x2, $y2)"
            } catch (_: Throwable) {}
        }

        // 2. Try Root
        if (RootBridge.isRootAvailable()) {
            try {
                val (code, _) = RootBridge.exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $totalDuration", timeoutMs = actionTimeout)
                if (code == 0) return@withContext true to "Root drag ($x1, $y1) -> ($x2, $y2)"
            } catch (_: Throwable) {}
        }

        // 3. Try Accessibility
        val a11y = McpAccessibilityService.instance
        if (a11y != null) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(actionTimeout) {
                    a11y.dragAndDrop(x1, y1, x2, y2, holdMs, dragMs)
                } ?: false
                if (ok) return@withContext true to "Accessibility drag ($x1, $y1) -> ($x2, $y2)"
            } catch (_: Throwable) {}
        }

        false to "No available privilege backend to execute drag and drop"
    }

    suspend fun inputText(text: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        // 1. Try Accessibility text injection (fast & handles unicode/chinese directly)
        val a11y = McpAccessibilityService.instance
        if (a11y != null) {
            try {
                if (a11y.inputText(text)) {
                    return@withContext true to "Accessibility text buffer set: '$text'"
                }
            } catch (_: Throwable) {}
        }

        // 2. Try Shizuku input text
        if (ShizukuBridge.hasPermission()) {
            try {
                val escaped = text.replace(" ", "%s").replace("'", "\\'")
                val ok = kotlinx.coroutines.withTimeoutOrNull(5000L) {
                    ShizukuBridge.injectText(escaped)
                } ?: false
                if (ok) return@withContext true to "Shizuku text typed"
            } catch (_: Throwable) {}
        }

        // 3. Try Root input text
        if (RootBridge.isRootAvailable()) {
            try {
                val escaped = text.replace(" ", "%s").replace("'", "\\'")
                val (code, _) = RootBridge.exec("input text '$escaped'", timeoutMs = 5000L)
                if (code == 0) return@withContext true to "Root text typed"
            } catch (_: Throwable) {}
        }

        false to "No available privilege backend to input text"
    }

    val KEY_CODE_TO_NAME: Map<Int, String> = mapOf(
        KeyEvent.KEYCODE_HOME to "HOME",
        KeyEvent.KEYCODE_BACK to "BACK",
        KeyEvent.KEYCODE_CALL to "CALL",
        KeyEvent.KEYCODE_ENDCALL to "ENDCALL",
        KeyEvent.KEYCODE_VOLUME_UP to "VOLUME_UP",
        KeyEvent.KEYCODE_VOLUME_DOWN to "VOLUME_DOWN",
        KeyEvent.KEYCODE_POWER to "POWER",
        KeyEvent.KEYCODE_CAMERA to "CAMERA",
        KeyEvent.KEYCODE_CLEAR to "CLEAR",
        KeyEvent.KEYCODE_TAB to "TAB",
        KeyEvent.KEYCODE_SPACE to "SPACE",
        KeyEvent.KEYCODE_ENTER to "ENTER",
        KeyEvent.KEYCODE_DEL to "DEL",
        KeyEvent.KEYCODE_GRAVE to "GRAVE",
        KeyEvent.KEYCODE_MINUS to "MINUS",
        KeyEvent.KEYCODE_EQUALS to "EQUALS",
        KeyEvent.KEYCODE_LEFT_BRACKET to "LEFT_BRACKET",
        KeyEvent.KEYCODE_RIGHT_BRACKET to "RIGHT_BRACKET",
        KeyEvent.KEYCODE_BACKSLASH to "BACKSLASH",
        KeyEvent.KEYCODE_SEMICOLON to "SEMICOLON",
        KeyEvent.KEYCODE_APOSTROPHE to "APOSTROPHE",
        KeyEvent.KEYCODE_SLASH to "SLASH",
        KeyEvent.KEYCODE_AT to "AT",
        KeyEvent.KEYCODE_PLUS to "PLUS",
        KeyEvent.KEYCODE_MENU to "MENU",
        KeyEvent.KEYCODE_NOTIFICATION to "NOTIFICATIONS",
        KeyEvent.KEYCODE_SEARCH to "SEARCH",
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "MEDIA_PLAY_PAUSE",
        KeyEvent.KEYCODE_MEDIA_STOP to "MEDIA_STOP",
        KeyEvent.KEYCODE_MEDIA_NEXT to "MEDIA_NEXT",
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to "MEDIA_PREVIOUS",
        KeyEvent.KEYCODE_MEDIA_REWIND to "MEDIA_REWIND",
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to "MEDIA_FAST_FORWARD",
        KeyEvent.KEYCODE_PAGE_UP to "PAGE_UP",
        KeyEvent.KEYCODE_PAGE_DOWN to "PAGE_DOWN",
        KeyEvent.KEYCODE_ESCAPE to "ESCAPE",
        KeyEvent.KEYCODE_FORWARD_DEL to "FORWARD_DEL",
        KeyEvent.KEYCODE_DPAD_UP to "DPAD_UP",
        KeyEvent.KEYCODE_DPAD_DOWN to "DPAD_DOWN",
        KeyEvent.KEYCODE_DPAD_LEFT to "DPAD_LEFT",
        KeyEvent.KEYCODE_DPAD_RIGHT to "DPAD_RIGHT",
        KeyEvent.KEYCODE_DPAD_CENTER to "DPAD_CENTER",
        KeyEvent.KEYCODE_VOLUME_MUTE to "VOLUME_MUTE",
        KeyEvent.KEYCODE_INFO to "INFO",
        KeyEvent.KEYCODE_CHANNEL_UP to "CHANNEL_UP",
        KeyEvent.KEYCODE_CHANNEL_DOWN to "CHANNEL_DOWN",
        KeyEvent.KEYCODE_SETTINGS to "SETTINGS",
        KeyEvent.KEYCODE_SLEEP to "SLEEP",
        KeyEvent.KEYCODE_WAKEUP to "WAKEUP",
        KeyEvent.KEYCODE_APP_SWITCH to "RECENTS",
        // Digits
        KeyEvent.KEYCODE_0 to "0",
        KeyEvent.KEYCODE_1 to "1",
        KeyEvent.KEYCODE_2 to "2",
        KeyEvent.KEYCODE_3 to "3",
        KeyEvent.KEYCODE_4 to "4",
        KeyEvent.KEYCODE_5 to "5",
        KeyEvent.KEYCODE_6 to "6",
        KeyEvent.KEYCODE_7 to "7",
        KeyEvent.KEYCODE_8 to "8",
        KeyEvent.KEYCODE_9 to "9"
    )

    fun resolveKeyName(element: JsonElement?): String {
        if (element == null) return ""
        val content = if (element is JsonPrimitive) element.content else element.toString()
        return resolveKeyName(content)
    }

    fun resolveKeyName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val num = trimmed.toIntOrNull()
        if (num != null) {
            val mapped = KEY_CODE_TO_NAME[num]
            if (mapped != null) return mapped
            return num.toString()
        }
        val upper = trimmed.uppercase()
        val stripped = if (upper.startsWith("KEYCODE_")) upper.removePrefix("KEYCODE_") else upper
        return when (stripped) {
            "APP_SWITCH" -> "RECENTS"
            "NOTIFICATION" -> "NOTIFICATIONS"
            "LOCK_SCREEN" -> "POWER"
            else -> stripped
        }
    }

    suspend fun pressKey(key: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (key.isBlank()) {
            return@withContext false to "Key parameter cannot be empty"
        }

        val targetKey = resolveKeyName(key).ifBlank { key.trim() }
        val (keyCode, globalAction) = parseKey(targetKey)

        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && targetKey.uppercase() != "KEYCODE_UNKNOWN" && targetKey != "0") {
            return@withContext false to "Unknown or unsupported key: '$key'"
        }

        // 1. Try Accessibility global actions for navigation keys
        val a11y = McpAccessibilityService.instance
        if (a11y != null && globalAction != null) {
            try {
                val ok = a11y.performGlobalAction(globalAction)
                if (ok) return@withContext true to "Accessibility global action performed for $targetKey"
            } catch (_: Throwable) {}
        }

        // 2. Try Shizuku fast key injection (< 5ms via binder or 3500ms timeout)
        if (ShizukuBridge.hasPermission()) {
            try {
                val ok = kotlinx.coroutines.withTimeoutOrNull(3500L) {
                    ShizukuBridge.injectKeyEvent(keyCode)
                } ?: false
                if (ok) return@withContext true to "Shizuku keyevent $keyCode ($targetKey)"
            } catch (_: Throwable) {}
        }

        // 3. Try Root key injection (strict 3500ms timeout)
        if (RootBridge.isRootAvailable()) {
            try {
                val (code, _) = RootBridge.exec("input keyevent $keyCode", timeoutMs = 3500L)
                if (code == 0) return@withContext true to "Root keyevent $keyCode ($targetKey)"
            } catch (_: Throwable) {}
        }

        false to "Failed to dispatch key $targetKey (code $keyCode)"
    }

    fun parseKey(key: String): Pair<Int, Int?> {
        val trimmed = key.trim()
        val normalized = if (trimmed.startsWith("KEYCODE_", ignoreCase = true)) {
            trimmed.substring(8).uppercase()
        } else {
            trimmed.uppercase()
        }

        return when (normalized) {
            "BACK" -> KeyEvent.KEYCODE_BACK to AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> KeyEvent.KEYCODE_HOME to AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS", "APP_SWITCH" -> KeyEvent.KEYCODE_APP_SWITCH to AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS", "NOTIFICATION" -> KeyEvent.KEYCODE_NOTIFICATION to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "POWER" -> KeyEvent.KEYCODE_POWER to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "ENTER" -> KeyEvent.KEYCODE_ENTER to null
            "DEL", "DELETE", "BACKSPACE" -> KeyEvent.KEYCODE_DEL to null
            "TAB" -> KeyEvent.KEYCODE_TAB to null
            "VOLUME_UP" -> KeyEvent.KEYCODE_VOLUME_UP to null
            "VOLUME_DOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN to null
            "VOLUME_MUTE" -> KeyEvent.KEYCODE_VOLUME_MUTE to null
            "MENU" -> KeyEvent.KEYCODE_MENU to null
            "SEARCH" -> KeyEvent.KEYCODE_SEARCH to null
            "ESCAPE", "ESC" -> KeyEvent.KEYCODE_ESCAPE to null
            "SPACE" -> KeyEvent.KEYCODE_SPACE to null
            "SLEEP" -> KeyEvent.KEYCODE_SLEEP to null
            "WAKEUP", "WAKE" -> KeyEvent.KEYCODE_WAKEUP to null
            else -> {
                val num = trimmed.toIntOrNull()
                if (num != null) {
                    when (num) {
                        KeyEvent.KEYCODE_BACK -> KeyEvent.KEYCODE_BACK to AccessibilityService.GLOBAL_ACTION_BACK
                        KeyEvent.KEYCODE_HOME -> KeyEvent.KEYCODE_HOME to AccessibilityService.GLOBAL_ACTION_HOME
                        KeyEvent.KEYCODE_APP_SWITCH -> KeyEvent.KEYCODE_APP_SWITCH to AccessibilityService.GLOBAL_ACTION_RECENTS
                        KeyEvent.KEYCODE_NOTIFICATION -> KeyEvent.KEYCODE_NOTIFICATION to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        KeyEvent.KEYCODE_POWER -> KeyEvent.KEYCODE_POWER to AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                        else -> num to null
                    }
                } else {
                    KeyEvent.KEYCODE_UNKNOWN to null
                }
            }
        }
    }
}
