package com.wzvideni.androidmcp.engine

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private var isHiddenApiBypassed = false

    fun initHiddenApiBypass() {
        if (!isHiddenApiBypassed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
                isHiddenApiBypassed = true
                Log.i(TAG, "Hidden API exemptions added successfully")
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to bypass hidden API: ${e.message}")
            }
        }
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int = 1001) {
        try {
            if (isRunning() && !hasPermission()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to request Shizuku permission: ${e.message}")
        }
    }

    suspend fun exec(vararg command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!isRunning() || !hasPermission()) {
            return@withContext -1 to "Shizuku is not running or permission not granted"
        }
        try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, command, null, null) as Process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            val exitCode = process.waitFor()
            exitCode to output.toString().trim()
        } catch (e: Throwable) {
            -1 to (e.message ?: "Unknown Shizuku exec error")
        }
    }

    suspend fun injectTap(x: Float, y: Float): Boolean {
        // Fast path via Shizuku shell command or InputManager
        val (code, _) = exec("input", "tap", x.toInt().toString(), y.toInt().toString())
        return code == 0
    }

    suspend fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val (code, _) = exec("input", "swipe", x1.toInt().toString(), y1.toInt().toString(), x2.toInt().toString(), y2.toInt().toString(), durationMs.toString())
        return code == 0
    }

    suspend fun injectText(text: String): Boolean {
        val (code, _) = exec("input", "text", text)
        return code == 0
    }

    suspend fun injectKeyEvent(keyCode: Int): Boolean {
        val (code, _) = exec("input", "keyevent", keyCode.toString())
        return code == 0
    }

    suspend fun takeScreenshotRaw(): ByteArray? = withContext(Dispatchers.IO) {
        if (!isRunning() || !hasPermission()) return@withContext null
        try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, arrayOf("screencap", "-p"), null, null) as Process
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            if (bytes.isNotEmpty()) bytes else null
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to capture screenshot via Shizuku: ${e.message}")
            null
        }
    }
}
