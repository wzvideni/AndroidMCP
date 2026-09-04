package com.wzvideni.androidmcp.server

import android.content.Context
import android.util.Log
import com.wzvideni.androidmcp.engine.MacroManager
import com.wzvideni.androidmcp.engine.PrivilegeManager
import com.wzvideni.androidmcp.engine.RootBridge
import com.wzvideni.androidmcp.mcp.McpProtocolHandler
import com.wzvideni.androidmcp.model.DeviceInfo
import com.wzvideni.androidmcp.model.JsonRpcError
import com.wzvideni.androidmcp.model.JsonRpcRequest
import com.wzvideni.androidmcp.model.JsonRpcResponse
import com.wzvideni.androidmcp.model.PrivilegeStatus
import com.wzvideni.androidmcp.model.jsonConfig
import com.wzvideni.androidmcp.vision.ScreenCapturer
import com.wzvideni.androidmcp.vision.SetOfMarkAnnotator
import com.wzvideni.androidmcp.vision.UiTreeFlattener
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.UUID

@Serializable
data class FsItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    val permissions: String
)

class McpHttpServer(
    private val context: Context,
    val port: Int = 8080,
    /**
     * 由外部（Koin DI）注入的 MCP 协议处理器。
     */
    val handler: McpProtocolHandler,
    val privilegeManager: PrivilegeManager = handler.privilegeManager
) {
    companion object {
        private const val TAG = "McpHttpServer"
    }

    private var server: EmbeddedServer<*, *>? = null
    private val sseMessages = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 64)

    fun start() {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.Accept)
                allowHeader("X-Requested-With")
                allowHeader("mcp-version")
                allowHeader("mcp-protocol-version")
                allowHeadersPrefixed("")
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Put)
                allowMethod(HttpMethod.Delete)
                allowMethod(HttpMethod.Head)
                allowNonSimpleContentTypes = true
            }
            install(ContentNegotiation) {
                json(jsonConfig)
            }
            install(SSE)

            routing {
                // Dashboard Web UI
                get("/") {
                    call.respondText(getDashboardHtml(), ContentType.Text.Html)
                }

                // Status API
                get("/api/status") {
                    val status = privilegeManager.getPrivilegeStatus()
                    val info = privilegeManager.getDeviceInfo(context)
                    val json = buildJsonObject {
                        put("serverPort", JsonPrimitive(port))
                        put("privileges", jsonConfig.encodeToJsonElement(PrivilegeStatus.serializer(), status))
                        put("device", jsonConfig.encodeToJsonElement(DeviceInfo.serializer(), info))
                    }
                    call.respondText(jsonConfig.encodeToString(json), ContentType.Application.Json)
                }

                // Quick Screenshot API (Supports optional Set-of-Mark badges)
                get("/api/screenshot") {
                    val som = call.request.queryParameters["som"] == "true"
                    val quality = call.request.queryParameters["quality"]?.toIntOrNull() ?: 80
                    var bmp = ScreenCapturer.captureBitmap()
                    if (bmp != null) {
                        if (som) {
                            val (tree, _) = handler.dumpUiTree()
                            if (tree != null) {
                                val flat = UiTreeFlattener.flattenInteractive(tree).filter { it.id > 0 }
                                bmp = SetOfMarkAnnotator.annotate(bmp, flat)
                            }
                        }
                        val bytes = ScreenCapturer.toByteArrayJpeg(bmp, quality)
                        call.respondBytes(bytes, ContentType.Image.JPEG)
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, "Failed to capture screenshot")
                    }
                }

                // Low-latency MJPEG Stream
                get("/api/stream") {
                    val fps = (call.request.queryParameters["fps"]?.toIntOrNull() ?: 15).coerceIn(1, 30)
                    val quality = (call.request.queryParameters["quality"]?.toIntOrNull() ?: 70).coerceIn(10, 100)
                    val som = call.request.queryParameters["som"] == "true"
                    val delayMs = 1000L / fps

                    call.response.headers.append("Cache-Control", "no-cache, private, no-store, must-revalidate")
                    call.response.headers.append("Pragma", "no-cache")

                    call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=--frame")) {
                        try {
                            while (true) {
                                var bmp = ScreenCapturer.captureBitmap()
                                if (bmp != null) {
                                    if (som) {
                                        val (tree, _) = handler.dumpUiTree()
                                        if (tree != null) {
                                            val flat = UiTreeFlattener.flattenInteractive(tree).filter { it.id > 0 }
                                            bmp = SetOfMarkAnnotator.annotate(bmp, flat)
                                        }
                                    }
                                    val bytes = ScreenCapturer.toByteArrayJpeg(bmp, quality)
                                    writeStringUtf8("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\n\r\n")
                                    writeFully(bytes)
                                    writeStringUtf8("\r\n")
                                    flush()
                                }
                                delay(delayMs)
                            }
                        } catch (_: Throwable) {
                            // Client disconnected
                        }
                    }
                }

                // File System APIs
                get("/api/fs/list") {
                    val dirPath = call.request.queryParameters["path"] ?: "/sdcard"
                    val items = listFileSystem(dirPath)
                    val json = jsonConfig.encodeToString(items)
                    call.respondText(json, ContentType.Application.Json)
                }

                get("/api/fs/download") {
                    val filePath = call.request.queryParameters["path"]
                    if (filePath.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing path parameter")
                        return@get
                    }
                    val file = File(filePath)
                    val name = file.name
                    call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
                    if (file.canRead() && file.isFile) {
                        call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
                    } else {
                        val tmpFile = File(context.cacheDir, "_dl_${System.currentTimeMillis()}_$name")
                        val (code, _) = RootBridge.exec("cp \"$filePath\" \"${tmpFile.absolutePath}\" && chmod 666 \"${tmpFile.absolutePath}\"")
                        if (code == 0 && tmpFile.exists()) {
                            val bytes = tmpFile.readBytes()
                            tmpFile.delete()
                            call.respondBytes(bytes, ContentType.Application.OctetStream)
                            return@get
                        }
                        call.respond(HttpStatusCode.NotFound, "File not found or unreadable: $filePath")
                    }
                }

                post("/api/fs/upload") {
                    val targetPath = call.request.queryParameters["path"]
                    if (targetPath.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing path parameter")
                        return@post
                    }
                    val bytes = call.receive<ByteArray>()
                    val destFile = File(targetPath)
                    try {
                        destFile.parentFile?.mkdirs()
                        destFile.writeBytes(bytes)
                        call.respondText("""{"success":true,"path":"$targetPath","bytes":${bytes.size}}""", ContentType.Application.Json)
                    } catch (_: Throwable) {
                        val tmpFile = File(context.cacheDir, "_up_${System.currentTimeMillis()}")
                        tmpFile.writeBytes(bytes)
                        val parentDir = destFile.parent ?: "/data/local/tmp"
                        RootBridge.exec("mkdir -p \"$parentDir\" && cp \"${tmpFile.absolutePath}\" \"$targetPath\" && chmod 666 \"$targetPath\"; rm -f \"${tmpFile.absolutePath}\"")
                        call.respondText("""{"success":true,"path":"$targetPath","bytes":${bytes.size},"via":"root"}""", ContentType.Application.Json)
                    }
                }

                post("/api/fs/delete") {
                    val targetPath = call.request.queryParameters["path"] ?: call.receiveText().trim()
                    if (targetPath.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Missing path")
                        return@post
                    }
                    val f = File(targetPath)
                    var ok = false
                    try {
                        ok = f.deleteRecursively()
                    } catch (_: Throwable) {}
                    if (!ok) {
                        val (code, _) = RootBridge.exec("rm -rf \"$targetPath\"")
                        ok = code == 0
                    }
                    call.respondText("""{"success":$ok,"path":"$targetPath"}""", ContentType.Application.Json)
                }

                // Macro APIs
                get("/api/macro/list") {
                    val macros = MacroManager.listMacros(context)
                    val array = buildJsonArray {
                        macros.forEach { (name, count) ->
                            add(buildJsonObject {
                                put("name", JsonPrimitive(name))
                                put("stepCount", JsonPrimitive(count))
                            })
                        }
                    }
                    call.respondText(jsonConfig.encodeToString(array), ContentType.Application.Json)
                }

                post("/api/macro/run") {
                    val name = call.request.queryParameters["name"] ?: call.receiveText().trim()
                    val actions = MacroManager.getMacro(context, name)
                    if (actions == null) {
                        call.respond(HttpStatusCode.NotFound, "Macro '$name' not found")
                        return@post
                    }
                    val (ok, log) = MacroManager.executeActions(actions)
                    call.respondText(buildJsonObject {
                        put("success", JsonPrimitive(ok))
                        put("log", JsonPrimitive(log))
                    }.toString(), ContentType.Application.Json)
                }

                post("/api/macro/record/start") {
                    MacroManager.startRecording()
                    call.respondText("""{"success":true,"isRecording":true}""", ContentType.Application.Json)
                }

                post("/api/macro/record/stop") {
                    val recorded = MacroManager.stopRecording()
                    val saveName = call.request.queryParameters["save_name"]
                    if (!saveName.isNullOrBlank() && recorded.isNotEmpty()) {
                        MacroManager.saveMacro(context, saveName, recorded)
                    }
                    call.respondText(buildJsonObject {
                        put("success", JsonPrimitive(true))
                        put("count", JsonPrimitive(recorded.size))
                    }.toString(), ContentType.Application.Json)
                }

                // JSON-RPC POST Handlers (Supports all client transport formats)
                post("/mcp/v1/rpc") { handleRpcCall(call, sseMessages) }
                post("/mcp/v1/sse") { handleRpcCall(call, sseMessages) }
                post("/mcp/v1/messages") { handleRpcCall(call, sseMessages) }
                post("/mcp") { handleRpcCall(call, sseMessages) }
                post("/sse") { handleRpcCall(call, sseMessages) }
                post("/messages") { handleRpcCall(call, sseMessages) }
                post("/") { handleRpcCall(call, sseMessages) }

                // Standard MCP SSE Streams
                sse("/mcp/v1/sse") { handleSseStream(this, sseMessages) }
                sse("/sse") { handleSseStream(this, sseMessages) }
                sse("/mcp") { handleSseStream(this, sseMessages) }
            }
        }.start(wait = false)
        Log.i(TAG, "McpHttpServer started on port $port")
    }

    private suspend fun handleRpcCall(call: ApplicationCall, sseMessages: MutableSharedFlow<Pair<String, String>>) {
        try {
            val sessionId = call.request.queryParameters["sessionId"]
            val body = call.receiveText()
            if (body.isBlank()) {
                call.respond(HttpStatusCode.Accepted)
                return
            }
            val req = jsonConfig.decodeFromString<JsonRpcRequest>(body)
            val resp = handler.handleJsonRpc(req)

            if (resp == null) {
                // Notification (e.g. notifications/initialized): Do not emit to SSE and respond 202 Accepted
                call.respond(HttpStatusCode.Accepted)
                return
            }

            val respJson = jsonConfig.encodeToString(resp)
            if (sessionId != null) {
                // MCP SSE transport: emit response to SSE channel and acknowledge POST with 202 Accepted
                sseMessages.emit(sessionId to respJson)
                call.respond(HttpStatusCode.Accepted)
            } else {
                // Direct HTTP transport: respond with JSON body directly
                call.respondText(respJson, ContentType.Application.Json, HttpStatusCode.OK)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling RPC POST: ${e.message}", e)
            val errResp = JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                error = JsonRpcError(code = -32700, message = "Parse error: ${e.message}")
            )
            call.respondText(jsonConfig.encodeToString(errResp), ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
    }

    private suspend fun handleSseStream(session: ServerSSESession, sseMessages: MutableSharedFlow<Pair<String, String>>) {
        val sessionId = UUID.randomUUID().toString()
        Log.i(TAG, "SSE client connected, sessionId=$sessionId")
        session.send(ServerSentEvent(event = "endpoint", data = "/mcp/v1/messages?sessionId=$sessionId"))

        try {
            sseMessages.asSharedFlow().collect { (targetSession, message) ->
                if (targetSession == sessionId || targetSession == "all") {
                    session.send(ServerSentEvent(event = "message", data = message))
                }
            }
        } catch (e: Throwable) {
            Log.i(TAG, "SSE client disconnected, sessionId=$sessionId: ${e.message}")
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "McpHttpServer stopped")
    }

    val isRunning: Boolean get() = server != null

    private suspend fun listFileSystem(dirPath: String): List<FsItem> {
        val dir = File(dirPath)
        val javaFiles = try {
            if (dir.exists() && dir.isDirectory) dir.listFiles() else null
        } catch (_: Throwable) {
            null
        }
        if (javaFiles != null) {
            return javaFiles.map { f ->
                FsItem(
                    name = f.name,
                    path = f.absolutePath,
                    isDir = f.isDirectory,
                    size = if (f.isDirectory) 0L else f.length(),
                    lastModified = f.lastModified(),
                    permissions = "${if (f.canRead()) "r" else "-"}${if (f.canWrite()) "w" else "-"}${if (f.canExecute()) "x" else "-"}"
                )
            }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }

        // Root fallback via ls -la
        val cleanDir = if (dirPath.endsWith("/")) dirPath else "$dirPath/"
        val (code, out) = RootBridge.exec("ls -la \"$cleanDir\"")
        if (code != 0 || out.isBlank()) return emptyList()

        val items = mutableListOf<FsItem>()
        out.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("total") || trimmed.isBlank()) return@forEach
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size >= 8) {
                val perms = parts[0]
                val isDir = perms.startsWith("d")
                val timeIdx = parts.indexOfFirst { it.matches(Regex("\\d{2}:\\d{2}(:\\d{2})?")) }
                val name = if (timeIdx > 0 && timeIdx + 1 < parts.size) {
                    parts.subList(timeIdx + 1, parts.size).joinToString(" ")
                } else {
                    parts.last()
                }
                if (name == "." || name == "..") return@forEach
                val cleanName = if (name.contains(" -> ")) name.substringBefore(" -> ") else name
                val size = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                items.add(
                    FsItem(
                        name = cleanName,
                        path = "$cleanDir$cleanName",
                        isDir = isDir,
                        size = if (isDir) 0L else size,
                        lastModified = System.currentTimeMillis(),
                        permissions = perms
                    )
                )
            }
        }
        return items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun getDashboardHtml(): String {
        val dollar = "$"
        return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>AndroidMCP 智能工作台</title>
            <style>
                :root {
                    --bg-page: #0b0f19;
                    --bg-card: #151d2f;
                    --bg-card-header: #1e293b;
                    --bg-hover: #1e293b;
                    --border-color: #243049;
                    --border-light: #334155;
                    --accent-cyan: #38bdf8;
                    --accent-blue: #3b82f6;
                    --accent-green: #10b981;
                    --accent-amber: #f59e0b;
                    --accent-red: #ef4444;
                    --text-primary: #f8fafc;
                    --text-secondary: #94a3b8;
                    --text-muted: #64748b;
                }
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                    background: var(--bg-page);
                    color: var(--text-primary);
                    line-height: 1.5;
                    min-height: 100vh;
                    display: flex;
                    flex-direction: column;
                }
                header {
                    background: var(--bg-card);
                    border-bottom: 1px solid var(--border-color);
                    padding: 12px 24px;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    position: sticky;
                    top: 0;
                    z-index: 100;
                }
                .logo-box { display: flex; align-items: center; gap: 10px; }
                .logo-title { font-size: 18px; font-weight: 700; color: var(--accent-cyan); display: flex; align-items: center; gap: 8px; }
                .badge {
                    font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 9999px;
                    display: inline-flex; align-items: center; gap: 4px; border: 1px solid transparent;
                }
                .badge-green { background: #10b98122; color: #34d399; border-color: #10b98144; }
                .badge-blue { background: #0284c722; color: #38bdf8; border-color: #0284c744; }
                .badge-amber { background: #f59e0b22; color: #fbbf24; border-color: #f59e0b44; }
                .badge-red { background: #ef444422; color: #f87171; border-color: #ef444444; }
                .badge-gray { background: #33415544; color: #94a3b8; border-color: #334155; }
                .device-chips { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
                
                .main-layout {
                    flex: 1;
                    display: grid;
                    grid-template-columns: 400px 1fr;
                    gap: 20px;
                    padding: 20px 24px;
                    max-width: 1700px;
                    margin: 0 auto;
                    width: 100%;
                }
                @media (max-width: 960px) {
                    .main-layout { grid-template-columns: 1fr; }
                }
                
                .card {
                    background: var(--bg-card);
                    border: 1px solid var(--border-color);
                    border-radius: 14px;
                    display: flex;
                    flex-direction: column;
                    overflow: hidden;
                }
                .card-header {
                    background: var(--bg-card-header);
                    padding: 12px 18px;
                    border-bottom: 1px solid var(--border-color);
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                }
                .card-title { font-size: 15px; font-weight: 600; color: var(--text-primary); display: flex; align-items: center; gap: 8px; }
                .card-body { padding: 18px; flex: 1; display: flex; flex-direction: column; }
                
                /* Phone Screen Mockup */
                .phone-wrapper {
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    gap: 12px;
                }
                .phone-bezel {
                    background: #1e293b;
                    border: 4px solid #334155;
                    border-radius: 28px;
                    padding: 10px 8px 12px 8px;
                    box-shadow: 0 20px 40px -15px rgba(0,0,0,0.7), 0 0 0 1px rgba(255,255,255,0.05);
                    width: 100%;
                    max-width: 340px;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    position: relative;
                }
                .phone-notch {
                    width: 60px;
                    height: 5px;
                    background: #0f172a;
                    border-radius: 9999px;
                    margin-bottom: 8px;
                }
                .screen-box {
                    position: relative;
                    width: 100%;
                    border-radius: 16px;
                    overflow: hidden;
                    background: #000;
                    cursor: crosshair;
                    user-select: none;
                    line-height: 0;
                }
                #screen-img {
                    width: 100%;
                    height: auto;
                    display: block;
                    border-radius: 16px;
                    pointer-events: none;
                }
                .coords-indicator {
                    position: absolute;
                    bottom: 8px;
                    right: 8px;
                    background: rgba(15, 23, 42, 0.85);
                    border: 1px solid rgba(255,255,255,0.1);
                    color: var(--accent-cyan);
                    font-size: 11px;
                    font-family: monospace;
                    padding: 2px 6px;
                    border-radius: 4px;
                    pointer-events: none;
                }
                
                .phone-nav-bar {
                    display: flex;
                    justify-content: space-around;
                    width: 100%;
                    margin-top: 10px;
                    padding-top: 8px;
                    border-top: 1px solid #334155;
                }
                .nav-btn {
                    background: transparent;
                    border: none;
                    color: var(--text-secondary);
                    font-size: 12px;
                    font-weight: 500;
                    padding: 5px 8px;
                    border-radius: 6px;
                    cursor: pointer;
                    display: flex;
                    align-items: center;
                    gap: 3px;
                    transition: all 0.2s;
                }
                .nav-btn:hover { background: #334155; color: var(--text-primary); }
                
                .btn-group { display: flex; gap: 8px; flex-wrap: wrap; }
                .btn {
                    background: var(--accent-blue);
                    color: white;
                    border: none;
                    padding: 6px 12px;
                    border-radius: 8px;
                    font-size: 12px;
                    font-weight: 600;
                    cursor: pointer;
                    display: inline-flex;
                    align-items: center;
                    gap: 5px;
                    transition: all 0.15s ease;
                }
                .btn:hover { filter: brightness(1.15); transform: translateY(-1px); }
                .btn:active { transform: translateY(0); }
                .btn-secondary { background: #1e293b; color: var(--text-primary); border: 1px solid var(--border-light); }
                .btn-secondary:hover { background: #334155; }
                .btn-success { background: #059669; }
                .btn-danger { background: #dc2626; }
                .btn-warning { background: #d97706; }
                
                /* Tabs */
                .tabs-header {
                    display: flex;
                    border-bottom: 1px solid var(--border-color);
                    background: var(--bg-card-header);
                    overflow-x: auto;
                }
                .tab-btn {
                    padding: 12px 16px;
                    background: transparent;
                    border: none;
                    border-bottom: 2px solid transparent;
                    color: var(--text-secondary);
                    font-size: 13px;
                    font-weight: 600;
                    cursor: pointer;
                    white-space: nowrap;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    transition: all 0.2s;
                }
                .tab-btn:hover { color: var(--text-primary); }
                .tab-btn.active {
                    color: var(--accent-cyan);
                    border-bottom-color: var(--accent-cyan);
                    background: rgba(56, 189, 248, 0.05);
                }
                
                .tab-content { display: none; flex: 1; flex-direction: column; }
                .tab-content.active { display: flex; }
                
                .input-box {
                    background: #090d16;
                    border: 1px solid var(--border-light);
                    border-radius: 8px;
                    color: var(--text-primary);
                    padding: 7px 10px;
                    font-size: 13px;
                    width: 100%;
                    outline: none;
                    transition: border-color 0.2s;
                }
                .input-box:focus { border-color: var(--accent-cyan); }
                
                .console-output {
                    background: #070a12;
                    border: 1px solid var(--border-color);
                    border-radius: 8px;
                    padding: 12px;
                    font-family: "JetBrains Mono", "SF Mono", Consolas, monospace;
                    font-size: 12px;
                    color: #a5f3fc;
                    overflow-y: auto;
                    max-height: 440px;
                    white-space: pre-wrap;
                    word-break: break-all;
                }
                
                .tree-item {
                    background: #0e1626;
                    border: 1px solid var(--border-color);
                    border-radius: 8px;
                    padding: 8px 12px;
                    margin-bottom: 6px;
                    font-family: monospace;
                    font-size: 12px;
                    cursor: pointer;
                    transition: all 0.2s;
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                }
                .tree-item:hover { background: #1a253c; border-color: var(--accent-cyan); }
                .tree-tag { background: #3b82f633; color: #60a5fa; padding: 2px 6px; border-radius: 4px; font-weight: bold; margin-right: 6px; }
                .tree-bounds { color: var(--text-muted); font-size: 11px; }

                /* Tables */
                .data-table {
                    width: 100%;
                    border-collapse: collapse;
                    font-size: 13px;
                }
                .data-table th {
                    background: #1e293b;
                    color: var(--text-secondary);
                    text-align: left;
                    padding: 8px 12px;
                    font-weight: 600;
                    border-bottom: 1px solid var(--border-color);
                }
                .data-table td {
                    padding: 8px 12px;
                    border-bottom: 1px solid var(--border-color);
                }
                .data-table tr:hover td {
                    background: rgba(56, 189, 248, 0.04);
                }
            </style>
        </head>
        <body>
            <header>
                <div class="logo-box">
                    <span class="logo-title">🤖 AndroidMCP 控制台</span>
                    <span class="badge badge-green">● 局域网在线</span>
                    <span class="badge badge-blue">端口 $port</span>
                    <span id="macro-rec-badge" class="badge badge-red" style="display: none;">● 正在录制宏</span>
                </div>
                <div class="device-chips" id="device-info-bar">
                    <span class="badge badge-gray">正在读取设备状态...</span>
                </div>
            </header>

            <div class="main-layout">
                <!-- Left Phone Mirror Panel -->
                <div class="card">
                    <div class="card-header">
                        <div class="card-title">📱 屏幕实时镜像</div>
                        <div class="btn-group">
                            <button class="btn btn-secondary" id="stream-btn" onclick="toggleStream()">⚡ 实时流</button>
                            <button class="btn btn-secondary" onclick="refreshScreen(false)">🔄 刷新</button>
                            <button class="btn btn-secondary" id="som-btn" onclick="toggleSom()">🏷️ SoM</button>
                            <select id="auto-refresh-select" onchange="changeAutoRefresh(this.value)" style="background: #1e293b; color: white; border: 1px solid #334155; border-radius: 6px; padding: 4px 6px; font-size: 11px;">
                                <option value="0">定时: 关</option>
                                <option value="1000">1 秒</option>
                                <option value="2000">2 秒</option>
                                <option value="5000">5 秒</option>
                            </select>
                        </div>
                    </div>
                    <div class="card-body phone-wrapper">
                        <div class="phone-bezel">
                            <div class="phone-notch"></div>
                            <div class="screen-box" id="screen-box">
                                <img id="screen-img" src="/api/screenshot" alt="设备屏幕" />
                                <div class="coords-indicator" id="coords-pill">X: 0, Y: 0</div>
                            </div>
                            <div class="phone-nav-bar">
                                <button class="nav-btn" onclick="pressKey('BACK')">⬅️ 返回</button>
                                <button class="nav-btn" onclick="pressKey('HOME')">🏠 桌面</button>
                                <button class="nav-btn" onclick="pressKey('RECENTS')">📱 任务</button>
                                <button class="nav-btn" onclick="pressKey('POWER')">🔒 电源</button>
                                <button class="nav-btn" onclick="pressKey('VOLUME_UP')">🔊+</button>
                                <button class="nav-btn" onclick="pressKey('VOLUME_DOWN')">🔉-</button>
                            </div>
                            <!-- Quick Macro toolbar directly under phone controls -->
                            <div style="display: flex; gap: 6px; margin-top: 8px; width: 100%; align-items: center; justify-content: center;">
                                <button class="btn btn-secondary" id="quick-rec-btn" onclick="toggleMacroRecording()" style="font-size: 11px; padding: 4px 8px;">🔴 录制宏</button>
                                <select id="quick-macro-select" class="input-box" style="font-size: 11px; padding: 3px 6px; width: 130px;">
                                    <option value="">选择宏...</option>
                                </select>
                                <button class="btn btn-secondary" onclick="runQuickMacro()" style="font-size: 11px; padding: 4px 8px;">▶️ 回放</button>
                            </div>
                        </div>
                        <p style="font-size: 11px; color: var(--text-muted); text-align: center;">
                            💡 提示：在屏幕上<b>单击</b>点击，<b>按住拖拽</b>滑动；开启「实时流」享受高帧率零延迟推流
                        </p>
                    </div>
                </div>

                <!-- Right Super Console Tabs -->
                <div class="card">
                    <div class="tabs-header">
                        <button class="tab-btn active" onclick="switchTab('quick')">⚡ 快捷控制</button>
                        <button class="tab-btn" onclick="switchTab('tree')">🌲 UI 布局树</button>
                        <button class="tab-btn" onclick="switchTab('fs')">📁 文件管理</button>
                        <button class="tab-btn" onclick="switchTab('macro')">🔴 自动化宏</button>
                        <button class="tab-btn" onclick="switchTab('shell')">💻 特权终端</button>
                        <button class="tab-btn" onclick="switchTab('playground')">🛠️ MCP 工具台</button>
                        <button class="tab-btn" onclick="switchTab('logcat')">📜 实时日志</button>
                    </div>

                    <div class="card-body">
                        <!-- Tab 1: 快捷控制 -->
                        <div id="tab-quick" class="tab-content active">
                            <div style="margin-bottom: 14px;">
                                <label style="font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; display: block;">⌨️ 快速输入文字</label>
                                <div style="display: flex; gap: 8px;">
                                    <input id="quick-input-text" class="input-box" placeholder="输入文字（支持中文、英文、符号）..." onkeydown="if(event.key==='Enter') sendInputText()" />
                                    <button class="btn" onclick="sendInputText()">发送 (Enter)</button>
                                </div>
                            </div>

                            <div style="margin-bottom: 14px;">
                                <label style="font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; display: block;">🚀 常用应用拉起</label>
                                <div class="btn-group" style="margin-bottom: 8px;">
                                    <button class="btn btn-secondary" onclick="launchApp('com.android.settings')">⚙️ 系统设置</button>
                                    <button class="btn btn-secondary" onclick="launchApp('org.lsposed.manager')">🧩 LSPosed</button>
                                    <button class="btn btn-secondary" onclick="launchApp('com.wzvideni.androidmcp')">🤖 AndroidMCP</button>
                                    <button class="btn btn-secondary" onclick="launchApp('com.android.chrome')">🌐 浏览器</button>
                                </div>
                                <div style="display: flex; gap: 8px;">
                                    <input id="custom-pkg-input" class="input-box" placeholder="输入要启动的应用包名 (如 com.tencent.mm)..." />
                                    <button class="btn" onclick="launchApp(document.getElementById('custom-pkg-input').value)">启动</button>
                                    <button class="btn btn-danger" onclick="stopApp(document.getElementById('custom-pkg-input').value)">强制停止</button>
                                </div>
                            </div>

                            <div style="margin-bottom: 14px;">
                                <label style="font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; display: block;">👆 手势快捷操作</label>
                                <div class="btn-group">
                                    <button class="btn btn-secondary" onclick="swipeDirection('up')">⬆️ 向上翻页</button>
                                    <button class="btn btn-secondary" onclick="swipeDirection('down')">⬇️ 向下翻页</button>
                                    <button class="btn btn-secondary" onclick="swipeDirection('left')">⬅️ 向左滑动</button>
                                    <button class="btn btn-secondary" onclick="swipeDirection('right')">➡️ 向右滑动</button>
                                </div>
                            </div>

                            <label style="font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; display: block;">📋 响应结果</label>
                            <pre class="console-output" id="quick-output">等待执行操作...</pre>
                        </div>

                        <!-- Tab 2: UI 布局树 -->
                        <div id="tab-tree" class="tab-content">
                            <div style="display: flex; gap: 8px; margin-bottom: 12px;">
                                <button class="btn" onclick="loadUiTree()">🔄 抓取当前屏幕布局</button>
                                <input id="tree-search-box" class="input-box" placeholder="过滤元素文本、ID、类型..." oninput="filterUiTree()" />
                            </div>
                            <div id="tree-list-container" style="flex: 1; overflow-y: auto; max-height: 480px;">
                                <div style="color: var(--text-muted); text-align: center; padding: 40px 0;">点击上方「抓取当前屏幕布局」提取 UI 树</div>
                            </div>
                        </div>

                        <!-- Tab 3: 文件管理 -->
                        <div id="tab-fs" class="tab-content">
                            <div class="btn-group" style="margin-bottom: 10px;">
                                <button class="btn btn-secondary" onclick="loadFsDir('/sdcard')">📁 /sdcard</button>
                                <button class="btn btn-secondary" onclick="loadFsDir('/sdcard/Download')">📥 下载目录</button>
                                <button class="btn btn-secondary" onclick="loadFsDir('/data/local/tmp')">⚡ /data/local/tmp</button>
                                <button class="btn btn-secondary" onclick="loadFsDir('/data/data/com.wzvideni.androidmcp')">🤖 App私有目录</button>
                            </div>
                            <div style="display: flex; gap: 8px; margin-bottom: 12px; align-items: center;">
                                <button class="btn btn-secondary" onclick="goFsUp()">⬆️ 上一级</button>
                                <input id="fs-path-input" class="input-box" value="/sdcard" onkeydown="if(event.key==='Enter') loadFsDir(this.value)" />
                                <button class="btn" onclick="loadFsDir(document.getElementById('fs-path-input').value)">🔄 前往</button>
                                <button class="btn btn-success" onclick="triggerFsUpload()">📤 上传文件</button>
                                <input type="file" id="fs-file-input" style="display: none;" onchange="handleFsFileUpload(event)" />
                            </div>
                            <div id="fs-upload-status" style="font-size: 12px; color: var(--accent-cyan); margin-bottom: 6px;"></div>
                            <div style="flex: 1; overflow-y: auto; max-height: 450px; border: 1px solid var(--border-color); border-radius: 8px;">
                                <table class="data-table">
                                    <thead>
                                        <tr>
                                            <th>名称</th>
                                            <th>类型</th>
                                            <th>大小</th>
                                            <th>权限</th>
                                            <th style="width: 130px;">操作</th>
                                        </tr>
                                    </thead>
                                    <tbody id="fs-table-body">
                                        <tr><td colspan="5" style="text-align: center; padding: 20px; color: var(--text-muted);">正在加载文件列表...</td></tr>
                                    </tbody>
                                </table>
                            </div>
                        </div>

                        <!-- Tab 4: 自动化宏 -->
                        <div id="tab-macro" class="tab-content">
                            <div style="display: flex; gap: 10px; align-items: center; margin-bottom: 14px; background: #0e1626; padding: 12px; border-radius: 8px; border: 1px solid var(--border-color);">
                                <button class="btn btn-danger" id="tab-macro-rec-btn" onclick="toggleMacroRecording()">🔴 开始录制宏</button>
                                <span style="font-size: 12px; color: var(--text-secondary); flex: 1;">
                                    开启录制后，在左侧屏幕上的点击、滑动、按键将自动录入为自动化动作。
                                </span>
                                <button class="btn btn-secondary" onclick="loadMacrosList()">🔄 刷新列表</button>
                            </div>

                            <div style="margin-bottom: 16px;">
                                <label style="font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 8px; display: block;">📜 已保存的自动化宏</label>
                                <div style="border: 1px solid var(--border-color); border-radius: 8px; overflow: hidden; max-height: 200px; overflow-y: auto;">
                                    <table class="data-table">
                                        <thead>
                                            <tr>
                                                <th>宏名称</th>
                                                <th>步骤数</th>
                                                <th style="width: 140px;">操作</th>
                                            </tr>
                                        </thead>
                                        <tbody id="macro-table-body">
                                            <tr><td colspan="3" style="text-align: center; padding: 15px; color: var(--text-muted);">暂无保存的宏</td></tr>
                                        </tbody>
                                    </table>
                                </div>
                            </div>

                            <div>
                                <label style="font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; display: block;">
                                    ⚡ 动作流批处理执行器 (run_actions)
                                </label>
                                <div class="btn-group" style="margin-bottom: 8px;">
                                    <button class="btn btn-secondary" onclick="loadMacroPreset('unlock')">示例: 解锁并回桌面</button>
                                    <button class="btn btn-secondary" onclick="loadMacroPreset('settings')">示例: 打开设置并翻页</button>
                                </div>
                                <textarea id="batch-actions-editor" class="input-box" style="font-family: monospace; height: 110px; margin-bottom: 8px;">[
  { "action": "tap", "x": 360, "y": 640 },
  { "action": "wait", "duration_ms": 500 },
  { "action": "press_key", "key": "BACK" }
]</textarea>
                                <button class="btn" onclick="runBatchActions()">🚀 执行动作流</button>
                            </div>

                            <label style="font-size: 13px; font-weight: 600; color: var(--text-secondary); margin: 10px 0 6px 0; display: block;">📋 宏执行日志</label>
                            <pre class="console-output" id="macro-run-output">等待执行宏...</pre>
                        </div>

                        <!-- Tab 5: 特权终端 -->
                        <div id="tab-shell" class="tab-content">
                            <div class="btn-group" style="margin-bottom: 10px;">
                                <button class="btn btn-secondary" onclick="runShellPreset('pm list packages -3')">📦 已安装第三方应用</button>
                                <button class="btn btn-secondary" onclick="runShellPreset('dumpsys battery')">🔋 电池详情</button>
                                <button class="btn btn-secondary" onclick="runShellPreset('getprop ro.product.model')">📱 设备型号</button>
                                <button class="btn btn-secondary" onclick="runShellPreset('top -n 1 -m 5')">📊 资源占用</button>
                            </div>
                            <div style="display: flex; gap: 8px; margin-bottom: 12px; align-items: center;">
                                <input id="shell-cmd-input" class="input-box" placeholder="输入 Shell 指令 (如 ps -ef)..." onkeydown="if(event.key==='Enter') executeShellCommand()" />
                                <label style="display: flex; align-items: center; gap: 4px; font-size: 13px; white-space: nowrap;">
                                    <input type="checkbox" id="shell-as-root" checked /> 以 Root 运行
                                </label>
                                <button class="btn" onclick="executeShellCommand()">执行 (Enter)</button>
                            </div>
                            <pre class="console-output" id="shell-output"># 终端就绪，请输入指令</pre>
                        </div>

                        <!-- Tab 6: MCP 工具台 -->
                        <div id="tab-playground" class="tab-content">
                            <div style="display: flex; gap: 8px; margin-bottom: 12px; align-items: center;">
                                <select id="mcp-tool-select" class="input-box" style="width: 280px;" onchange="onToolSelected(this.value)">
                                    <option value="get_device_info">get_device_info</option>
                                    <option value="get_activity_stack">get_activity_stack</option>
                                    <option value="get_ui_hierarchy">get_ui_hierarchy</option>
                                    <option value="capture_screenshot">capture_screenshot</option>
                                    <option value="tap">tap</option>
                                    <option value="swipe">swipe</option>
                                    <option value="long_press">long_press</option>
                                    <option value="drag_and_drop">drag_and_drop</option>
                                    <option value="input_text">input_text</option>
                                    <option value="press_key">press_key</option>
                                    <option value="launch_app">launch_app</option>
                                    <option value="stop_app">stop_app</option>
                                    <option value="clear_app_data">clear_app_data</option>
                                    <option value="install_apk">install_apk</option>
                                    <option value="pull_apk">pull_apk</option>
                                    <option value="execute_shell">execute_shell</option>
                                    <option value="get_recent_logs">get_recent_logs</option>
                                    <option value="get_notifications">get_notifications</option>
                                    <option value="wait_for_notification">wait_for_notification</option>
                                    <option value="wait_for_element">wait_for_element</option>
                                    <option value="click_by_selector">click_by_selector</option>
                                    <option value="manage_clipboard">manage_clipboard</option>
                                    <option value="system_control">system_control</option>
                                    <option value="system_file_ops">system_file_ops</option>
                                    <option value="send_intent">send_intent</option>
                                    <option value="hook_inspect_activity">hook_inspect_activity</option>
                                    <option value="hook_get_fragments">hook_get_fragments</option>
                                    <option value="hook_call_method">hook_call_method</option>
                                    <option value="hook_set_field">hook_set_field</option>
                                    <option value="hook_get_view_tree">hook_get_view_tree</option>
                                    <option value="hook_dump_sqlite">hook_dump_sqlite</option>
                                    <option value="hook_dump_shared_prefs">hook_dump_shared_prefs</option>
                                    <option value="hook_trace_method">hook_trace_method</option>
                                    <option value="run_actions">run_actions</option>
                                    <option value="manage_macro">manage_macro</option>
                                </select>
                                <button class="btn" onclick="runSelectedMcpTool()">🚀 发送 JSON-RPC 调用</button>
                            </div>
                            <div style="margin-bottom: 8px;">
                                <label style="font-size: 12px; color: var(--text-secondary);">参数 JSON (arguments):</label>
                                <textarea id="mcp-args-editor" class="input-box" style="font-family: monospace; height: 110px; margin-top: 4px;">{}</textarea>
                            </div>
                            <pre class="console-output" id="mcp-response-output">{}</pre>
                        </div>

                        <!-- Tab 7: 实时日志 -->
                        <div id="tab-logcat" class="tab-content">
                            <div style="display: flex; gap: 8px; margin-bottom: 12px; align-items: center;">
                                <input id="logcat-filter" class="input-box" placeholder="过滤 Tag 或关键字 (如 McpHttpServer)..." />
                                <input id="logcat-lines" type="number" value="60" class="input-box" style="width: 80px;" title="获取行数" />
                                <button class="btn" onclick="fetchLogcat()">🔄 抓取日志</button>
                            </div>
                            <pre class="console-output" id="logcat-output">点击「抓取日志」查看最新 Logcat</pre>
                        </div>
                    </div>
                </div>
            </div>

            <script>
                let currentSom = false;
                let autoRefreshTimer = null;
                let isStreaming = false;
                let isMacroRecording = false;
                let rawTreeData = null;
                let screenNaturalWidth = 720;
                let screenNaturalHeight = 1280;
                let currentFsPath = '/sdcard';

                // Tab switching
                function switchTab(tabName) {
                    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
                    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                    event.currentTarget.classList.add('active');
                    document.getElementById('tab-' + tabName).classList.add('active');
                    if (tabName === 'fs') loadFsDir(currentFsPath);
                    if (tabName === 'macro') loadMacrosList();
                }

                // Call JSON-RPC
                async function callRpc(method, params = {}) {
                    try {
                        const resp = await fetch('/mcp/v1/rpc', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ jsonrpc: '2.0', id: Date.now(), method, params })
                        });
                        return await resp.json();
                    } catch (e) {
                        return { error: { message: e.message } };
                    }
                }

                async function callTool(name, args = {}) {
                    return await callRpc('tools/call', { name, arguments: args });
                }

                // Screen refresh & Live Streaming
                function refreshScreen(som = currentSom) {
                    if (isStreaming) return;
                    const img = document.getElementById('screen-img');
                    img.src = '/api/screenshot?som=' + som + '&t=' + Date.now();
                }

                function toggleStream() {
                    const img = document.getElementById('screen-img');
                    const btn = document.getElementById('stream-btn');
                    isStreaming = !isStreaming;
                    if (isStreaming) {
                        btn.style.background = '#10b981';
                        btn.style.color = '#fff';
                        btn.textContent = '⏹️ 停止流';
                        img.src = '/api/stream?som=' + currentSom + '&fps=15&quality=60';
                        if (autoRefreshTimer) {
                            clearInterval(autoRefreshTimer);
                            document.getElementById('auto-refresh-select').value = '0';
                        }
                    } else {
                        btn.style.background = '#1e293b';
                        btn.style.color = 'var(--text-primary)';
                        btn.textContent = '⚡ 实时流';
                        img.src = '/api/screenshot?som=' + currentSom + '&t=' + Date.now();
                    }
                }

                function toggleSom() {
                    currentSom = !currentSom;
                    const btn = document.getElementById('som-btn');
                    if (currentSom) {
                        btn.style.background = '#0284c7';
                        btn.style.color = '#fff';
                    } else {
                        btn.style.background = '#1e293b';
                        btn.style.color = 'var(--text-primary)';
                    }
                    if (isStreaming) {
                        const img = document.getElementById('screen-img');
                        img.src = '/api/stream?som=' + currentSom + '&fps=15&quality=60';
                    } else {
                        refreshScreen(currentSom);
                    }
                }

                function changeAutoRefresh(intervalMs) {
                    if (autoRefreshTimer) clearInterval(autoRefreshTimer);
                    const ms = parseInt(intervalMs);
                    if (ms > 0) {
                        if (isStreaming) toggleStream();
                        autoRefreshTimer = setInterval(() => refreshScreen(currentSom), ms);
                    }
                }

                // Interactive touch & gesture handlers on screen box
                const screenBox = document.getElementById('screen-box');
                const coordsPill = document.getElementById('coords-pill');
                let isDragging = false;
                let startX = 0, startY = 0, startTime = 0;

                function getDeviceCoords(e) {
                    const img = document.getElementById('screen-img');
                    const rect = img.getBoundingClientRect();
                    const scaleX = (img.naturalWidth || screenNaturalWidth) / rect.width;
                    const scaleY = (img.naturalHeight || screenNaturalHeight) / rect.height;
                    const x = Math.max(0, Math.min(Math.round((e.clientX - rect.left) * scaleX), (img.naturalWidth || screenNaturalWidth)));
                    const y = Math.max(0, Math.min(Math.round((e.clientY - rect.top) * scaleY), (img.naturalHeight || screenNaturalHeight)));
                    return { x, y };
                }

                screenBox.addEventListener('mousemove', (e) => {
                    const { x, y } = getDeviceCoords(e);
                    coordsPill.textContent = 'X: ' + x + ', Y: ' + y;
                });

                screenBox.addEventListener('mousedown', (e) => {
                    isDragging = true;
                    const coords = getDeviceCoords(e);
                    startX = coords.x;
                    startY = coords.y;
                    startTime = Date.now();
                });

                window.addEventListener('mouseup', async (e) => {
                    if (!isDragging) return;
                    isDragging = false;
                    const coords = getDeviceCoords(e);
                    const endX = coords.x;
                    const endY = coords.y;
                    const elapsed = Date.now() - startTime;
                    const dist = Math.hypot(endX - startX, endY - startY);

                    if (dist > 25) {
                        document.getElementById('quick-output').textContent = '正在执行滑动：(' + startX + ',' + startY + ') ➔ (' + endX + ',' + endY + ')...';
                        await callTool('swipe', { x1: startX, y1: startY, x2: endX, y2: endY, duration_ms: Math.max(250, elapsed) });
                    } else {
                        document.getElementById('quick-output').textContent = '正在点击坐标 (' + startX + ',' + startY + ')...';
                        await callTool('tap', { x: startX, y: startY });
                    }
                    setTimeout(() => refreshScreen(currentSom), 500);
                });

                // Device info update
                async function loadDeviceInfo() {
                    try {
                        const res = await fetch('/api/status');
                        const data = await res.json();
                        const dev = data.device || {};
                        const priv = data.privileges || {};
                        screenNaturalWidth = dev.screenWidth || 720;
                        screenNaturalHeight = dev.screenHeight || 1280;

                        const bar = document.getElementById('device-info-bar');
                        bar.innerHTML = 
                            '<span class="badge badge-blue">📱 ' + (dev.brand || '') + ' ' + (dev.model || 'Android') + ' (Android ' + (dev.androidVersion || '') + ')</span>' +
                            '<span class="badge badge-gray">⚡ ' + (dev.batteryLevel || 0) + '% ' + (dev.isCharging ? '充电中' : '') + '</span>' +
                            '<span class="badge ' + (priv.lsposedActive ? 'badge-green' : 'badge-gray') + '">LSPosed: ' + (priv.lsposedActive ? '已激活' : '未激活') + '</span>' +
                            '<span class="badge ' + (priv.rootAvailable ? 'badge-green' : 'badge-gray') + '">Root: ' + (priv.rootAvailable ? '已授权' : '未授权') + '</span>' +
                            '<span class="badge ' + (priv.shizukuRunning ? 'badge-green' : 'badge-gray') + '">Shizuku: ' + (priv.shizukuRunning ? '运行中' : '未运行') + '</span>' +
                            '<span class="badge ' + (priv.notificationActive ? 'badge-green' : 'badge-gray') + '">通知监听: ' + (priv.notificationActive ? '已激活' : '未开启') + '</span>';
                    } catch (e) {
                        console.error('Failed to load device info', e);
                    }
                }

                // Quick actions
                async function pressKey(key) {
                    document.getElementById('quick-output').textContent = '正在按下按键: ' + key + '...';
                    const res = await callTool('press_key', { key });
                    document.getElementById('quick-output').textContent = JSON.stringify(res, null, 2);
                    setTimeout(() => refreshScreen(currentSom), 400);
                }

                async function sendInputText() {
                    const text = document.getElementById('quick-input-text').value;
                    if (!text) return;
                    document.getElementById('quick-output').textContent = '正在输入文本: ' + text + '...';
                    const res = await callTool('input_text', { text });
                    document.getElementById('quick-output').textContent = JSON.stringify(res, null, 2);
                    document.getElementById('quick-input-text').value = '';
                    setTimeout(() => refreshScreen(currentSom), 400);
                }

                async function launchApp(pkg) {
                    if (!pkg) return;
                    document.getElementById('quick-output').textContent = '正在启动应用: ' + pkg + '...';
                    const res = await callTool('launch_app', { package_name: pkg });
                    document.getElementById('quick-output').textContent = JSON.stringify(res, null, 2);
                    setTimeout(() => refreshScreen(currentSom), 1000);
                }

                async function stopApp(pkg) {
                    if (!pkg) return;
                    document.getElementById('quick-output').textContent = '正在停止应用: ' + pkg + '...';
                    const res = await callTool('stop_app', { package_name: pkg });
                    document.getElementById('quick-output').textContent = JSON.stringify(res, null, 2);
                    setTimeout(() => refreshScreen(currentSom), 600);
                }

                async function swipeDirection(dir) {
                    const w = screenNaturalWidth, h = screenNaturalHeight;
                    let x1 = w / 2, y1 = h / 2, x2 = w / 2, y2 = h / 2;
                    if (dir === 'up') { y1 = h * 0.75; y2 = h * 0.25; }
                    else if (dir === 'down') { y1 = h * 0.25; y2 = h * 0.75; }
                    else if (dir === 'left') { x1 = w * 0.8; x2 = w * 0.2; }
                    else if (dir === 'right') { x1 = w * 0.2; x2 = w * 0.8; }
                    await callTool('swipe', { x1, y1, x2, y2, duration_ms: 300 });
                    setTimeout(() => refreshScreen(currentSom), 500);
                }

                // UI Tree tab
                async function loadUiTree() {
                    const container = document.getElementById('tree-list-container');
                    container.innerHTML = '<div style="color: var(--text-muted); padding: 20px; text-align: center;">正在提取 UI 树...</div>';
                    const res = await callTool('get_ui_hierarchy', { format: 'json' });
                    try {
                        const content = res.result.content[0].text;
                        rawTreeData = JSON.parse(content);
                        renderUiTreeList(rawTreeData);
                    } catch (e) {
                        container.innerHTML = '<div style="color: var(--accent-red); padding: 20px;">提取失败: ' + e.message + '</div>';
                    }
                }

                function flattenNodes(node, list = []) {
                    if (!node) return list;
                    if (node.id > 0 || node.clickable || node.text || node.description) {
                        list.push(node);
                    }
                    if (node.children) {
                        for (const child of node.children) flattenNodes(child, list);
                    }
                    return list;
                }

                function renderUiTreeList(tree) {
                    const container = document.getElementById('tree-list-container');
                    const items = flattenNodes(tree);
                    if (items.length === 0) {
                        container.innerHTML = '<div style="color: var(--text-muted); padding: 20px; text-align: center;">未检测到可交互元素</div>';
                        return;
                    }
                    container.innerHTML = items.map(item => {
                        const type = (item.className || 'View').split('.').pop();
                        const text = item.text || item.description || '';
                        const bounds = item.bounds ? '[' + item.bounds.left + ',' + item.bounds.top + '][' + item.bounds.right + ',' + item.bounds.bottom + ']' : '';
                        const cx = item.bounds ? item.bounds.left + Math.round((item.bounds.right - item.bounds.left) / 2) : 0;
                        const cy = item.bounds ? item.bounds.top + Math.round((item.bounds.bottom - item.bounds.top) / 2) : 0;
                        return '<div class="tree-item" onclick="tapElement(' + cx + ',' + cy + ')">' +
                            '<div>' +
                                '<span class="tree-tag">#' + item.id + '</span>' +
                                '<b>' + type + '</b> ' +
                                (text ? '<span style="color: var(--accent-cyan);">"' + escapeHtml(text.substring(0, 35)) + '"</span>' : '') +
                                (item.clickable ? ' <span style="color: var(--accent-green); font-size: 11px;">[可点击]</span>' : '') +
                            '</div>' +
                            '<div class="tree-bounds">' + bounds + '</div>' +
                        '</div>';
                    }).join('');
                }

                async function tapElement(x, y) {
                    await callTool('tap', { x, y });
                    setTimeout(() => { refreshScreen(currentSom); loadUiTree(); }, 500);
                }

                function filterUiTree() {
                    if (!rawTreeData) return;
                    const query = document.getElementById('tree-search-box').value.toLowerCase();
                    const allItems = flattenNodes(rawTreeData);
                    const filtered = allItems.filter(item => {
                        return (item.text && item.text.toLowerCase().includes(query)) ||
                               (item.description && item.description.toLowerCase().includes(query)) ||
                               (item.className && item.className.toLowerCase().includes(query)) ||
                               (item.id.toString() === query);
                    });
                    const container = document.getElementById('tree-list-container');
                    container.innerHTML = filtered.map(item => {
                        const type = (item.className || 'View').split('.').pop();
                        const text = item.text || item.description || '';
                        const bounds = item.bounds ? '[' + item.bounds.left + ',' + item.bounds.top + '][' + item.bounds.right + ',' + item.bounds.bottom + ']' : '';
                        const cx = item.bounds ? item.bounds.left + Math.round((item.bounds.right - item.bounds.left) / 2) : 0;
                        const cy = item.bounds ? item.bounds.top + Math.round((item.bounds.bottom - item.bounds.top) / 2) : 0;
                        return '<div class="tree-item" onclick="tapElement(' + cx + ',' + cy + ')">' +
                            '<div><span class="tree-tag">#' + item.id + '</span><b>' + type + '</b> ' +
                            (text ? '<span style="color: var(--accent-cyan);">"' + escapeHtml(text.substring(0, 35)) + '"</span>' : '') + '</div>' +
                            '<div class="tree-bounds">' + bounds + '</div>' +
                        '</div>';
                    }).join('');
                }

                // File Manager Tab
                async function loadFsDir(dirPath = currentFsPath) {
                    currentFsPath = (dirPath || '/sdcard').trim();
                    document.getElementById('fs-path-input').value = currentFsPath;
                    const tbody = document.getElementById('fs-table-body');
                    tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 20px; color: var(--text-muted);">正在加载文件列表...</td></tr>';
                    try {
                        const res = await fetch('/api/fs/list?path=' + encodeURIComponent(currentFsPath));
                        const items = await res.json();
                        if (!items || items.length === 0) {
                            tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 20px; color: var(--text-muted);">目录为空</td></tr>';
                            return;
                        }
                        tbody.innerHTML = items.map(item => {
                            const icon = item.isDir ? '📁' : '📄';
                            const sizeStr = item.isDir ? '-' : formatBytes(item.size);
                            const clickAction = item.isDir ? 'onclick="loadFsDir(\'' + escapeJs(item.path) + '\')"' : '';
                            const nameStyle = item.isDir ? 'cursor: pointer; color: var(--accent-cyan); font-weight: 600;' : 'color: var(--text-primary);';
                            const downloadBtn = !item.isDir ? '<a href="/api/fs/download?path=' + encodeURIComponent(item.path) + '" class="btn btn-secondary" style="font-size: 11px; padding: 2px 7px; text-decoration: none;" download>⬇️ 下载</a>' : '';
                            const deleteBtn = '<button class="btn btn-danger" style="font-size: 11px; padding: 2px 7px;" onclick="deleteFsItem(\'' + escapeJs(item.path) + '\')">🗑️</button>';
                            return '<tr>' +
                                '<td style="' + nameStyle + '" ' + clickAction + '>' + icon + ' ' + escapeHtml(item.name) + '</td>' +
                                '<td style="font-size: 12px; color: var(--text-secondary);">' + (item.isDir ? '目录' : '文件') + '</td>' +
                                '<td style="font-size: 12px; font-family: monospace;">' + sizeStr + '</td>' +
                                '<td style="font-size: 11px; font-family: monospace; color: var(--text-muted);">' + escapeHtml(item.permissions) + '</td>' +
                                '<td style="display: flex; gap: 6px;">' + downloadBtn + ' ' + deleteBtn + '</td>' +
                            '</tr>';
                        }).join('');
                    } catch (e) {
                        tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 20px; color: var(--accent-red);">加载失败: ' + e.message + '</td></tr>';
                    }
                }

                function goFsUp() {
                    if (currentFsPath === '/' || !currentFsPath.includes('/')) return;
                    let parent = currentFsPath.substring(0, currentFsPath.lastIndexOf('/'));
                    if (!parent) parent = '/';
                    loadFsDir(parent);
                }

                async function deleteFsItem(path) {
                    if (!confirm('确定要删除 ' + path + ' 吗？')) return;
                    try {
                        const res = await fetch('/api/fs/delete?path=' + encodeURIComponent(path), { method: 'POST' });
                        const json = await res.json();
                        if (json.success) {
                            loadFsDir(currentFsPath);
                        } else {
                            alert('删除失败');
                        }
                    } catch (e) {
                        alert('删除出错: ' + e.message);
                    }
                }

                function triggerFsUpload() {
                    document.getElementById('fs-file-input').click();
                }

                async function handleFsFileUpload(event) {
                    const file = event.target.files[0];
                    if (!file) return;
                    const cleanPath = currentFsPath.endsWith('/') ? currentFsPath : currentFsPath + '/';
                    const targetPath = cleanPath + file.name;
                    const statusSpan = document.getElementById('fs-upload-status');
                    statusSpan.textContent = '正在上传 ' + file.name + ' (' + formatBytes(file.size) + ')...';
                    try {
                        const buffer = await file.arrayBuffer();
                        const res = await fetch('/api/fs/upload?path=' + encodeURIComponent(targetPath), {
                            method: 'POST',
                            body: buffer
                        });
                        const json = await res.json();
                        if (json.success) {
                            statusSpan.textContent = '✅ 上传完成: ' + file.name;
                            loadFsDir(currentFsPath);
                        } else {
                            statusSpan.textContent = '❌ 上传失败';
                        }
                    } catch (e) {
                        statusSpan.textContent = '❌ 上传错误: ' + e.message;
                    }
                    setTimeout(() => { statusSpan.textContent = ''; }, 4000);
                    event.target.value = '';
                }

                // Automation Macro Tab & Toolbar
                async function toggleMacroRecording() {
                    if (!isMacroRecording) {
                        const res = await fetch('/api/macro/record/start', { method: 'POST' });
                        const json = await res.json();
                        if (json.success) {
                            isMacroRecording = true;
                            updateMacroUi();
                        }
                    } else {
                        const defaultName = 'macro_' + new Date().toISOString().slice(11, 19).replace(/:/g, '');
                        const name = prompt('请输入要保存的宏名称 (留空则不持久化):', defaultName);
                        const url = '/api/macro/record/stop' + (name ? '?save_name=' + encodeURIComponent(name) : '');
                        const res = await fetch(url, { method: 'POST' });
                        const json = await res.json();
                        isMacroRecording = false;
                        updateMacroUi();
                        alert('录制完成，已录制 ' + json.count + ' 步操作！');
                        loadMacrosList();
                    }
                }

                function updateMacroUi() {
                    const recBadge = document.getElementById('macro-rec-badge');
                    const quickRecBtn = document.getElementById('quick-rec-btn');
                    const tabRecBtn = document.getElementById('tab-macro-rec-btn');
                    if (isMacroRecording) {
                        if (recBadge) recBadge.style.display = 'inline-flex';
                        if (quickRecBtn) { quickRecBtn.style.background = '#dc2626'; quickRecBtn.textContent = '⏹️ 停止录制'; }
                        if (tabRecBtn) { tabRecBtn.style.background = '#dc2626'; tabRecBtn.textContent = '⏹️ 停止录制并保存'; }
                    } else {
                        if (recBadge) recBadge.style.display = 'none';
                        if (quickRecBtn) { quickRecBtn.style.background = '#1e293b'; quickRecBtn.textContent = '🔴 录制宏'; }
                        if (tabRecBtn) { tabRecBtn.style.background = '#dc2626'; tabRecBtn.textContent = '🔴 开始录制宏'; }
                    }
                }

                async function loadMacrosList() {
                    try {
                        const res = await fetch('/api/macro/list');
                        const list = await res.json();
                        const tbody = document.getElementById('macro-table-body');
                        const quickSelect = document.getElementById('quick-macro-select');
                        if (quickSelect) {
                            quickSelect.innerHTML = '<option value="">选择宏...</option>' + list.map(m => '<option value="' + escapeHtml(m.name) + '">' + escapeHtml(m.name) + ' (' + m.stepCount + '步)</option>').join('');
                        }
                        if (tbody) {
                            if (!list || list.length === 0) {
                                tbody.innerHTML = '<tr><td colspan="3" style="text-align: center; padding: 15px; color: var(--text-muted);">暂无保存的宏</td></tr>';
                                return;
                            }
                            tbody.innerHTML = list.map(m => 
                                '<tr>' +
                                    '<td style="font-weight: 600; color: var(--accent-cyan);">' + escapeHtml(m.name) + '</td>' +
                                    '<td style="font-size: 12px; color: var(--text-secondary);">' + m.stepCount + ' 步</td>' +
                                    '<td style="display: flex; gap: 8px;">' +
                                        '<button class="btn btn-success" style="font-size: 11px; padding: 3px 8px;" onclick="runMacro(\'' + escapeJs(m.name) + '\')">▶️ 回放</button>' +
                                        '<button class="btn btn-danger" style="font-size: 11px; padding: 3px 8px;" onclick="deleteMacro(\'' + escapeJs(m.name) + '\')">🗑️ 删除</button>' +
                                    '</td>' +
                                '</tr>'
                            ).join('');
                        }
                    } catch (e) {
                        console.error('Failed to load macros', e);
                    }
                }

                async function runMacro(name) {
                    if (!name) return;
                    document.getElementById('macro-run-output').textContent = '正在执行宏 [' + name + ']...';
                    const res = await fetch('/api/macro/run?name=' + encodeURIComponent(name), { method: 'POST' });
                    const json = await res.json();
                    document.getElementById('macro-run-output').textContent = json.log || JSON.stringify(json, null, 2);
                    setTimeout(() => refreshScreen(currentSom), 500);
                }

                async function runQuickMacro() {
                    const sel = document.getElementById('quick-macro-select');
                    if (!sel || !sel.value) { alert('请先在下拉列表中选择要回放的宏'); return; }
                    runMacro(sel.value);
                }

                async function deleteMacro(name) {
                    if (!confirm('确定要删除宏 ' + name + ' 吗？')) return;
                    await callTool('manage_macro', { action: 'delete', name });
                    loadMacrosList();
                }

                function loadMacroPreset(type) {
                    const editor = document.getElementById('batch-actions-editor');
                    if (type === 'unlock') {
                        editor.value = JSON.stringify([
                            { action: "press_key", key: "POWER" },
                            { action: "wait", duration_ms: 500 },
                            { action: "swipe", x1: 360, y1: 1000, x2: 360, y2: 300, duration_ms: 300 },
                            { action: "wait", duration_ms: 400 },
                            { action: "press_key", key: "HOME" }
                        ], null, 2);
                    } else if (type === 'settings') {
                        editor.value = JSON.stringify([
                            { action: "launch_app", package_name: "com.android.settings" },
                            { action: "wait", duration_ms: 1200 },
                            { action: "swipe", x1: 360, y1: 800, x2: 360, y2: 300, duration_ms: 350 },
                            { action: "wait", duration_ms: 500 },
                            { action: "press_key", key: "BACK" }
                        ], null, 2);
                    }
                }

                async function runBatchActions() {
                    const editor = document.getElementById('batch-actions-editor');
                    let actions = [];
                    try {
                        actions = JSON.parse(editor.value);
                    } catch (e) {
                        alert('动作流 JSON 解析失败: ' + e.message);
                        return;
                    }
                    document.getElementById('macro-run-output').textContent = '正在执行动作流序列...';
                    const res = await callTool('run_actions', { actions, default_delay_ms: 300 });
                    document.getElementById('macro-run-output').textContent = JSON.stringify(res, null, 2);
                    setTimeout(() => refreshScreen(currentSom), 500);
                }

                // Shell Tab
                async function executeShellCommand() {
                    const cmd = document.getElementById('shell-cmd-input').value;
                    if (!cmd) return;
                    const asRoot = document.getElementById('shell-as-root').checked;
                    document.getElementById('shell-output').textContent = '$ ' + cmd + '\n正在执行中...';
                    const res = await callTool('execute_shell', { command: cmd, as_root: asRoot });
                    try {
                        const text = res.result.content[0].text;
                        document.getElementById('shell-output').textContent = '$ ' + cmd + '\n' + text;
                    } catch (e) {
                        document.getElementById('shell-output').textContent = JSON.stringify(res, null, 2);
                    }
                }

                function runShellPreset(cmd) {
                    document.getElementById('shell-cmd-input').value = cmd;
                    executeShellCommand();
                }

                // MCP Tool Playground Tab
                const toolTemplates = {
                    get_device_info: {},
                    get_activity_stack: { max_tasks: 10 },
                    get_ui_hierarchy: { format: "compact_text" },
                    capture_screenshot: { quality: 80, annotate_som: false },
                    tap: { x: 360, y: 640 },
                    swipe: { x1: 360, y1: 900, x2: 360, y2: 300, duration_ms: 300 },
                    long_press: { x: 360, y: 640, duration_ms: 800 },
                    drag_and_drop: { x1: 360, y1: 800, x2: 360, y2: 300, hold_ms: 400, duration_ms: 500 },
                    input_text: { text: "Hello MCP" },
                    press_key: { key: "HOME" },
                    launch_app: { package_name: "com.android.settings" },
                    stop_app: { package_name: "com.android.settings" },
                    clear_app_data: { package_name: "com.android.settings" },
                    install_apk: { file_path: "/data/local/tmp/app.apk", grant_permissions: true },
                    pull_apk: { package_name: "com.wzvideni.androidmcp", destination_path: "/sdcard/Download/app.apk" },
                    execute_shell: { command: "id", as_root: true },
                    get_recent_logs: { lines: 50, tag: "AndroidMCP" },
                    get_notifications: { limit: 20 },
                    wait_for_notification: { text_contains: "验证码", timeout_ms: 15000 },
                    wait_for_element: { text: "设置", timeout_ms: 5000, condition: "present", auto_click: false },
                    click_by_selector: { text: "设置" },
                    manage_clipboard: { action: "get" },
                    system_control: { action: "keep_alive_whitelist", package_name: "com.wzvideni.androidmcp" },
                    system_file_ops: { action: "list", path: "/sdcard" },
                    send_intent: { action: "android.intent.action.VIEW", uri: "https://github.com" },
                    hook_inspect_activity: { package_name: "com.android.settings" },
                    hook_get_fragments: { package_name: "com.android.settings" },
                    hook_call_method: { package_name: "com.android.settings", method_name: "toString", params: [] },
                    hook_set_field: { package_name: "com.android.settings", class_name: "MainActivity", field_name: "myField", field_value: "test" },
                    hook_get_view_tree: { package_name: "com.android.settings" },
                    hook_dump_sqlite: { package_name: "com.wzvideni.androidmcp", db_name: "app.db" },
                    hook_dump_shared_prefs: { package_name: "com.wzvideni.androidmcp", pref_name: "settings" },
                    hook_trace_method: { action: "start", package_name: "com.android.settings", class_name: "com.android.settings.SettingsActivity", method_name: "onCreate", capture_args: true, capture_return: true },
                    run_actions: {
                        actions: [
                            { action: "tap", x: 360, y: 640 },
                            { action: "wait", duration_ms: 500 },
                            { action: "press_key", key: "BACK" }
                        ],
                        default_delay_ms: 300
                    },
                    manage_macro: { action: "list" }
                };

                function onToolSelected(toolName) {
                    const tpl = toolTemplates[toolName] || {};
                    document.getElementById('mcp-args-editor').value = JSON.stringify(tpl, null, 2);
                }

                async function runSelectedMcpTool() {
                    const toolName = document.getElementById('mcp-tool-select').value;
                    const argsStr = document.getElementById('mcp-args-editor').value;
                    let args = {};
                    try { args = JSON.parse(argsStr); } catch (e) { alert('参数 JSON 解析错误: ' + e.message); return; }
                    document.getElementById('mcp-response-output').textContent = '正在发送请求...';
                    const res = await callTool(toolName, args);
                    document.getElementById('mcp-response-output').textContent = JSON.stringify(res, null, 2);
                }

                // Logcat Tab
                async function fetchLogcat() {
                    const filter = document.getElementById('logcat-filter').value;
                    const lines = parseInt(document.getElementById('logcat-lines').value) || 60;
                    document.getElementById('logcat-output').textContent = '正在抓取 Logcat 日志...';
                    const res = await callTool('get_recent_logs', { lines, filter: filter || undefined });
                    try {
                        const text = res.result.content[0].text;
                        document.getElementById('logcat-output').textContent = text || '未抓取到匹配日志';
                    } catch (e) {
                        document.getElementById('logcat-output').textContent = JSON.stringify(res, null, 2);
                    }
                }

                // Utility functions
                function formatBytes(bytes) {
                    if (!bytes || bytes <= 0) return '0 B';
                    const k = 1024;
                    const sizes = ['B', 'KB', 'MB', 'GB'];
                    const i = Math.floor(Math.log(bytes) / Math.log(k));
                    return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
                }

                function escapeHtml(str) {
                    return (str || '').toString().replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
                }

                function escapeJs(str) {
                    return (str || '').toString().replace(/\\/g, '\\\\').replace(/'/g, "\\'");
                }

                // Initial Load
                loadDeviceInfo();
                loadMacrosList();
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}

