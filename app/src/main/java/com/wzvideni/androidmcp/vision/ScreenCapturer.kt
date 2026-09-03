package com.wzvideni.androidmcp.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import com.wzvideni.androidmcp.engine.McpAccessibilityService
import com.wzvideni.androidmcp.engine.RootBridge
import com.wzvideni.androidmcp.engine.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object ScreenCapturer {

    private const val TAG = "ScreenCapturer"

    suspend fun captureBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        // Wake up screen if in sleep/ambient state
        try {
            if (RootBridge.isRootAvailable()) {
                RootBridge.exec("input keyevent 224")
            } else if (ShizukuBridge.hasPermission()) {
                ShizukuBridge.exec("input", "keyevent", "224")
            }
        } catch (_: Throwable) {
        }

        // 1. Try Root screencap
        if (RootBridge.isRootAvailable()) {
            val raw = RootBridge.takeScreenshotRaw()
            if (raw != null) {
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bmp != null) return@withContext bmp
            }
        }

        // 2. Try Shizuku screencap (In-Memory byte stream, bypasses SELinux file restrictions)
        if (ShizukuBridge.hasPermission()) {
            val raw = ShizukuBridge.takeScreenshotRaw()
            if (raw != null) {
                val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (bmp != null) return@withContext bmp
            }
        }

        // 3. Try Accessibility takeScreenshot (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val a11y = McpAccessibilityService.instance
            if (a11y != null) {
                val bmp = a11y.takeScreenshotA11y()
                if (bmp != null) return@withContext bmp
            }
        }

        null
    }

    fun toBase64Jpeg(bitmap: Bitmap, quality: Int = 80): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun toByteArrayJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), out)
        return out.toByteArray()
    }
}
