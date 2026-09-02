package com.wzvideni.androidmcp.engine

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RootBridge {

    private const val TAG = "RootBridge"
    @Volatile
    private var isRootChecked = false
    @Volatile
    private var isRootGranted = false

    fun isRootCached(): Boolean = isRootGranted

    fun hasSuBinary(): Boolean {
        val paths = System.getenv("PATH")?.split(":") ?: listOf(
            "/system/bin", "/system/xbin", "/sbin", "/vendor/bin"
        )
        return paths.any { dir -> File(dir, "su").exists() }
    }

    suspend fun checkRootAsync(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootChecked) {
            if (!hasSuBinary()) {
                isRootGranted = false
                isRootChecked = true
                return@withContext false
            }
            isRootGranted = try {
                Shell.getShell().isRoot
            } catch (e: Throwable) {
                Log.d(TAG, "Root check exception: ${e.message}")
                false
            }
            isRootChecked = true
        }
        isRootGranted
    }

    fun isRootAvailable(): Boolean {
        if (!isRootChecked) {
            if (!hasSuBinary()) {
                isRootGranted = false
                isRootChecked = true
                return false
            }
        }
        return isRootGranted
    }

    suspend fun exec(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!checkRootAsync()) return@withContext -1 to "Root is not granted"
        try {
            val result = Shell.cmd(command).exec()
            val output = result.out.joinToString("\n")
            val errors = result.err.joinToString("\n")
            val fullOutput = if (errors.isNotBlank()) "$output\nError: $errors" else output
            result.code to fullOutput.trim()
        } catch (e: Throwable) {
            -1 to "Root execution failed: ${e.message}"
        }
    }

    suspend fun takeScreenshotRaw(): ByteArray? = withContext(Dispatchers.IO) {
        if (!checkRootAsync()) return@withContext null
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            if (bytes.isNotEmpty()) bytes else null
        } catch (e: Throwable) {
            Log.e(TAG, "Root screencap failed: ${e.message}")
            null
        }
    }
}
