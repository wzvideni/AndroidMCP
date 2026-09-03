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
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

object HookClientManager {

    private const val TAG = "HookClientManager"
    private const val TIMEOUT_MS = 2500

    suspend fun sendCommand(
        packageName: String,
        request: HookIpcRequest
    ): HookIpcResponse = withContext(Dispatchers.IO) {
        val port = HookIpcServer.getPortForPackage(packageName)
        val socketName = "androidmcp_hook_$packageName"
        val reqJson = jsonConfig.encodeToString(request)

        // 1. Prioritize Loopback TCP (Bypasses all SELinux cross-domain socket restrictions)
        var tcpSocket: Socket? = null
        try {
            tcpSocket = Socket()
            tcpSocket.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MS)
            tcpSocket.soTimeout = TIMEOUT_MS

            val writer = PrintWriter(OutputStreamWriter(tcpSocket.getOutputStream(), Charsets.UTF_8), true)
            val reader = BufferedReader(InputStreamReader(tcpSocket.getInputStream(), Charsets.UTF_8))

            writer.println(reqJson)

            val respLine = reader.readLine()
            if (respLine != null) {
                return@withContext jsonConfig.decodeFromString<HookIpcResponse>(respLine)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "TCP IPC connect to $packageName:127.0.0.1:$port failed: ${e.message}")
        } finally {
            try { tcpSocket?.close() } catch (_: Throwable) {}
        }

        // 2. Fallback to Abstract LocalSocket
        var localSocket: LocalSocket? = null
        try {
            localSocket = LocalSocket()
            localSocket.soTimeout = TIMEOUT_MS
            val address = LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
            localSocket.connect(address)

            val writer = PrintWriter(OutputStreamWriter(localSocket.outputStream, Charsets.UTF_8), true)
            val reader = BufferedReader(InputStreamReader(localSocket.inputStream, Charsets.UTF_8))

            writer.println(reqJson)

            val respLine = reader.readLine()
            if (respLine != null) {
                return@withContext jsonConfig.decodeFromString<HookIpcResponse>(respLine)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "LocalSocket connect to $socketName failed: ${e.message}")
        } finally {
            try { localSocket?.close() } catch (_: Throwable) {}
        }

        HookIpcResponse(
            success = false,
            message = "Cannot communicate with hooked app $packageName (Is LSPosed active and module enabled for this app?)"
        )
    }

    suspend fun isAppHookedAndActive(packageName: String): Boolean {
        val resp = sendCommand(packageName, HookIpcRequest(action = "PING", targetPackage = packageName))
        return resp.success
    }
}
