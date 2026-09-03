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
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs inside the hooked target application process.
 * Listens for commands from AndroidMCP via Loopback TCP (127.0.0.1) and LocalServerSocket.
 */
object HookIpcServer {

    private const val TAG = "HookIpcServer"
    private const val BASE_PORT = 19500
    private var isRunning = AtomicBoolean(false)
    private var tcpServerSocket: ServerSocket? = null
    private var localServerSocket: LocalServerSocket? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var packageName: String = ""

    fun getPortForPackage(pkg: String): Int {
        val hash = (pkg.hashCode().toLong() and 0x7FFFFFFF) % 1000
        return BASE_PORT + hash.toInt()
    }

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
        val port = getPortForPackage(pkgName)
        val socketName = "androidmcp_hook_$pkgName"

        // 1. Loopback TCP Listener (Completely bypasses SELinux cross-domain socket restrictions)
        try {
            tcpServerSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            Log.i(TAG, "Hook IPC TCP server started on 127.0.0.1:$port for $pkgName")
            CoroutineScope(Dispatchers.IO).launch {
                while (isRunning.get()) {
                    try {
                        val client = tcpServerSocket?.accept() ?: break
                        handleTcpClient(client)
                    } catch (_: Throwable) {
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind TCP port $port for $pkgName: ${e.message}")
        }

        // 2. Abstract LocalServerSocket Listener
        try {
            localServerSocket = LocalServerSocket(socketName)
            Log.i(TAG, "Hook IPC LocalServerSocket started on abstract: $socketName")

            while (isRunning.get()) {
                val clientSocket = localServerSocket?.accept() ?: break
                handleLocalClient(clientSocket)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Hook IPC LocalServerSocket error on $socketName: ${e.message}")
        }
    }

    private fun handleTcpClient(socket: Socket) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                socket.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)

                val line = reader.readLine()
                if (line != null) {
                    val request = jsonConfig.decodeFromString<HookIpcRequest>(line)
                    val response = processRequest(request)
                    val jsonResp = jsonConfig.encodeToString(response)
                    writer.println(jsonResp)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error handling TCP client connection: ${e.message}")
            } finally {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun handleLocalClient(socket: LocalSocket) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8), true)

                val line = reader.readLine()
                if (line != null) {
                    val request = jsonConfig.decodeFromString<HookIpcRequest>(line)
                    val response = processRequest(request)
                    val jsonResp = jsonConfig.encodeToString(response)
                    writer.println(jsonResp)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error handling LocalSocket client connection: ${e.message}")
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
                val targetObj: Any? = if (!request.className.isNullOrBlank()) {
                    val clazz = MethodInvoker.loadClass(request.className, currentActivity?.classLoader)
                        ?: return HookIpcResponse(success = false, message = "Class not found: ${request.className}")
                    MethodInvoker.resolveInstance(clazz, currentActivity)
                } else {
                    currentActivity ?: try {
                        val atClass = Class.forName("android.app.ActivityThread")
                        atClass.getMethod("currentApplication").invoke(null)
                    } catch (_: Throwable) {
                        null
                    }
                }

                if (targetObj == null) {
                    val jsonObj = buildJsonObject {
                        put("process", JsonPrimitive(packageName))
                        put("type", JsonPrimitive("Service/Framework Process"))
                        put("hasActivity", JsonPrimitive(false))
                    }
                    HookIpcResponse(
                        success = true,
                        message = "Process $packageName is active (Service/Framework Mode, no foreground Activity)",
                        data = jsonObj
                    )
                } else {
                    val info = MethodInvoker.inspectObject(targetObj)
                    val jsonObj = buildJsonObject {
                        info.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    }
                    HookIpcResponse(
                        success = true,
                        message = "Target: ${targetObj.javaClass.name}",
                        data = jsonObj
                    )
                }
            }
            "GET_VIEW_TREE" -> {
                val tree = if (currentActivity != null) {
                    ViewTreeExtractor.extractFromActivity(currentActivity)
                } else {
                    ViewTreeExtractor.extractFromProcess(packageName)
                }
                HookIpcResponse(
                    success = true,
                    message = "View tree extracted from $packageName (${if (currentActivity != null) currentActivity.javaClass.name else "Process Windows"})",
                    uiNode = tree
                )
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
                        val clazz = MethodInvoker.loadClass(request.className, currentActivity?.classLoader)
                            ?: return HookIpcResponse(success = false, message = "Class not found: ${request.className}")
                        clazz
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
                        val clazz = MethodInvoker.loadClass(request.className, currentActivity?.classLoader)
                            ?: return HookIpcResponse(success = false, message = "Class not found: ${request.className}")
                        clazz
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
