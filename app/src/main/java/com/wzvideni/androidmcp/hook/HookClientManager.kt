package com.wzvideni.androidmcp.hook

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.wzvideni.androidmcp.model.HookIpcRequest
import com.wzvideni.androidmcp.model.HookIpcResponse
import com.wzvideni.androidmcp.model.jsonConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

object HookClientManager {

    private const val TAG = "HookClientManager"
    private const val TIMEOUT_MS = 2500

    suspend fun sendCommand(
        packageName: String,
        request: HookIpcRequest
    ): HookIpcResponse = withContext(Dispatchers.IO) {
        val socketName = "androidmcp_hook_$packageName"
        var socket: LocalSocket? = null
        try {
            socket = LocalSocket()
            socket.soTimeout = TIMEOUT_MS
            val address = LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
            socket.connect(address)

            val writer = PrintWriter(socket.outputStream, true)
            val reader = BufferedReader(InputStreamReader(socket.inputStream))

            val reqJson = jsonConfig.encodeToString(request)
            writer.println(reqJson)

            val respLine = reader.readLine()
            if (respLine != null) {
                jsonConfig.decodeFromString<HookIpcResponse>(respLine)
            } else {
                HookIpcResponse(success = false, message = "Empty response from hooked app $packageName")
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Cannot connect to hooked app socket $socketName: ${e.message}")
            HookIpcResponse(
                success = false,
                message = "Cannot communicate with hooked app $packageName (Is LSPosed active and module enabled for this app?): ${e.message}"
            )
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun isAppHookedAndActive(packageName: String): Boolean {
        val resp = sendCommand(packageName, HookIpcRequest(action = "PING", targetPackage = packageName))
        return resp.success
    }
}
