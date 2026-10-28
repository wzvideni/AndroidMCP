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
import kotlinx.serialization.json.contentOrNull
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
        resolveElementCoords: ((Int) -> Pair<Float, Float>?)? = null,
        getActivityInfo: (suspend () -> Pair<String?, String?>)? = null,
        onStep: (suspend (Int, JsonObject, Boolean, String) -> Unit)? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val summary = StringBuilder()
        var allSuccess = true

        for ((index, item) in actions.withIndex()) {
            val type = item["action"]?.jsonPrimitive?.content ?: "tap"
            var success = false
            var message = ""
            val beforeAct = try { getActivityInfo?.invoke() } catch (_: Throwable) { null }

            when (type.lowercase()) {
                "tap", "click" -> {
                    var x = item["x"]?.jsonPrimitive?.floatOrNull
                    var y = item["y"]?.jsonPrimitive?.floatOrNull
                    val elementId = item["element_id"]?.jsonPrimitive?.intOrNull
                    if ((x == null || y == null) && elementId != null && resolveElementCoords != null) {
                        val coords = resolveElementCoords(elementId)
                        if (coords != null) {
                            x = coords.first
                            y = coords.second
                        }
                    }
                    val finalX = x ?: 0f
                    val finalY = y ?: 0f
                    val targetPkg = item["target_package"]?.jsonPrimitive?.contentOrNull
                    val targetId = item["target_id"]?.jsonPrimitive?.contentOrNull ?: item["resource_id"]?.jsonPrimitive?.contentOrNull
                    val res = InputController.click(finalX, finalY, targetPkg, targetId)
                    success = res.first
                    message = if (res.first) "tap at ($finalX, $finalY)" else res.second
                }
                "long_press" -> {
                    var x = item["x"]?.jsonPrimitive?.floatOrNull
                    var y = item["y"]?.jsonPrimitive?.floatOrNull
                    val elementId = item["element_id"]?.jsonPrimitive?.intOrNull
                    if ((x == null || y == null) && elementId != null && resolveElementCoords != null) {
                        val coords = resolveElementCoords(elementId)
                        if (coords != null) {
                            x = coords.first
                            y = coords.second
                        }
                    }
                    val finalX = x ?: 0f
                    val finalY = y ?: 0f
                    val dur = item["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 800L
                    val targetPkg = item["target_package"]?.jsonPrimitive?.contentOrNull
                    val targetId = item["target_id"]?.jsonPrimitive?.contentOrNull ?: item["resource_id"]?.jsonPrimitive?.contentOrNull
                    val res = InputController.longPress(finalX, finalY, dur, targetPkg, targetId)
                    success = res.first
                    message = if (res.first) "long press at ($finalX, $finalY) for ${dur}ms" else res.second
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

            if (index < actions.size - 1 && delayBetweenMs > 0) {
                delay(delayBetweenMs)
            }

            val afterAct = try { getActivityInfo?.invoke() } catch (_: Throwable) { null }
            val actTransition = if (beforeAct != null && afterAct != null) {
                if (beforeAct.second != afterAct.second || beforeAct.first != afterAct.first) {
                    " [Activity: ${beforeAct.first ?: "?"}/${beforeAct.second ?: "?"} -> ${afterAct.first ?: "?"}/${afterAct.second ?: "?"}]"
                } else {
                    " [Activity: ${afterAct.first ?: "?"}/${afterAct.second ?: "?"}]"
                }
            } else ""

            if (!success) allSuccess = false
            summary.append("[${index + 1}/${actions.size}] $type: ${if (success) "OK" else "FAIL ($message)"}$actTransition\n")
            onStep?.invoke(index, item, success, message)
        }

        allSuccess to summary.toString().trim()
    }
}
