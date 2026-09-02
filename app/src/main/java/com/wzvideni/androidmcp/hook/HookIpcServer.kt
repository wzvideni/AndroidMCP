package com.wzvideni.androidmcp.hook

import android.app.Activity
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.wzvideni.androidmcp.model.HookIpcRequest
import com.wzvideni.androidmcp.model.HookIpcResponse
import com.wzvideni.androidmcp.model.jsonConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs inside the hooked target application process.
 * Listens for commands from AndroidMCP via LocalServerSocket.
 */
object HookIpcServer {

    private const val TAG = "HookIpcServer"
    private var isRunning = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var packageName: String = ""

    fun updateCurrentActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        this.packageName = activity.packageName
        ensureServerStarted(packageName)
    }

    fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
        }
    }

    fun ensureServerStarted(pkgName: String) {
        if (isRunning.compareAndSet(false, true)) {
            this.packageName = pkgName
            CoroutineScope(Dispatchers.IO).launch {
                startServerLoop(pkgName)
            }
        }
    }

    private fun startServerLoop(pkgName: String) {
        val socketName = "androidmcp_hook_$pkgName"
        try {
            serverSocket = LocalServerSocket(socketName)
            Log.i(TAG, "Hook IPC server started on abstract socket: $socketName")

            while (isRunning.get()) {
                val clientSocket = serverSocket?.accept() ?: break
                handleClient(clientSocket)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Hook IPC Server error on $socketName: ${e.message}")
            isRunning.set(false)
        }
    }

    private fun handleClient(socket: LocalSocket) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val writer = PrintWriter(socket.outputStream, true)

                val line = reader.readLine()
                if (line != null) {
                    val request = jsonConfig.decodeFromString<HookIpcRequest>(line)
                    val response = processRequest(request)
                    val jsonResp = jsonConfig.encodeToString(response)
                    writer.println(jsonResp)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error handling client connection: ${e.message}")
            } finally {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun processRequest(request: HookIpcRequest): HookIpcResponse {
        val currentActivity = currentActivityRef?.get()

        return when (request.action) {
            "PING" -> {
                HookIpcResponse(
                    success = true,
                    message = "PONG: App $packageName is active (Activity: ${currentActivity?.javaClass?.name ?: "none"})"
                )
            }
            "GET_ACTIVITY_INFO" -> {
                if (currentActivity == null) {
                    HookIpcResponse(success = false, message = "No active Activity in process $packageName")
                } else {
                    val info = MethodInvoker.inspectObject(currentActivity)
                    val jsonObj = buildJsonObject {
                        info.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    }
                    HookIpcResponse(
                        success = true,
                        message = "Activity: ${currentActivity.javaClass.name}",
                        data = jsonObj
                    )
                }
            }
            "GET_VIEW_TREE" -> {
                if (currentActivity == null) {
                    HookIpcResponse(success = false, message = "No active Activity in process $packageName")
                } else {
                    val tree = ViewTreeExtractor.extractFromActivity(currentActivity)
                    HookIpcResponse(
                        success = true,
                        message = "View tree extracted from ${currentActivity.javaClass.name}",
                        uiNode = tree
                    )
                }
            }
            "CLICK_VIEW" -> {
                if (currentActivity == null) {
                    HookIpcResponse(success = false, message = "No active Activity")
                } else {
                    val clicked = MethodInvoker.clickViewByIdOrTag(
                        currentActivity,
                        request.targetId,
                        request.viewId
                    )
                    HookIpcResponse(
                        success = clicked,
                        message = if (clicked) "Click dispatched successfully" else "Failed to find or click view"
                    )
                }
            }
            "CALL_METHOD" -> {
                if (currentActivity == null && request.className.isNullOrBlank()) {
                    HookIpcResponse(success = false, message = "No active Activity and no ClassName specified")
                } else {
                    val target: Any = if (!request.className.isNullOrBlank()) {
                        try {
                            Class.forName(request.className)
                        } catch (e: Throwable) {
                            return HookIpcResponse(success = false, message = "Class not found: ${e.message}")
                        }
                    } else {
                        currentActivity!!
                    }

                    val methodName = request.methodName ?: return HookIpcResponse(
                        success = false,
                        message = "Missing methodName"
                    )
                    val (ok, res) = MethodInvoker.callMethod(target, methodName, request.params)
                    HookIpcResponse(
                        success = ok,
                        message = res
                    )
                }
            }
            "SET_FIELD" -> {
                if (currentActivity == null && request.className.isNullOrBlank()) {
                    HookIpcResponse(success = false, message = "No active Activity and no ClassName specified")
                } else {
                    val target: Any = if (!request.className.isNullOrBlank()) {
                        try {
                            Class.forName(request.className)
                        } catch (e: Throwable) {
                            return HookIpcResponse(success = false, message = "Class not found: ${e.message}")
                        }
                    } else {
                        currentActivity!!
                    }
                    val fieldName = request.fieldName ?: return HookIpcResponse(
                        success = false,
                        message = "Missing fieldName"
                    )
                    val fieldValue = request.fieldValue ?: return HookIpcResponse(
                        success = false,
                        message = "Missing fieldValue"
                    )
                    val (ok, res) = MethodInvoker.setField(target, fieldName, fieldValue)
                    HookIpcResponse(
                        success = ok,
                        message = res
                    )
                }
            }
            else -> HookIpcResponse(success = false, message = "Unknown action: ${request.action}")
        }
    }
}
