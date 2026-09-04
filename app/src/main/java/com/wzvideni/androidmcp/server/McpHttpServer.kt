package com.wzvideni.androidmcp.server

import android.content.Context
import android.util.Log
import com.wzvideni.androidmcp.engine.PrivilegeManager
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

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
                    padding: 14px 18px;
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
                    gap: 14px;
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
                    font-size: 13px;
                    font-weight: 500;
                    padding: 6px 12px;
                    border-radius: 8px;
                    cursor: pointer;
                    display: flex;
                    align-items: center;
                    gap: 4px;
                    transition: all 0.2s;
                }
                .nav-btn:hover { background: #334155; color: var(--text-primary); }
                
                .btn-group { display: flex; gap: 8px; flex-wrap: wrap; }
                .btn {
                    background: var(--accent-blue);
                    color: white;
                    border: none;
                    padding: 7px 14px;
                    border-radius: 8px;
                    font-size: 13px;
                    font-weight: 600;
                    cursor: pointer;
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                    transition: all 0.15s ease;
                }
                .btn:hover { filter: brightness(1.15); transform: translateY(-1px); }
                .btn:active { transform: translateY(0); }
                .btn-secondary { background: #1e293b; color: var(--text-primary); border: 1px solid var(--border-light); }
                .btn-secondary:hover { background: #334155; }
                .btn-success { background: #059669; }
                .btn-danger { background: #dc2626; }
                
                /* Tabs */
                .tabs-header {
                    display: flex;
                    border-bottom: 1px solid var(--border-color);
                    background: var(--bg-card-header);
                    overflow-x: auto;
                }
                .tab-btn {
                    padding: 12px 18px;
                    background: transparent;
                    border: none;
                    border-bottom: 2px solid transparent;
                    color: var(--text-secondary);
                    font-size: 14px;
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
                    padding: 8px 12px;
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
                    max-height: 480px;
                    white-space: pre-wrap;
                    word-break: break-all;
                }
                
                .tree-item {
                    background: #0e1626;
                    border: 1px solid var(--border-color);
                    border-radius: 8px;
                    padding: 10px 12px;
                    margin-bottom: 8px;
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
            </style>
        </head>
        <body>
            <header>
                <div class="logo-box">
                    <span class="logo-title">🤖 AndroidMCP 控制台</span>
                    <span class="badge badge-green">● 局域网在线</span>
                    <span class="badge badge-blue">端口 $port</span>
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
                            <button class="btn btn-secondary" onclick="refreshScreen(false)">🔄 刷新</button>
                            <button class="btn btn-secondary" id="som-btn" onclick="toggleSom()">🏷️ SoM 标注</button>
                            <select id="auto-refresh-select" onchange="changeAutoRefresh(this.value)" style="background: #1e293b; color: white; border: 1px solid #334155; border-radius: 6px; padding: 4px 8px; font-size: 12px;">
                                <option value="0">自动: 关</option>
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
                                <button class="nav-btn" onclick="pressKey('VOLUME_UP')">🔊 音量+</button>
                                <button class="nav-btn" onclick="pressKey('VOLUME_DOWN')">🔉 音量-</button>
                            </div>
                        </div>
                        <p style="font-size: 12px; color: var(--text-muted); text-align: center;">
                            💡 提示：在屏幕上<b>单击</b>触发点击，<b>按住拖动</b>触发滑动
                        </p>
                    </div>
                </div>

                <!-- Right Super Console Tabs -->
                <div class="card">
                    <div class="tabs-header">
                        <button class="tab-btn active" onclick="switchTab('quick')">⚡ 快捷控制</button>
                        <button class="tab-btn" onclick="switchTab('tree')">🌲 UI 布局树</button>
                        <button class="tab-btn" onclick="switchTab('shell')">💻 特权终端</button>
                        <button class="tab-btn" onclick="switchTab('playground')">🛠️ MCP 工具台</button>
                        <button class="tab-btn" onclick="switchTab('logcat')">📜 实时日志</button>
                    </div>

                    <div class="card-body">
                        <!-- Tab 1: 快捷控制 -->
                        <div id="tab-quick" class="tab-content active">
                            <div style="margin-bottom: 16px;">
                                <label style="font-size: 13px; font-weight: 600; color: var(--text-secondary); margin-bottom: 6px; display: block;">⌨️ 快速输入文字</label>
                                <div style="display: flex; gap: 8px;">
                                    <input id="quick-input-text" class="input-box" placeholder="输入文字（支持中文、英文、符号）..." onkeydown="if(event.key==='Enter') sendInputText()" />
                                    <button class="btn" onclick="sendInputText()">发送 (Enter)</button>
                                </div>
                            </div>

                            <div style="margin-bottom: 16px;">
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

                            <div style="margin-bottom: 16px;">
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

                        <!-- Tab 3: 特权终端 -->
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

                        <!-- Tab 4: MCP 工具台 -->
                        <div id="tab-playground" class="tab-content">
                            <div style="display: flex; gap: 8px; margin-bottom: 12px; align-items: center;">
                                <select id="mcp-tool-select" class="input-box" style="width: 260px;" onchange="onToolSelected(this.value)">
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
                                    <option value="hook_inspect_activity">hook_inspect_activity</option>
                                    <option value="hook_get_fragments">hook_get_fragments</option>
                                    <option value="hook_call_method">hook_call_method</option>
                                    <option value="hook_set_field">hook_set_field</option>
                                    <option value="hook_get_view_tree">hook_get_view_tree</option>
                                    <option value="hook_trace_method">hook_trace_method</option>
                                </select>
                                <button class="btn" onclick="runSelectedMcpTool()">🚀 发送 JSON-RPC 调用</button>
                            </div>
                            <div style="margin-bottom: 8px;">
                                <label style="font-size: 12px; color: var(--text-secondary);">参数 JSON (arguments):</label>
                                <textarea id="mcp-args-editor" class="input-box" style="font-family: monospace; height: 110px; margin-top: 4px;">{}</textarea>
                            </div>
                            <pre class="console-output" id="mcp-response-output">{}</pre>
                        </div>

                        <!-- Tab 5: 实时日志 -->
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
                let rawTreeData = null;
                let screenNaturalWidth = 720;
                let screenNaturalHeight = 1280;

                // Tab switching
                function switchTab(tabName) {
                    document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
                    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                    event.currentTarget.classList.add('active');
                    document.getElementById('tab-' + tabName).classList.add('active');
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

                // Screen refresh & Touch/Swipe handling
                function refreshScreen(som = currentSom) {
                    const img = document.getElementById('screen-img');
                    img.src = '/api/screenshot?som=' + som + '&t=' + Date.now();
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
                    refreshScreen(currentSom);
                }

                function changeAutoRefresh(intervalMs) {
                    if (autoRefreshTimer) clearInterval(autoRefreshTimer);
                    const ms = parseInt(intervalMs);
                    if (ms > 0) {
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
                            '<span class="badge ' + (priv.shizukuRunning ? 'badge-green' : 'badge-gray') + '">Shizuku/Sui: ' + (priv.shizukuRunning ? '运行中' : '未运行') + '</span>' +
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
                                (text ? '<span style="color: var(--accent-cyan);">"' + text.substring(0, 35) + '"</span>' : '') +
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
                            (text ? '<span style="color: var(--accent-cyan);">"' + text.substring(0, 35) + '"</span>' : '') + '</div>' +
                            '<div class="tree-bounds">' + bounds + '</div>' +
                        '</div>';
                    }).join('');
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
                    hook_inspect_activity: { package_name: "com.android.settings" },
                    hook_get_fragments: { package_name: "com.android.settings" },
                    hook_call_method: { package_name: "com.android.settings", method_name: "toString", params: [] },
                    hook_set_field: { package_name: "com.android.settings", class_name: "MainActivity", field_name: "myField", field_value: "test" },
                    hook_get_view_tree: { package_name: "com.android.settings" },
                    hook_trace_method: { action: "start", package_name: "com.android.settings", class_name: "com.android.settings.SettingsActivity", method_name: "onCreate", capture_args: true, capture_return: true }
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

                // Initial Load
                loadDeviceInfo();
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
