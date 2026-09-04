package com.wzvideni.androidmcp.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

@Serializable
data class TraceRecord(
    val timestamp: Long,
    val className: String,
    val methodName: String,
    val args: List<String>,
    val result: String,
    val durationMs: Long
)

object MethodTraceManager {

    private const val TAG = "MethodTraceManager"
    private const val MAX_RECORDS = 50

    private val traces = ConcurrentLinkedDeque<TraceRecord>()
    private val hookedMethods = ConcurrentHashMap.newKeySet<String>()

    fun recordTrace(record: TraceRecord) {
        traces.addFirst(record)
        while (traces.size > MAX_RECORDS) {
            traces.pollLast()
        }
    }

    fun getTraces(className: String? = null, methodName: String? = null): List<TraceRecord> {
        return traces.filter { rec ->
            (className == null || rec.className.contains(className, ignoreCase = true)) &&
            (methodName == null || rec.methodName.equals(methodName, ignoreCase = true))
        }
    }

    fun clearTraces() {
        traces.clear()
    }

    fun registerTrace(clazz: Class<*>, methodName: String): Pair<Boolean, String> {
        val key = "${clazz.name}#$methodName"
        if (hookedMethods.contains(key)) {
            return true to "Method $key is already being traced"
        }

        val allMethods = mutableListOf<java.lang.reflect.Method>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            allMethods.addAll(c.declaredMethods)
            c = c.superclass
        }
        val methods = allMethods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            val avail = allMethods.map { it.name }.distinct().take(12).joinToString(", ")
            return false to "No method named '$methodName' found in ${clazz.name}. Available methods: $avail"
        }

        var hookCount = 0
        for (m in methods) {
            try {
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.setObjectExtra("mcp_trace_start", System.currentTimeMillis())
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val start = (param.getObjectExtra("mcp_trace_start") as? Long) ?: System.currentTimeMillis()
                        val duration = System.currentTimeMillis() - start
                        val argsList = param.args?.map { it?.toString() ?: "null" } ?: emptyList()
                        val resStr = if (param.hasThrowable()) {
                            "Exception: ${param.throwable?.message}"
                        } else {
                            param.result?.toString() ?: "void/null"
                        }
                        recordTrace(
                            TraceRecord(
                                timestamp = System.currentTimeMillis(),
                                className = clazz.name,
                                methodName = m.name,
                                args = argsList,
                                result = resStr,
                                durationMs = duration
                            )
                        )
                    }
                })
                hookCount++
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to hook method ${m.name}: ${e.message}")
            }
        }

        return if (hookCount > 0) {
            hookedMethods.add(key)
            true to "Successfully registered trace for $hookCount overload(s) of $key"
        } else {
            false to "Failed to hook any method overload for $key"
        }
    }
}
