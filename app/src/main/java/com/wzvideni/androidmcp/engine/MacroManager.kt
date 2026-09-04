package com.wzvideni.androidmcp.engine

import android.content.Context
import android.util.Log
import com.wzvideni.androidmcp.model.jsonConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object MacroManager {

    private const val TAG = "MacroManager"
    private const val MACRO_FILE_NAME = "androidmcp_macros.json"

    private val recordingBuffer = CopyOnWriteArrayList<JsonObject>()
    @Volatile
    var isRecording: Boolean = false
        private set

    fun startRecording() {
        recordingBuffer.clear()
        isRecording = true
        Log.i(TAG, "Macro recording started")
    }

    fun recordAction(action: JsonObject) {
        if (isRecording) {
            recordingBuffer.add(action)
            Log.d(TAG, "Recorded action: ${action["action"]?.jsonPrimitive?.content}")
        }
    }

    fun stopRecording(): List<JsonObject> {
        isRecording = false
        val result = recordingBuffer.toList()
        Log.i(TAG, "Macro recording stopped, captured ${result.size} action(s)")
        return result
    }

    fun getRecordedActions(): List<JsonObject> = recordingBuffer.toList()

    private fun getStorageFile(context: Context): File {
        return File(context.filesDir, MACRO_FILE_NAME)
    }

    @Synchronized
    private fun loadAllMacros(context: Context): MutableMap<String, List<JsonObject>> {
        val file = getStorageFile(context)
        if (!file.exists()) return mutableMapOf()
        return try {
            val text = file.readText()
            val root = jsonConfig.parseToJsonElement(text).jsonObject
            val map = mutableMapOf<String, List<JsonObject>>()
            for ((key, elem) in root) {
                if (elem is JsonArray) {
                    map[key] = elem.mapNotNull { if (it is JsonObject) it else null }
                }
            }
            map
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load macros: ${e.message}")
            mutableMapOf()
        }
    }

    @Synchronized
    private fun saveAllMacros(context: Context, macros: Map<String, List<JsonObject>>) {
        val file = getStorageFile(context)
        try {
            val jsonText = jsonConfig.encodeToString(macros)
            file.writeText(jsonText)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to save macros: ${e.message}")
        }
    }

    fun saveMacro(context: Context, name: String, actions: List<JsonObject>): Boolean {
        if (name.isBlank() || actions.isEmpty()) return false
        val all = loadAllMacros(context)
        all[name] = actions
        saveAllMacros(context, all)
        Log.i(TAG, "Saved macro '$name' with ${actions.size} action(s)")
        return true
    }

    fun getMacro(context: Context, name: String): List<JsonObject>? {
        return loadAllMacros(context)[name]
    }

    fun listMacros(context: Context): Map<String, Int> {
        return loadAllMacros(context).mapValues { it.value.size }
    }

    fun deleteMacro(context: Context, name: String): Boolean {
        val all = loadAllMacros(context)
        val removed = all.remove(name) != null
        if (removed) {
            saveAllMacros(context, all)
            Log.i(TAG, "Deleted macro '$name'")
        }
        return removed
    }

    suspend fun executeActions(
        actions: List<JsonObject>,
        delayBetweenMs: Long = 300L,
        onStep: (suspend (Int, JsonObject, Boolean, String) -> Unit)? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val summary = StringBuilder()
        var allSuccess = true

        for ((index, item) in actions.withIndex()) {
            val type = item["action"]?.jsonPrimitive?.content ?: "tap"
            var success = false
            var message = ""

            when (type.lowercase()) {
                "tap", "click" -> {
                    val x = item["x"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y = item["y"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val res = InputController.click(x, y)
                    success = res.first
                    message = res.second
                }
                "long_press" -> {
                    val x = item["x"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y = item["y"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val dur = item["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 800L
                    val res = InputController.longPress(x, y, dur)
                    success = res.first
                    message = res.second
                }
                "swipe" -> {
                    val x1 = item["x1"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y1 = item["y1"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val x2 = item["x2"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y2 = item["y2"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val dur = item["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L
                    val res = InputController.swipe(x1, y1, x2, y2, dur)
                    success = res.first
                    message = res.second
                }
                "drag_and_drop" -> {
                    val x1 = item["x1"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y1 = item["y1"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val x2 = item["x2"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val y2 = item["y2"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val hold = item["hold_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 400L
                    val dur = item["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 500L
                    val res = InputController.dragAndDrop(x1, y1, x2, y2, hold, dur)
                    success = res.first
                    message = res.second
                }
                "input_text", "type" -> {
                    val text = item["text"]?.jsonPrimitive?.content ?: ""
                    val res = InputController.inputText(text)
                    success = res.first
                    message = res.second
                }
                "press_key", "key" -> {
                    val key = item["key"]?.jsonPrimitive?.content ?: "BACK"
                    val res = InputController.pressKey(key)
                    success = res.first
                    message = res.second
                }
                "wait", "sleep" -> {
                    val waitMs = item["duration_ms"]?.jsonPrimitive?.intOrNull
                        ?: item["ms"]?.jsonPrimitive?.intOrNull ?: 500
                    delay(waitMs.toLong())
                    success = true
                    message = "Waited ${waitMs}ms"
                }
                else -> {
                    success = false
                    message = "Unknown action: $type"
                }
            }

            if (!success) allSuccess = false
            summary.append("[${index + 1}/${actions.size}] $type: ${if (success) "OK" else "FAIL ($message)"}\n")
            onStep?.invoke(index, item, success, message)

            if (index < actions.size - 1 && delayBetweenMs > 0) {
                delay(delayBetweenMs)
            }
        }

        allSuccess to summary.toString().trim()
    }
}
