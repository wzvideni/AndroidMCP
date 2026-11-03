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
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private var isHiddenApiBypassed = false

    @Volatile
    private var inputManagerBinder: IBinder? = null
    @Volatile
    private var injectInputEventMethod: Method? = null
    @Volatile
    private var iInputManager: Any? = null
    @Volatile
    private var inputManagerInitAttempted = false

    private fun initInputManager(): Boolean {
        if (iInputManager != null && injectInputEventMethod != null) return true
        if (inputManagerInitAttempted && iInputManager == null) return false
        inputManagerInitAttempted = true
        return try {
            val binder = SystemServiceHelper.getSystemService("input") ?: return false
            inputManagerBinder = ShizukuBinderWrapper(binder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            iInputManager = asInterface.invoke(null, inputManagerBinder)
            val iInputManagerClass = Class.forName("android.hardware.input.IInputManager")
            injectInputEventMethod = iInputManagerClass.methods.firstOrNull {
                it.name == "injectInputEvent" && it.parameterTypes.isNotEmpty() &&
                    InputEvent::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            injectInputEventMethod?.isAccessible = true
            iInputManager != null && injectInputEventMethod != null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to init Shizuku IInputManager binder: ${e.message}")
            false
        }
    }

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

    suspend fun exec(vararg command: String): Pair<Int, String> = execWithTimeout(15000L, *command)

    suspend fun execWithTimeout(timeoutMs: Long, vararg command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
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

            // Close stdin immediately to prevent processes (like cmd package / pm) from stalling
            try {
                process.outputStream?.close()
            } catch (_: Throwable) {}

            val result = withTimeoutOrNull(timeoutMs) {
                val outDeferred = async(Dispatchers.IO) {
                    try {
                        process.inputStream.bufferedReader().use { it.readText() }
                    } catch (_: Throwable) {
                        ""
                    }
                }
                val errDeferred = async(Dispatchers.IO) {
                    try {
                        process.errorStream.bufferedReader().use { it.readText() }
                    } catch (_: Throwable) {
                        ""
                    }
                }
                val waitDeferred = async(Dispatchers.IO) {
                    try {
                        process.waitFor()
                    } catch (_: Throwable) {
                        -1
                    }
                }
                val out = outDeferred.await()
                val err = errDeferred.await()
                val exitCode = waitDeferred.await()
                val full = if (err.isNotBlank()) "$out\n$err".trim() else out.trim()
                exitCode to full
            }

            if (result != null) {
                result
            } else {
                try {
                    process.destroy()
                } catch (_: Throwable) {}
                try {
                    process.inputStream?.close()
                } catch (_: Throwable) {}
                try {
                    process.errorStream?.close()
                } catch (_: Throwable) {}
                -1 to "Shizuku command timed out after ${timeoutMs}ms: ${command.joinToString(" ")}"
            }
        } catch (e: Throwable) {
            -1 to (e.message ?: "Unknown Shizuku exec error")
        }
    }

    suspend fun injectTap(x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        if (!isRunning() || !hasPermission()) return@withContext false
        // 1. Try fast IInputManager Binder injection (< 5ms)
        try {
            if (initInputManager()) {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                val argsDown = if (injectInputEventMethod!!.parameterTypes.size == 2) arrayOf(down, 0) else arrayOf(down, 0, 0)
                val argsUp = if (injectInputEventMethod!!.parameterTypes.size == 2) arrayOf(up, 0) else arrayOf(up, 0, 0)
                val okDown = injectInputEventMethod!!.invoke(iInputManager, *argsDown) as? Boolean ?: true
                val okUp = injectInputEventMethod!!.invoke(iInputManager, *argsUp) as? Boolean ?: true
                down.recycle()
                up.recycle()
                if (okDown && okUp) return@withContext true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Fast tap binder injection failed: ${e.message}")
        }

        // 2. Fallback to input shell command with strict 3500ms timeout
        val (code, _) = execWithTimeout(3500L, "input", "tap", x.toInt().toString(), y.toInt().toString())
        code == 0
    }

    suspend fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val timeout = (durationMs + 3000L).coerceAtLeast(4000L)
        val (code, _) = execWithTimeout(timeout, "input", "swipe", x1.toInt().toString(), y1.toInt().toString(), x2.toInt().toString(), y2.toInt().toString(), durationMs.toString())
        return code == 0
    }

    suspend fun injectText(text: String): Boolean {
        val (code, _) = execWithTimeout(5000L, "input", "text", text)
        return code == 0
    }

    suspend fun injectKeyEvent(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isRunning() || !hasPermission()) return@withContext false
        // 1. Try fast IInputManager Binder injection (< 5ms)
        try {
            if (initInputManager()) {
                val now = SystemClock.uptimeMillis()
                val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
                val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
                val argsDown = if (injectInputEventMethod!!.parameterTypes.size == 2) arrayOf(down, 0) else arrayOf(down, 0, 0)
                val argsUp = if (injectInputEventMethod!!.parameterTypes.size == 2) arrayOf(up, 0) else arrayOf(up, 0, 0)
                val okDown = injectInputEventMethod!!.invoke(iInputManager, *argsDown) as? Boolean ?: true
                val okUp = injectInputEventMethod!!.invoke(iInputManager, *argsUp) as? Boolean ?: true
                if (okDown && okUp) return@withContext true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Fast keyevent binder injection failed: ${e.message}")
        }

        // 2. Fallback to input shell command with strict 3500ms timeout
        val (code, _) = execWithTimeout(3500L, "input", "keyevent", keyCode.toString())
        code == 0
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
            withTimeoutOrNull(5000L) {
                val bytes = process.inputStream.readBytes()
                process.waitFor()
                if (bytes.isNotEmpty()) bytes else null
            } ?: run {
                try { process.destroy() } catch (_: Throwable) {}
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to capture screenshot via Shizuku: ${e.message}")
            null
        }
    }
}
