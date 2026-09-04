package com.wzvideni.androidmcp.mcp

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.util.Log
import com.wzvideni.androidmcp.engine.InputController
import com.wzvideni.androidmcp.engine.MacroManager
import com.wzvideni.androidmcp.engine.McpAccessibilityService
import com.wzvideni.androidmcp.engine.PrivilegeManager
import com.wzvideni.androidmcp.engine.RootBridge
import com.wzvideni.androidmcp.engine.ShizukuBridge
import com.wzvideni.androidmcp.hook.HookClientManager
import com.wzvideni.androidmcp.model.CallToolResult
import com.wzvideni.androidmcp.model.ContentItem
import com.wzvideni.androidmcp.model.GetPromptResult
import com.wzvideni.androidmcp.model.HookIpcRequest
import com.wzvideni.androidmcp.model.InitializeResult
import com.wzvideni.androidmcp.model.JsonRpcError
import com.wzvideni.androidmcp.model.JsonRpcRequest
import com.wzvideni.androidmcp.model.JsonRpcResponse
import com.wzvideni.androidmcp.model.ListPromptsResult
import com.wzvideni.androidmcp.model.ListResourcesResult
import com.wzvideni.androidmcp.model.ListToolsResult
import com.wzvideni.androidmcp.model.Prompt
import com.wzvideni.androidmcp.model.PromptMessage
import com.wzvideni.androidmcp.model.ReadResourceResult
import com.wzvideni.androidmcp.model.RectBounds
import com.wzvideni.androidmcp.model.Resource
import com.wzvideni.androidmcp.model.ResourceContent
import com.wzvideni.androidmcp.model.Tool
import com.wzvideni.androidmcp.model.ToolInputSchema
import com.wzvideni.androidmcp.model.UiNode
import com.wzvideni.androidmcp.model.jsonConfig
import com.wzvideni.androidmcp.model.NotificationItem
import com.wzvideni.androidmcp.notification.McpNotificationListenerService
import com.wzvideni.androidmcp.vision.ScreenCapturer
import com.wzvideni.androidmcp.vision.SetOfMarkAnnotator
import com.wzvideni.androidmcp.vision.UiTreeFlattener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.ArrayDeque

class McpProtocolHandler(
    val context: Context,
    val privilegeManager: PrivilegeManager,
    val clipboardManager: ClipboardManager
) {

    companion object {
        private const val TAG = "McpProtocolHandler"
        private val BOUNDS_REGEX = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")
    }

    private var lastDumpedTree: UiNode? = null

    suspend fun handleJsonRpc(request: JsonRpcRequest): JsonRpcResponse? {
        // Notifications (JSON-RPC requests without id) MUST NEVER receive a response
        if (request.id == null || request.method.startsWith("notifications/")) {
            when (request.method) {
                "notifications/initialized", "initialized" -> {
                    Log.i(TAG, "Client sent initialized notification")
                }
                "notifications/cancelled", "$/cancelRequest" -> {
                    Log.i(TAG, "Client sent cancel request notification: ${request.params}")
                }
                else -> {
                    Log.d(TAG, "Received notification: ${request.method}")
                }
            }
            return null
        }

        return try {
            when (request.method) {
                "initialize" -> {
                    val result = InitializeResult()
                    JsonRpcResponse(id = request.id, result = jsonConfig.encodeToJsonElement(result))
                }
                "ping" -> {
                    JsonRpcResponse(id = request.id, result = JsonPrimitive("pong"))
                }
                "tools/list" -> {
                    val toolsResult = ListToolsResult(tools = getAvailableTools())
                    JsonRpcResponse(id = request.id, result = jsonConfig.encodeToJsonElement(toolsResult))
                }
                "tools/call" -> {
                    val params = request.params ?: throw IllegalArgumentException("Missing params for tools/call")
                    val toolName = params["name"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing tool name")
                    val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

                    val callResult = executeTool(toolName, arguments)
                    JsonRpcResponse(id = request.id, result = jsonConfig.encodeToJsonElement(callResult))
                }
                "resources/list" -> {
                    val resources = getAvailableResources()
                    JsonRpcResponse(
                        id = request.id,
                        result = jsonConfig.encodeToJsonElement(ListResourcesResult(resources))
                    )
                }
                "resources/read" -> {
                    val params = request.params ?: throw IllegalArgumentException("Missing params for resources/read")
                    val uri = params["uri"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing uri")
                    val readResult = readResource(uri)
                    JsonRpcResponse(id = request.id, result = jsonConfig.encodeToJsonElement(readResult))
                }
                "prompts/list" -> {
                    val prompts = getAvailablePrompts()
                    JsonRpcResponse(
                        id = request.id,
                        result = jsonConfig.encodeToJsonElement(ListPromptsResult(prompts))
                    )
                }
                "prompts/get" -> {
                    val params = request.params ?: throw IllegalArgumentException("Missing params for prompts/get")
                    val name = params["name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing prompt name")
                    val promptResult = getPrompt(name)
                    JsonRpcResponse(id = request.id, result = jsonConfig.encodeToJsonElement(promptResult))
                }
                else -> {
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(
                            code = -32601,
                            message = "Method not found: ${request.method}"
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error handling RPC method ${request.method}: ${e.message}", e)
            JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(
                    code = -32000,
                    message = e.message ?: "Internal error",
                    data = JsonPrimitive(e.stackTraceToString())
                )
            )
        }
    }

    private fun getAvailableTools(): List<Tool> {
        return listOf(
            Tool(
                name = "get_ui_hierarchy",
                description = "Extract current screen UI hierarchy tree. Automatically leverages in-process LSPosed hook for maximum depth (Jetpack Compose & internal View state), falling back to Accessibility UI tree. Outputs compact token-optimized format or full JSON.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("format", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("compact_text"))
                                add(JsonPrimitive("json"))
                            })
                            put("description", JsonPrimitive("Output format: 'compact_text' (default, token-efficient with Set-of-Mark IDs) or 'json'"))
                        })
                    }
                )
            ),
            Tool(
                name = "capture_screenshot",
                description = "Capture current screen image. Optionally draws Set-of-Mark (SoM) bounding boxes with numeric IDs on interactive elements.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("annotate_som", buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Whether to draw numeric element badges (Set-of-Mark) corresponding to get_ui_hierarchy IDs"))
                        })
                        put("quality", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("JPEG compression quality (1-100), default 80"))
                        })
                    }
                )
            ),
            Tool(
                name = "tap",
                description = "Tap on screen by (x, y) coordinates or by element_id (numeric Set-of-Mark ID from get_ui_hierarchy). Uses LSPosed direct dispatch -> Shizuku -> Root -> Accessibility.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("element_id", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Numeric element ID from get_ui_hierarchy / SoM screenshot"))
                        })
                        put("x", buildJsonObject {
                            put("type", JsonPrimitive("number"))
                            put("description", JsonPrimitive("Screen X coordinate"))
                        })
                        put("y", buildJsonObject {
                            put("type", JsonPrimitive("number"))
                            put("description", JsonPrimitive("Screen Y coordinate"))
                        })
                    }
                )
            ),
            Tool(
                name = "swipe",
                description = "Perform a smooth swipe/scroll gesture from (x1, y1) to (x2, y2).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("x1", buildJsonObject { put("type", JsonPrimitive("number")) })
                        put("y1", buildJsonObject { put("type", JsonPrimitive("number")) })
                        put("x2", buildJsonObject { put("type", JsonPrimitive("number")) })
                        put("y2", buildJsonObject { put("type", JsonPrimitive("number")) })
                        put("duration_ms", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Duration in milliseconds, default 300"))
                        })
                    },
                    required = listOf("x1", "y1", "x2", "y2")
                )
            ),
            Tool(
                name = "input_text",
                description = "Type text into currently focused input field. Supports full Unicode, Chinese characters, and symbols directly without soft-keyboard interference.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Text to input"))
                        })
                    },
                    required = listOf("text")
                )
            ),
            Tool(
                name = "press_key",
                description = "Simulate key press (e.g., 'BACK', 'HOME', 'RECENTS', 'ENTER', 'POWER', 'VOLUME_UP', 'VOLUME_DOWN').",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("key", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Key name or key code"))
                        })
                    },
                    required = listOf("key")
                )
            ),
            Tool(
                name = "launch_app",
                description = "Launch an application by package name, optionally specifying Activity or deep link URI.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("activity_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("uri", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                    required = listOf("package_name")
                )
            ),
            Tool(
                name = "stop_app",
                description = "Force stop an application by package name (via Shizuku or Root).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                    required = listOf("package_name")
                )
            ),
            Tool(
                name = "clear_app_data",
                description = "Clear application data/cache by package name (via Shizuku or Root).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                    required = listOf("package_name")
                )
            ),
            Tool(
                name = "execute_shell",
                description = "Execute a shell command on the device (supports normal shell, Shizuku, or Root).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("command", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("as_root", buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("Run as root if true"))
                        })
                    },
                    required = listOf("command")
                )
            ),
            Tool(
                name = "get_device_info",
                description = "Get comprehensive device specs, screen resolution, battery status, and current foreground app/activity.",
                inputSchema = ToolInputSchema()
            ),
            Tool(
                name = "get_recent_logs",
                description = "Retrieve recent Logcat logs with optional tag and line count filters.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("tag", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("filter", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("lines", buildJsonObject { put("type", JsonPrimitive("integer")) })
                    }
                )
            ),
            // LSPosed Deep Reverse Engineering Tools
            Tool(
                name = "hook_inspect_activity",
                description = "[LSPosed] Inspect current active Activity, Application, or specific class in the target app, listing internal fields, state, and methods in memory.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Target app package name (defaults to current foreground app if omitted)"))
                        })
                        put("class_name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Target class name to inspect (optional, defaults to active Activity or Application)"))
                        })
                    }
                )
            ),
            Tool(
                name = "hook_call_method",
                description = "[LSPosed] Invoke arbitrary Java/Kotlin method on target Activity or class inside the hooked app process.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("class_name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Target class name (optional, defaults to active Activity)"))
                        })
                        put("method_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("params", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                        })
                    },
                    required = listOf("package_name", "method_name")
                )
            ),
            Tool(
                name = "hook_set_field",
                description = "[LSPosed] Modify private or public field value in memory inside the hooked target app.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("class_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("field_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("field_value", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                    required = listOf("package_name", "field_name", "field_value")
                )
            ),
            Tool(
                name = "hook_get_view_tree",
                description = "[LSPosed] Extract in-process View and Compose Semantics tree directly from target app's DecorView.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                    },
                    required = listOf("package_name")
                )
            ),
            Tool(
                name = "click_by_selector",
                description = "Smart UI selector targeting. Automatically captures UI hierarchy, matches element by text/id/desc, calculates center coordinates, and taps it in one step.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("text", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("Text to match")) })
                        put("resource_id", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("Resource ID to match")) })
                        put("content_desc", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("Content description to match")) })
                        put("class_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("Class name filter")) })
                        put("match_type", buildJsonObject { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("'contains' (default), 'exact', or 'regex'")) })
                    }
                )
            ),
            Tool(
                name = "hook_dump_sqlite",
                description = "[Root/Reverse] Directly query private SQLite database files in /data/data/<package>/databases/ without opening the app.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("db_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Database filename (optional, lists available DBs if omitted)") })
                        put("query", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "SQL query (defaults to listing all tables)") })
                        put("limit", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Max rows returned, default 50") })
                    },
                    required = listOf("package_name")
                )
            ),
            Tool(
                name = "hook_dump_shared_prefs",
                description = "[Root/Reverse] Inspect private SharedPreferences XML configurations in /data/data/<package>/shared_prefs/ and parse as JSON.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("file_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "XML filename (optional, lists available files if omitted)") })
                        put("key", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Filter by specific preference key") })
                    },
                    required = listOf("package_name")
                )
            ),
            Tool(
                name = "manage_clipboard",
                description = "Get or set system clipboard content across the entire Android OS.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "'get' to read clipboard, 'set' to write") })
                        put("text", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Text to put into clipboard (required for 'set')") })
                    },
                    required = listOf("action")
                )
            ),
            Tool(
                name = "system_control",
                description = "[Root/System] Deep OS hardware & power controls (Wi-Fi, Mobile Data, Airplane Mode, HTTP Proxy, Screen Density/Size, Wake/Sleep, Grant Permissions).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", "Actions: 'wifi_on', 'wifi_off', 'data_on', 'data_off', 'airplane_on', 'airplane_off', 'set_proxy', 'clear_proxy', 'wake', 'sleep', 'set_screen_density', 'set_screen_size', 'reset_screen', 'grant_all_permissions'")
                        })
                        put("param", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", "Parameter for action (e.g., '192.168.1.100:8888' for set_proxy, '280' for density, package name for grant_all_permissions)")
                        })
                    },
                    required = listOf("action")
                )
            ),
            Tool(
                name = "system_file_ops",
                description = "[Root] Read, write, list, delete, or inspect protected files in system partitions and private app sandboxes (/data/data/).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "'read', 'write', 'list', 'delete', 'exists'") })
                        put("path", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Target file or directory path") })
                        put("content", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "File content for 'write'") })
                        put("as_base64", buildJsonObject { put("type", JsonPrimitive("boolean")); put("description", "Whether content is base64 encoded") })
                    },
                    required = listOf("action", "path")
                )
            ),
            Tool(
                name = "send_intent",
                description = "Send arbitrary Intent to launch Activities, send Broadcasts, or start Services with custom Actions, URIs, and Extras.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("type", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "'activity' (default), 'broadcast', or 'service'") })
                        put("action", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Intent action (e.g. 'android.intent.action.VIEW')") })
                        put("data_uri", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Data URI (e.g. 'https://...', 'tel:...')") })
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("component", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Component name (e.g. 'com.example/.MyActivity')") })
                        put("extras", buildJsonObject { put("type", JsonPrimitive("object")); put("description", "Key-value string extras") })
                    },
                    required = listOf("action")
                )
            ),
            Tool(
                name = "wait_for_element",
                description = "Smart UI element waiter. Continuously monitors the UI hierarchy until an element matching the selector appears (or disappears). Solves race conditions and prevents token waste.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("text", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Text to match") })
                        put("resource_id", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Resource ID to match") })
                        put("content_desc", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Content description to match") })
                        put("class_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Class name filter") })
                        put("match_type", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "'contains' (default), 'exact', or 'regex'") })
                        put("condition", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "'present' (default, wait until visible) or 'absent' (wait until gone)") })
                        put("timeout_ms", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Timeout in ms, default 5000 (max 30000)") })
                        put("interval_ms", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Check interval in ms, default 400") })
                        put("auto_click", buildJsonObject { put("type", JsonPrimitive("boolean")); put("description", "If true, clicks the matched element immediately upon appearance") })
                    }
                )
            ),
            Tool(
                name = "long_press",
                description = "Perform long-press gesture on screen by (x, y) coordinates or by element_id (Set-of-Mark ID from get_ui_hierarchy). Triggers context menus or drag initiation.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("element_id", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Numeric element ID from get_ui_hierarchy / SoM screenshot") })
                        put("x", buildJsonObject { put("type", JsonPrimitive("number")); put("description", "Screen X coordinate") })
                        put("y", buildJsonObject { put("type", JsonPrimitive("number")); put("description", "Screen Y coordinate") })
                        put("duration_ms", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Press duration in milliseconds, default 800") })
                    }
                )
            ),
            Tool(
                name = "drag_and_drop",
                description = "Perform a drag-and-drop gesture from a source point/element to a target point/element.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("from_element_id", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Source element ID from get_ui_hierarchy") })
                        put("to_element_id", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Target element ID from get_ui_hierarchy") })
                        put("x1", buildJsonObject { put("type", JsonPrimitive("number")); put("description", "Start X coordinate") })
                        put("y1", buildJsonObject { put("type", JsonPrimitive("number")); put("description", "Start Y coordinate") })
                        put("x2", buildJsonObject { put("type", JsonPrimitive("number")); put("description", "End X coordinate") })
                        put("y2", buildJsonObject { put("type", JsonPrimitive("number")); put("description", "End Y coordinate") })
                        put("hold_ms", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Initial hold duration before moving, default 400") })
                        put("duration_ms", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Drag movement duration in ms, default 500") })
                    }
                )
            ),
            Tool(
                name = "get_notifications",
                description = "Retrieve active and recent system notifications (SMS verification codes, app push alerts, OTP messages, etc.). Supports package name and keyword filtering.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Filter notifications from a specific package") })
                        put("filter", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Keyword filter for title or text content") })
                        put("limit", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Max notifications returned, default 20") })
                        put("clear", buildJsonObject { put("type", JsonPrimitive("boolean")); put("description", "If true, cancels/dismisses the retrieved notifications") })
                    }
                )
            ),
            Tool(
                name = "wait_for_notification",
                description = "Asynchronously wait for a new incoming notification matching criteria (e.g., SMS verification code / OTP) within a timeout.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Filter incoming notification by package name") })
                        put("text_contains", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Substring or keyword required in notification title/text") })
                        put("timeout_ms", buildJsonObject { put("type", JsonPrimitive("integer")); put("description", "Timeout in ms to wait, default 15000 (max 60000)") })
                        put("clear_after_found", buildJsonObject { put("type", JsonPrimitive("boolean")); put("description", "Whether to dismiss notification after matched") })
                    }
                )
            ),
            Tool(
                name = "get_activity_stack",
                description = "Inspect Android Activity Task stack, listing top resumed activity, focused app, and all active Tasks across displays.",
                inputSchema = ToolInputSchema()
            ),
            Tool(
                name = "hook_get_fragments",
                description = "[LSPosed] Extract active AndroidX / framework Fragment hierarchy from current foreground Activity (class names, tags, visibility, states, child fragments).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Target app package name (defaults to current foreground app if omitted)"))
                        })
                    }
                )
            ),
            Tool(
                name = "hook_trace_method",
                description = "[LSPosed] Dynamically trace method calls inside hooked app process. Records invocation timestamp, arguments, return value/exception, and duration in a ring buffer.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Target app package name") })
                        put("class_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Target class name containing the method") })
                        put("method_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Method name to trace") })
                        put("action", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", "Action: 'trace' (default, registers hook), 'query' (retrieves recorded calls), 'clear' (clears logs)")
                        })
                    },
                    required = listOf("package_name", "class_name", "method_name")
                )
            ),
            Tool(
                name = "install_apk",
                description = "Silently install an APK package on device via Shizuku or Root.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("path", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "File path of the APK on the device (e.g. /data/local/tmp/app.apk)") })
                        put("reinstall", buildJsonObject { put("type", JsonPrimitive("boolean")); put("description", "Whether to reinstall / keep data (-r), default true") })
                        put("downgrade", buildJsonObject { put("type", JsonPrimitive("boolean")); put("description", "Whether to allow downgrade (-d), default true") })
                        put("grant_permissions", buildJsonObject { put("type", JsonPrimitive("boolean")); put("description", "Whether to grant all runtime permissions (-g), default true") })
                    },
                    required = listOf("path")
                )
            ),
            Tool(
                name = "pull_apk",
                description = "Extract the installed APK file of a package on device, copy to destination path (defaults to /sdcard/Download/<package>.apk), and return path and size.",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("package_name", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Package name of the installed app to extract") })
                        put("dest_path", buildJsonObject { put("type", JsonPrimitive("string")); put("description", "Destination path on device (optional)") })
                    },
                    required = listOf("package_name")
                )
            ),
            Tool(
                name = "run_actions",
                description = "Execute a batch sequence of UI actions atomically with configurable delay between steps. Supported actions: 'tap' (x, y), 'long_press' (x, y, duration_ms), 'swipe' (x1, y1, x2, y2, duration_ms), 'drag_and_drop' (x1, y1, x2, y2, hold_ms, duration_ms), 'input_text' (text), 'press_key' (key), 'wait' (duration_ms).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("actions", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Array of action objects: [ { action: 'tap', x: 100, y: 200 }, { action: 'input_text', text: 'hello' } ]"))
                        })
                        put("delay_between_ms", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Delay in milliseconds between actions, default 300ms"))
                        })
                    },
                    required = listOf("actions")
                )
            ),
            Tool(
                name = "manage_macro",
                description = "Manage and replay automation macros. Supported actions: 'run' (replay a saved macro by name), 'save' (save actions sequence as a named macro), 'list' (list all saved macros), 'delete' (delete a saved macro).",
                inputSchema = ToolInputSchema(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Action: 'run', 'save', 'list', 'delete'"))
                        })
                        put("macro_name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Identifier name of the macro"))
                        })
                        put("actions", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Action objects array (required when saving a macro)"))
                        })
                        put("delay_between_ms", buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Delay in ms between steps during replay, default 300ms"))
                        })
                    },
                    required = listOf("action")
                )
            )
        )
    }

    suspend fun dumpUiTree(): Pair<UiNode?, String> {
        val (currentPkg, _) = privilegeManager.getForegroundApp(context)
        var tree: UiNode? = null
        var source = "unknown"

        // 1. Try LSPosed hook view tree if foreground app is hooked
        if (!currentPkg.isNullOrBlank()) {
            val hookResp = HookClientManager.sendCommand(
                currentPkg,
                HookIpcRequest(action = "GET_VIEW_TREE", targetPackage = currentPkg)
            )
            if (hookResp.success && hookResp.uiNode != null) {
                tree = hookResp.uiNode
                source = "LSPosed (in-process memory tree)"
            }
        }

        // 2. Fallback to Accessibility hierarchy
        if (tree == null) {
            val a11y = McpAccessibilityService.instance
            tree = a11y?.dumpHierarchy()
            if (tree != null) source = "AccessibilityService"
        }

        // 3. Fallback to UiAutomator dump via Root / Shizuku
        if (tree == null) {
            tree = dumpViaUiAutomator()
            if (tree != null) source = "UiAutomator (Root/Shizuku dump)"
        }

        if (tree != null) {
            lastDumpedTree = tree
        }
        return tree to source
    }

    @SuppressLint("SdCardPath")
    private suspend fun executeTool(name: String, args: JsonObject): CallToolResult {
        return when (name) {
            "get_ui_hierarchy" -> {
                val format = args["format"]?.jsonPrimitive?.content ?: "compact_text"
                val (currentPkg, currentAct) = privilegeManager.getForegroundApp(context)
                val (tree, source) = dumpUiTree()

                if (tree == null) {
                    return CallToolResult(
                        content = listOf(ContentItem(text = "Failed to capture UI hierarchy. Ensure AccessibilityService is enabled, target app is hooked with LSPosed, or Root is granted.")),
                        isError = true
                    )
                }

                val outputText = if (format == "json") {
                    jsonConfig.encodeToString(tree)
                } else {
                    val title = "App: ${currentPkg ?: "Unknown"} | Activity: ${currentAct ?: "Unknown"} | Source: $source"
                    UiTreeFlattener.toCompactPrompt(tree, title)
                }

                CallToolResult(content = listOf(ContentItem(text = outputText)))
            }

            "capture_screenshot" -> {
                val annotateSom = args["annotate_som"]?.jsonPrimitive?.booleanOrNull ?: false
                val quality = args["quality"]?.jsonPrimitive?.intOrNull ?: 80

                var bitmap = ScreenCapturer.captureBitmap() ?: return CallToolResult(
                    content = listOf(ContentItem(text = "Screenshot capture failed. Ensure Root, Shizuku, or Accessibility is enabled.")),
                    isError = true
                )

                if (annotateSom && lastDumpedTree != null) {
                    val interactiveElements = UiTreeFlattener.flattenInteractive(lastDumpedTree!!)
                    bitmap = SetOfMarkAnnotator.annotate(bitmap, interactiveElements)
                }

                val base64 = ScreenCapturer.toBase64Jpeg(bitmap, quality)
                CallToolResult(
                    content = listOf(
                        ContentItem(
                            type = "image",
                            data = base64,
                            mimeType = "image/jpeg"
                        )
                    )
                )
            }

            "tap" -> {
                val elementId = args["element_id"]?.jsonPrimitive?.intOrNull
                var x = args["x"]?.jsonPrimitive?.floatOrNull
                var y = args["y"]?.jsonPrimitive?.floatOrNull
                var targetPkg: String? = null
                var targetId: String? = null

                if (elementId != null && lastDumpedTree != null) {
                    val node = UiTreeFlattener.findNodeById(lastDumpedTree!!, elementId)
                    if (node != null) {
                        x = node.bounds.centerX.toFloat()
                        y = node.bounds.centerY.toFloat()
                        targetPkg = node.packageName
                        targetId = node.resourceId
                    }
                }

                if (x == null || y == null) {
                    return CallToolResult(
                        content = listOf(ContentItem(text = "Please specify (x, y) coordinates or a valid element_id from get_ui_hierarchy")),
                        isError = true
                    )
                }

                val (ok, msg) = InputController.click(x, y, targetPkg, targetId)
                if (ok && MacroManager.isRecording) {
                    MacroManager.recordAction(buildJsonObject { put("action", JsonPrimitive("tap")); put("x", JsonPrimitive(x)); put("y", JsonPrimitive(y)) })
                }
                CallToolResult(
                    content = listOf(ContentItem(text = msg)),
                    isError = !ok
                )
            }

            "swipe" -> {
                val x1 = args["x1"]?.jsonPrimitive?.float ?: 0f
                val y1 = args["y1"]?.jsonPrimitive?.float ?: 0f
                val x2 = args["x2"]?.jsonPrimitive?.float ?: 0f
                val y2 = args["y2"]?.jsonPrimitive?.float ?: 0f
                val duration = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L

                val (ok, msg) = InputController.swipe(x1, y1, x2, y2, duration)
                if (ok && MacroManager.isRecording) {
                    MacroManager.recordAction(buildJsonObject {
                        put("action", JsonPrimitive("swipe"))
                        put("x1", JsonPrimitive(x1)); put("y1", JsonPrimitive(y1))
                        put("x2", JsonPrimitive(x2)); put("y2", JsonPrimitive(y2))
                        put("duration_ms", JsonPrimitive(duration))
                    })
                }
                CallToolResult(content = listOf(ContentItem(text = msg)), isError = !ok)
            }

            "input_text" -> {
                val text = args["text"]?.jsonPrimitive?.content ?: ""
                val (ok, msg) = InputController.inputText(text)
                if (ok && MacroManager.isRecording) {
                    MacroManager.recordAction(buildJsonObject { put("action", JsonPrimitive("input_text")); put("text", JsonPrimitive(text)) })
                }
                CallToolResult(content = listOf(ContentItem(text = msg)), isError = !ok)
            }

            "press_key" -> {
                val key = args["key"]?.jsonPrimitive?.content ?: ""
                val (ok, msg) = InputController.pressKey(key)
                if (ok && MacroManager.isRecording) {
                    MacroManager.recordAction(buildJsonObject { put("action", JsonPrimitive("press_key")); put("key", JsonPrimitive(key)) })
                }
                CallToolResult(content = listOf(ContentItem(text = msg)), isError = !ok)
            }

            "launch_app" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: ""
                val act = args["activity_name"]?.jsonPrimitive?.contentOrNull
                val uri = args["uri"]?.jsonPrimitive?.contentOrNull

                val (ok, msg) = privilegeManager.launchApp(context, pkg, act, uri)
                CallToolResult(content = listOf(ContentItem(text = msg)), isError = !ok)
            }

            "stop_app" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: ""
                val (ok, msg) = privilegeManager.stopApp(pkg)
                CallToolResult(content = listOf(ContentItem(text = msg)), isError = !ok)
            }

            "clear_app_data" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: ""
                val (ok, msg) = privilegeManager.clearAppData(pkg)
                CallToolResult(content = listOf(ContentItem(text = msg)), isError = !ok)
            }

            "execute_shell" -> {
                val cmd = args["command"]?.jsonPrimitive?.content ?: ""
                val asRoot = args["as_root"]?.jsonPrimitive?.booleanOrNull ?: false

                val (code, out) = if (asRoot && RootBridge.isRootAvailable()) {
                    RootBridge.exec(cmd)
                } else if (ShizukuBridge.hasPermission()) {
                    ShizukuBridge.exec("sh", "-c", cmd)
                } else {
                    withContext(Dispatchers.IO) {
                        try {
                            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                            val output = proc.inputStream.bufferedReader().readText()
                            val err = proc.errorStream.bufferedReader().readText()
                            val exit = proc.waitFor()
                            exit to if (err.isNotBlank()) "$output\n$err" else output
                        } catch (e: Throwable) {
                            -1 to "Execution error: ${e.message}"
                        }
                    }
                }
                CallToolResult(
                    content = listOf(ContentItem(text = "Exit Code: $code\nOutput:\n$out")),
                    isError = code != 0
                )
            }

            "get_device_info" -> {
                val info = privilegeManager.getDeviceInfo(context)
                val status = privilegeManager.getPrivilegeStatus()
                val json = buildJsonObject {
                    put("device", jsonConfig.encodeToJsonElement(info))
                    put("privileges", jsonConfig.encodeToJsonElement(status))
                }
                CallToolResult(content = listOf(ContentItem(text = jsonConfig.encodeToString(json))))
            }

            "get_recent_logs" -> {
                val lines = args["lines"]?.jsonPrimitive?.intOrNull ?: 100
                val tag = args["tag"]?.jsonPrimitive?.contentOrNull
                val filter = args["filter"]?.jsonPrimitive?.contentOrNull

                val cmd = StringBuilder("logcat -d -v time -t $lines")
                if (tag != null) cmd.append(" -s $tag")
                val (_, out) = if (ShizukuBridge.hasPermission()) {
                    ShizukuBridge.exec("sh", "-c", cmd.toString())
                } else {
                    RootBridge.exec(cmd.toString())
                }
                val filtered = if (filter != null) {
                    out.lineSequence().filter { it.contains(filter, ignoreCase = true) }.joinToString("\n")
                } else out

                CallToolResult(content = listOf(ContentItem(text = filtered)))
            }

            // LSPosed Deep Hook Tools
            "hook_inspect_activity" -> {
                var pkg = args["package_name"]?.jsonPrimitive?.contentOrNull
                val cls = args["class_name"]?.jsonPrimitive?.contentOrNull
                if (pkg.isNullOrBlank()) {
                    val (fgPkg, _) = privilegeManager.getForegroundApp(context)
                    pkg = fgPkg
                }
                if (pkg.isNullOrBlank()) {
                    return CallToolResult(content = listOf(ContentItem(text = "Cannot determine target package")), isError = true)
                }

                val resp = HookClientManager.sendCommand(
                    pkg,
                    HookIpcRequest(
                        action = "GET_ACTIVITY_INFO",
                        targetPackage = pkg,
                        className = cls
                    )
                )
                val text = if (resp.success) {
                    "Activity/Process Inspection:\n${resp.data?.toString() ?: resp.message}"
                } else {
                    resp.message ?: "Failed"
                }
                CallToolResult(content = listOf(ContentItem(text = text)), isError = !resp.success)
            }

            "hook_call_method" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: ""
                val cls = args["class_name"]?.jsonPrimitive?.contentOrNull
                val method = args["method_name"]?.jsonPrimitive?.content ?: ""
                val paramsArray = args["params"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

                val resp = HookClientManager.sendCommand(
                    pkg,
                    HookIpcRequest(
                        action = "CALL_METHOD",
                        targetPackage = pkg,
                        className = cls,
                        methodName = method,
                        params = paramsArray
                    )
                )
                CallToolResult(
                    content = listOf(ContentItem(text = resp.message ?: "Result: ${resp.data}")),
                    isError = !resp.success
                )
            }

            "hook_set_field" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: ""
                val cls = args["class_name"]?.jsonPrimitive?.contentOrNull
                val field = args["field_name"]?.jsonPrimitive?.content ?: ""
                val value = args["field_value"]?.jsonPrimitive?.content ?: ""

                val resp = HookClientManager.sendCommand(
                    pkg,
                    HookIpcRequest(
                        action = "SET_FIELD",
                        targetPackage = pkg,
                        className = cls,
                        fieldName = field,
                        fieldValue = value
                    )
                )
                CallToolResult(
                    content = listOf(ContentItem(text = resp.message ?: "Done")),
                    isError = !resp.success
                )
            }

            "hook_get_view_tree" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: ""
                val resp = HookClientManager.sendCommand(
                    pkg,
                    HookIpcRequest(action = "GET_VIEW_TREE", targetPackage = pkg)
                )
                if (resp.success && resp.uiNode != null) {
                    lastDumpedTree = resp.uiNode
                    val promptText = UiTreeFlattener.toCompactPrompt(resp.uiNode, "LSPosed In-Process View Tree for $pkg")
                    CallToolResult(content = listOf(ContentItem(text = promptText)))
                } else {
                    CallToolResult(content = listOf(ContentItem(text = resp.message ?: "Failed")), isError = true)
                }
            }

            // ----------------------------------------------------
            // Next-Gen Black Tech MCP Superpowers
            // ----------------------------------------------------
            "click_by_selector" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                val resId = args["resource_id"]?.jsonPrimitive?.contentOrNull
                val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
                val className = args["class_name"]?.jsonPrimitive?.contentOrNull
                val matchType = args["match_type"]?.jsonPrimitive?.content ?: "contains"

                val (tree, _) = dumpUiTree()
                if (tree == null) {
                    return CallToolResult(content = listOf(ContentItem(text = "Failed to capture UI tree")), isError = true)
                }

                val flat = UiTreeFlattener.flattenInteractive(tree)
                val matched = flat.firstOrNull { node ->
                    val textMatches = when {
                        text == null -> true
                        matchType == "exact" -> node.text == text
                        matchType == "regex" -> node.text?.let { Regex(text).containsMatchIn(it) } == true
                        else -> node.text?.contains(text, ignoreCase = true) == true
                    }
                    val resMatches = when {
                        resId == null -> true
                        matchType == "exact" -> node.resourceId == resId
                        else -> node.resourceId?.contains(resId, ignoreCase = true) == true
                    }
                    val descMatches = when {
                        contentDesc == null -> true
                        matchType == "exact" -> node.description == contentDesc
                        else -> node.description?.contains(contentDesc, ignoreCase = true) == true
                    }
                    val classMatches = when {
                        className == null -> true
                        else -> node.className?.contains(className, ignoreCase = true) == true
                    }
                    textMatches && resMatches && descMatches && classMatches
                }

                if (matched == null) {
                    return CallToolResult(
                        content = listOf(ContentItem(text = "No UI element matched selector criteria: text=$text, resource_id=$resId, desc=$contentDesc")),
                        isError = true
                    )
                }

                val cx = matched.bounds.centerX.toFloat()
                val cy = matched.bounds.centerY.toFloat()
                val (ok, msg) = InputController.click(cx, cy, matched.packageName, matched.resourceId)
                CallToolResult(
                    content = listOf(ContentItem(text = "Matched: ${matched.toCompactString()}\nClick result at ($cx, $cy): $msg")),
                    isError = !ok
                )
            }

            "hook_dump_sqlite" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing package_name")
                val dbName = args["db_name"]?.jsonPrimitive?.contentOrNull
                val query = args["query"]?.jsonPrimitive?.contentOrNull ?: "SELECT name FROM sqlite_master WHERE type='table';"
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 50

                val dbDir = File(Environment.getDataDirectory(), "data/$pkg/databases").path
                if (dbName.isNullOrBlank()) {
                    val (_, out) = RootBridge.exec("ls -1 $dbDir 2>/dev/null")
                    val dbs = out.lines().filter { it.endsWith(".db") || it.endsWith(".sqlite") }
                    CallToolResult(content = listOf(ContentItem(text = "Available databases in $pkg: $dbs\nSpecify db_name and query to inspect tables.")))
                } else {
                    val dbPath = "$dbDir/$dbName"
                    val safeQuery = if (query.trim().uppercase().startsWith("SELECT") && !query.uppercase().contains("LIMIT")) "$query LIMIT $limit;" else query
                    val (code, out) = RootBridge.exec("sqlite3 -json $dbPath \"$safeQuery\" 2>/dev/null || sqlite3 -header -column $dbPath \"$safeQuery\" 2>/dev/null")
                    val resultText = if (code != 0 || out.isBlank()) {
                        querySqliteViaFramework(dbPath, query, limit)
                    } else {
                        out
                    }
                    CallToolResult(
                        content = listOf(ContentItem(text = resultText))
                    )
                }
            }

            "hook_dump_shared_prefs" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing package_name")
                val fileName = args["file_name"]?.jsonPrimitive?.contentOrNull
                val filterKey = args["key"]?.jsonPrimitive?.contentOrNull

                val spDir = File(Environment.getDataDirectory(), "data/$pkg/shared_prefs").path
                if (fileName.isNullOrBlank()) {
                    val (_, out) = RootBridge.exec("ls -1 $spDir 2>/dev/null")
                    val files = out.lines().filter { it.endsWith(".xml") }
                    CallToolResult(content = listOf(ContentItem(text = "Available SharedPreferences in $pkg:\n${files.joinToString("\n")}\n\nSpecify file_name to dump contents.")))
                } else {
                    val targetFile = if (fileName.endsWith(".xml")) "$spDir/$fileName" else "$spDir/$fileName.xml"
                    val (code, out) = RootBridge.exec("cat $targetFile 2>/dev/null")
                    if (code != 0 || out.isBlank()) {
                        CallToolResult(content = listOf(ContentItem(text = "Failed to read $targetFile (exitCode=$code)")), isError = true)
                    } else {
                        val parsedJson = parseSharedPrefsXml(out, filterKey)
                        CallToolResult(content = listOf(ContentItem(text = parsedJson)))
                    }
                }
            }

            "manage_clipboard" -> {
                val action = args["action"]?.jsonPrimitive?.content ?: "get"
                val text = args["text"]?.jsonPrimitive?.contentOrNull

                when (action) {
                    "get" -> {
                        var clipText: String? = null
                        withContext(Dispatchers.Main) {
                            try {
                                clipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                            } catch (_: Throwable) {
                            }
                        }
                        if (clipText.isNullOrBlank()) {
                            val (_, out) = RootBridge.exec("cmd clipboard get 2>/dev/null")
                            clipText = out.trim()
                        }
                        CallToolResult(content = listOf(ContentItem(text = clipText)))
                    }
                    "set" -> {
                        val newText = text ?: ""
                        withContext(Dispatchers.Main) {
                            try {
                                val clip = ClipData.newPlainText("mcp", newText)
                                clipboardManager.setPrimaryClip(clip)
                            } catch (_: Throwable) {
                            }
                        }
                        RootBridge.exec("cmd clipboard set \"${newText.replace("\"", "\\\"")}\" 2>/dev/null")
                        CallToolResult(content = listOf(ContentItem(text = "Clipboard updated successfully.")))
                    }
                    else -> throw IllegalArgumentException("Unknown action: $action (must be 'get' or 'set')")
                }
            }

            "system_control" -> {
                val action = args["action"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing action")
                val param = args["param"]?.jsonPrimitive?.contentOrNull

                val cmd = when (action) {
                    "wifi_on" -> "svc wifi enable"
                    "wifi_off" -> "svc wifi disable"
                    "data_on" -> "svc data enable"
                    "data_off" -> "svc data disable"
                    "airplane_on" -> "cmd connectivity airplane-mode enable"
                    "airplane_off" -> "cmd connectivity airplane-mode disable"
                    "set_proxy" -> "settings put global http_proxy $param"
                    "clear_proxy" -> "settings put global http_proxy :0"
                    "wake" -> "input keyevent KEYCODE_WAKEUP"
                    "sleep" -> "input keyevent KEYCODE_SLEEP"
                    "set_screen_density" -> "wm density $param"
                    "set_screen_size" -> "wm size $param"
                    "reset_screen" -> "wm density reset && wm size reset"
                    "grant_all_permissions" -> {
                        val targetPkg = param ?: throw IllegalArgumentException("param (package_name) is required for grant_all_permissions")
                        "pm grant $targetPkg android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; pm grant $targetPkg android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; pm grant $targetPkg android.permission.ACCESS_FINE_LOCATION 2>/dev/null; pm grant $targetPkg android.permission.ACCESS_COARSE_LOCATION 2>/dev/null; pm grant $targetPkg android.permission.POST_NOTIFICATIONS 2>/dev/null; pm grant $targetPkg android.permission.CAMERA 2>/dev/null; pm grant $targetPkg android.permission.RECORD_AUDIO 2>/dev/null; echo 'Granted permissions for $targetPkg'"
                    }
                    "keep_alive_whitelist" -> {
                        val targetPkg = param ?: context.packageName
                        "dumpsys deviceidle whitelist +$targetPkg 2>/dev/null; cmd appops set $targetPkg RUN_IN_BACKGROUND allow 2>/dev/null; echo 'Whitelisted $targetPkg for battery optimization & background execution'"
                    }
                    else -> throw IllegalArgumentException("Unknown system_control action: $action")
                }

                val (code, out) = RootBridge.exec(cmd)
                CallToolResult(content = listOf(ContentItem(text = "Action: $action (code=$code)\n$out")))
            }

            "system_file_ops" -> {
                val action = args["action"]?.jsonPrimitive?.content ?: "read"
                val path = args["path"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing path")
                val content = args["content"]?.jsonPrimitive?.contentOrNull
                val asBase64 = args["as_base64"]?.jsonPrimitive?.booleanOrNull ?: false

                when (action) {
                    "read" -> {
                        val (code, out) = if (asBase64) {
                            RootBridge.exec("base64 $path 2>/dev/null")
                        } else {
                            RootBridge.exec("cat $path 2>/dev/null")
                        }
                        if (code != 0) {
                            CallToolResult(content = listOf(ContentItem(text = "Failed to read file $path (exitCode=$code)")), isError = true)
                        } else {
                            CallToolResult(content = listOf(ContentItem(text = out)))
                        }
                    }
                    "write" -> {
                        val dataToWrite = content ?: ""
                        val (code, out) = if (asBase64) {
                            RootBridge.exec("echo \"$dataToWrite\" | base64 -d > $path")
                        } else {
                            RootBridge.exec("cat << 'EOF' > $path\n$dataToWrite\nEOF")
                        }
                        CallToolResult(content = listOf(ContentItem(text = "Write to $path (code=$code)\n$out")))
                    }
                    "list" -> {
                        val (_, out) = RootBridge.exec("ls -la $path 2>/dev/null")
                        CallToolResult(content = listOf(ContentItem(text = out)))
                    }
                    "delete" -> {
                        val (code, out) = RootBridge.exec("rm -rf $path 2>/dev/null")
                        CallToolResult(content = listOf(ContentItem(text = "Delete $path (code=$code) $out")))
                    }
                    "exists" -> {
                        val (code, _) = RootBridge.exec("test -e $path")
                        CallToolResult(content = listOf(ContentItem(text = (code == 0).toString())))
                    }
                    else -> throw IllegalArgumentException("Unknown file_ops action: $action")
                }
            }

            "send_intent" -> {
                val type = args["type"]?.jsonPrimitive?.content ?: "activity"
                val action = args["action"]?.jsonPrimitive?.content ?: "android.intent.action.VIEW"
                val dataUri = args["data_uri"]?.jsonPrimitive?.contentOrNull
                val pkg = args["package_name"]?.jsonPrimitive?.contentOrNull
                val component = args["component"]?.jsonPrimitive?.contentOrNull
                val extras = args["extras"]?.jsonObject

                val sb = StringBuilder()
                when (type) {
                    "activity" -> sb.append("am start -a ").append(action)
                    "broadcast" -> sb.append("am broadcast -a ").append(action)
                    "service" -> sb.append("am startservice -a ").append(action)
                }
                if (!dataUri.isNullOrBlank()) sb.append(" -d \"").append(dataUri).append("\"")
                if (!pkg.isNullOrBlank()) sb.append(" -p ").append(pkg)
                if (!component.isNullOrBlank()) sb.append(" -n ").append(component)
                extras?.forEach { (k, v) ->
                    sb.append(" --es \"").append(k).append("\" \"").append(v.jsonPrimitive.content).append("\"")
                }

                val (code, out) = RootBridge.exec(sb.toString())
                CallToolResult(content = listOf(ContentItem(text = "Intent Command: $sb\nExitCode: $code\nOutput:\n$out")))
            }

            "wait_for_element" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull
                val resId = args["resource_id"]?.jsonPrimitive?.contentOrNull
                val contentDesc = args["content_desc"]?.jsonPrimitive?.contentOrNull
                val className = args["class_name"]?.jsonPrimitive?.contentOrNull
                val matchType = args["match_type"]?.jsonPrimitive?.content ?: "contains"
                val condition = args["condition"]?.jsonPrimitive?.content ?: "present"
                val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5000L).coerceIn(500L, 30000L)
                val intervalMs = (args["interval_ms"]?.jsonPrimitive?.longOrNull ?: 400L).coerceIn(100L, 2000L)
                val autoClick = args["auto_click"]?.jsonPrimitive?.booleanOrNull ?: false

                val startTime = System.currentTimeMillis()
                var matchedNode: UiNode? = null

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val (tree, _) = dumpUiTree()
                    if (tree != null) {
                        val flat = UiTreeFlattener.flattenInteractive(tree)
                        matchedNode = flat.firstOrNull { node ->
                            val textMatches = when {
                                text == null -> true
                                matchType == "exact" -> node.text == text
                                matchType == "regex" -> node.text?.let { Regex(text).containsMatchIn(it) } == true
                                else -> node.text?.contains(text, ignoreCase = true) == true
                            }
                            val resMatches = when {
                                resId == null -> true
                                matchType == "exact" -> node.resourceId == resId
                                else -> node.resourceId?.contains(resId, ignoreCase = true) == true
                            }
                            val descMatches = when {
                                contentDesc == null -> true
                                matchType == "exact" -> node.description == contentDesc
                                else -> node.description?.contains(contentDesc, ignoreCase = true) == true
                            }
                            val classMatches = when {
                                className == null -> true
                                else -> node.className?.contains(className, ignoreCase = true) == true
                            }
                            textMatches && resMatches && descMatches && classMatches
                        }
                    }

                    if (condition == "present" && matchedNode != null) {
                        break
                    } else if (condition == "absent" && matchedNode == null) {
                        break
                    }

                    delay(intervalMs)
                }

                val elapsed = System.currentTimeMillis() - startTime
                if (condition == "present") {
                    if (matchedNode != null) {
                        val cx = matchedNode.bounds.centerX.toFloat()
                        val cy = matchedNode.bounds.centerY.toFloat()
                        val extraMsg = if (autoClick) {
                            val (ok, clickMsg) = InputController.click(cx, cy, matchedNode.packageName, matchedNode.resourceId)
                            "\nAuto-click at ($cx, $cy): $clickMsg (success=$ok)"
                        } else ""

                        CallToolResult(
                            content = listOf(
                                ContentItem(
                                    text = "Element found after ${elapsed}ms:\n${matchedNode.toCompactString()}$extraMsg"
                                )
                            )
                        )
                    } else {
                        CallToolResult(
                            content = listOf(
                                ContentItem(
                                    text = "Timeout (${timeoutMs}ms) reached. Element not found matching selector (text=$text, id=$resId, desc=$contentDesc, class=$className)"
                                )
                            ),
                            isError = true
                        )
                    }
                } else {
                    if (matchedNode == null) {
                        CallToolResult(
                            content = listOf(
                                ContentItem(
                                    text = "Element successfully became absent after ${elapsed}ms."
                                )
                            )
                        )
                    } else {
                        CallToolResult(
                            content = listOf(
                                ContentItem(
                                    text = "Timeout (${timeoutMs}ms) reached. Element is still present: ${matchedNode.toCompactString()}"
                                )
                            ),
                            isError = true
                        )
                    }
                }
            }

            "long_press" -> {
                val elementId = args["element_id"]?.jsonPrimitive?.intOrNull
                var x = args["x"]?.jsonPrimitive?.floatOrNull
                var y = args["y"]?.jsonPrimitive?.floatOrNull
                val durationMs = (args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 800L).coerceIn(300L, 5000L)
                var targetPkg: String? = null
                var targetId: String? = null

                if (elementId != null && lastDumpedTree != null) {
                    val node = UiTreeFlattener.findNodeById(lastDumpedTree!!, elementId)
                    if (node != null) {
                        x = node.bounds.centerX.toFloat()
                        y = node.bounds.centerY.toFloat()
                        targetPkg = node.packageName
                        targetId = node.resourceId
                    }
                }

                if (x == null || y == null) {
                    return CallToolResult(
                        content = listOf(ContentItem(text = "Please specify (x, y) coordinates or a valid element_id from get_ui_hierarchy")),
                        isError = true
                    )
                }

                val (ok, msg) = InputController.longPress(x, y, durationMs, targetPkg, targetId)
                if (ok && MacroManager.isRecording) {
                    MacroManager.recordAction(buildJsonObject {
                        put("action", JsonPrimitive("long_press"))
                        put("x", JsonPrimitive(x))
                        put("y", JsonPrimitive(y))
                        put("duration_ms", JsonPrimitive(durationMs))
                    })
                }
                CallToolResult(
                    content = listOf(ContentItem(text = msg)),
                    isError = !ok
                )
            }

            "drag_and_drop" -> {
                val fromId = args["from_element_id"]?.jsonPrimitive?.intOrNull
                val toId = args["to_element_id"]?.jsonPrimitive?.intOrNull
                var x1 = args["x1"]?.jsonPrimitive?.floatOrNull
                var y1 = args["y1"]?.jsonPrimitive?.floatOrNull
                var x2 = args["x2"]?.jsonPrimitive?.floatOrNull
                var y2 = args["y2"]?.jsonPrimitive?.floatOrNull
                val holdMs = (args["hold_ms"]?.jsonPrimitive?.longOrNull ?: 400L).coerceIn(100L, 2000L)
                val durationMs = (args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 500L).coerceIn(100L, 5000L)

                if (fromId != null && lastDumpedTree != null) {
                    val node1 = UiTreeFlattener.findNodeById(lastDumpedTree!!, fromId)
                    if (node1 != null) {
                        x1 = node1.bounds.centerX.toFloat()
                        y1 = node1.bounds.centerY.toFloat()
                    }
                }
                if (toId != null && lastDumpedTree != null) {
                    val node2 = UiTreeFlattener.findNodeById(lastDumpedTree!!, toId)
                    if (node2 != null) {
                        x2 = node2.bounds.centerX.toFloat()
                        y2 = node2.bounds.centerY.toFloat()
                    }
                }

                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    return CallToolResult(
                        content = listOf(ContentItem(text = "Please specify (x1, y1, x2, y2) or valid from_element_id and to_element_id")),
                        isError = true
                    )
                }

                val (ok, msg) = InputController.dragAndDrop(x1, y1, x2, y2, holdMs, durationMs)
                if (ok && MacroManager.isRecording) {
                    MacroManager.recordAction(buildJsonObject {
                        put("action", JsonPrimitive("drag_and_drop"))
                        put("x1", JsonPrimitive(x1)); put("y1", JsonPrimitive(y1))
                        put("x2", JsonPrimitive(x2)); put("y2", JsonPrimitive(y2))
                        put("hold_ms", JsonPrimitive(holdMs)); put("duration_ms", JsonPrimitive(durationMs))
                    })
                }
                CallToolResult(
                    content = listOf(ContentItem(text = msg)),
                    isError = !ok
                )
            }

            "get_notifications" -> {
                val pkgFilter = args["package_name"]?.jsonPrimitive?.contentOrNull
                val textFilter = args["filter"]?.jsonPrimitive?.contentOrNull
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 100)
                val clear = args["clear"]?.jsonPrimitive?.booleanOrNull ?: false

                val service = McpNotificationListenerService.instance
                val rawList = if (service != null) {
                    val active = service.getActiveNotificationsList()
                    val history = McpNotificationListenerService.notificationHistory.toList()
                    (active + history).distinctBy { it.key ?: "${it.packageName}:${it.postTime}" }
                } else {
                    McpNotificationListenerService.queryNotificationsViaShell()
                }

                val filtered = rawList.filter { item ->
                    val pkgMatch = pkgFilter == null || item.packageName.contains(pkgFilter, ignoreCase = true)
                    val textMatch = textFilter == null ||
                            (item.title?.contains(textFilter, ignoreCase = true) == true) ||
                            (item.text?.contains(textFilter, ignoreCase = true) == true) ||
                            (item.subText?.contains(textFilter, ignoreCase = true) == true)
                    pkgMatch && textMatch
                }.take(limit)

                if (clear && service != null) {
                    filtered.forEach { item ->
                        item.key?.let { service.clearNotificationByKey(it) }
                    }
                }

                val json = jsonConfig.encodeToString(filtered)
                CallToolResult(
                    content = listOf(
                        ContentItem(
                            text = if (filtered.isEmpty()) {
                                "No matching notifications found."
                            } else {
                                "Found ${filtered.size} notification(s):\n$json"
                            }
                        )
                    )
                )
            }

            "wait_for_notification" -> {
                val pkgFilter = args["package_name"]?.jsonPrimitive?.contentOrNull
                val textContains = args["text_contains"]?.jsonPrimitive?.contentOrNull
                val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 15000L).coerceIn(1000L, 60000L)
                val clearAfter = args["clear_after_found"]?.jsonPrimitive?.booleanOrNull ?: false

                val service = McpNotificationListenerService.instance
                val matched: NotificationItem? = if (service != null) {
                    withTimeoutOrNull(timeoutMs) {
                        // First check if any recent notification in last 10 seconds already matches
                        val recentMatch = McpNotificationListenerService.notificationHistory.firstOrNull { item ->
                            val pkgMatch = pkgFilter == null || item.packageName.contains(pkgFilter, ignoreCase = true)
                            val textMatch = textContains == null ||
                                    (item.title?.contains(textContains, ignoreCase = true) == true) ||
                                    (item.text?.contains(textContains, ignoreCase = true) == true)
                            pkgMatch && textMatch && (System.currentTimeMillis() - item.postTime < 10000)
                        }
                        if (recentMatch != null) {
                            recentMatch
                        } else {
                            McpNotificationListenerService.notificationEvents.first { item ->
                                val pkgMatch = pkgFilter == null || item.packageName.contains(pkgFilter, ignoreCase = true)
                                val textMatch = textContains == null ||
                                        (item.title?.contains(textContains, ignoreCase = true) == true) ||
                                        (item.text?.contains(textContains, ignoreCase = true) == true)
                                pkgMatch && textMatch
                            }
                        }
                    }
                } else {
                    // Fallback polling via shell
                    val startTime = System.currentTimeMillis()
                    var result: NotificationItem? = null
                    while (System.currentTimeMillis() - startTime < timeoutMs) {
                        val active = McpNotificationListenerService.queryNotificationsViaShell()
                        val m = active.firstOrNull { item ->
                            val pkgMatch = pkgFilter == null || item.packageName.contains(pkgFilter, ignoreCase = true)
                            val textMatch = textContains == null ||
                                    (item.title?.contains(textContains, ignoreCase = true) == true) ||
                                    (item.text?.contains(textContains, ignoreCase = true) == true)
                            pkgMatch && textMatch
                        }
                        if (m != null) {
                            result = m
                            break
                        }
                        delay(1000)
                    }
                    result
                }

                if (matched != null) {
                    if (clearAfter && service != null && matched.key != null) {
                        service.clearNotificationByKey(matched.key)
                    }
                    CallToolResult(
                        content = listOf(
                            ContentItem(
                                text = "Notification received:\n${jsonConfig.encodeToString(matched)}"
                            )
                        )
                    )
                } else {
                    CallToolResult(
                        content = listOf(
                            ContentItem(
                                text = "Timeout (${timeoutMs}ms) expired waiting for notification (pkg=$pkgFilter, text=$textContains)"
                            )
                        ),
                        isError = true
                    )
                }
            }

            "get_activity_stack" -> {
                val (code, out) = if (ShizukuBridge.hasPermission()) {
                    ShizukuBridge.exec("dumpsys", "activity", "activities")
                } else if (RootBridge.isRootAvailable()) {
                    RootBridge.exec("dumpsys activity activities")
                } else {
                    -1 to "Root or Shizuku permission required to inspect Activity stack"
                }

                if (code != 0 || out.isBlank()) {
                    CallToolResult(content = listOf(ContentItem(text = "Failed to query Activity stack: $out")), isError = true)
                } else {
                    val resumedMatch = Regex("ResumedActivity: ActivityRecord\\{[^}]*?\\s+([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)\\s+t(\\d+)\\}").find(out)
                    val focusedAppMatch = Regex("mFocusedApp=ActivityRecord\\{[^}]*?\\s+([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.]+)\\s+t(\\d+)\\}").find(out)
                    val focusedWindowMatch = Regex("mFocusedWindow=Window\\{[^}]*?\\s+([^}]+)\\}").find(out)

                    val taskMatches = Regex("\\* Task\\{([a-f0-9]+)\\s+#(\\d+)\\s+type=([a-zA-Z0-9_]+)(?:\\s+A=[^:]*:([a-zA-Z0-9_.]+))?(?:\\s+I=([a-zA-Z0-9_./]+))?[^}]*?visible=(true|false)[^}]*?mode=([a-zA-Z0-9_]+)").findAll(out)

                    val tasksList = taskMatches.map { m ->
                        val taskId = m.groupValues[2]
                        val type = m.groupValues[3]
                        val affinity = m.groupValues[4].ifBlank { m.groupValues[5] }
                        val isVisible = m.groupValues[6]
                        val mode = m.groupValues[7]
                        buildJsonObject {
                            put("taskId", JsonPrimitive(taskId))
                            put("type", JsonPrimitive(type))
                            put("package", JsonPrimitive(affinity.ifBlank { "unknown" }))
                            put("visible", JsonPrimitive(isVisible == "true"))
                            put("mode", JsonPrimitive(mode))
                        }
                    }.toList()

                    val resultJson = buildJsonObject {
                        put("topResumedActivity", buildJsonObject {
                            put("package", JsonPrimitive(resumedMatch?.groupValues?.get(1) ?: "unknown"))
                            put("activity", JsonPrimitive(resumedMatch?.groupValues?.get(2) ?: "unknown"))
                            put("taskId", JsonPrimitive(resumedMatch?.groupValues?.get(3) ?: "unknown"))
                        })
                        put("focusedApp", buildJsonObject {
                            put("package", JsonPrimitive(focusedAppMatch?.groupValues?.get(1) ?: "unknown"))
                            put("activity", JsonPrimitive(focusedAppMatch?.groupValues?.get(2) ?: "unknown"))
                        })
                        put("focusedWindow", JsonPrimitive(focusedWindowMatch?.groupValues?.get(1) ?: "unknown"))
                        put("activeTasks", buildJsonArray { tasksList.forEach { add(it) } })
                    }

                    CallToolResult(content = listOf(ContentItem(text = jsonConfig.encodeToString(resultJson))))
                }
            }

            "hook_get_fragments" -> {
                var pkg = args["package_name"]?.jsonPrimitive?.contentOrNull
                if (pkg.isNullOrBlank()) {
                    val (fgPkg, _) = privilegeManager.getForegroundApp(context)
                    pkg = fgPkg
                }
                if (pkg.isNullOrBlank()) {
                    return CallToolResult(content = listOf(ContentItem(text = "Cannot determine target package")), isError = true)
                }

                val resp = HookClientManager.sendCommand(
                    pkg,
                    HookIpcRequest(action = "GET_FRAGMENTS", targetPackage = pkg)
                )

                if (resp.success) {
                    val text = "Fragment Hierarchy for $pkg:\n${resp.data?.toString() ?: resp.message}"
                    CallToolResult(content = listOf(ContentItem(text = text)))
                } else {
                    CallToolResult(content = listOf(ContentItem(text = resp.message ?: "Failed to extract Fragments")), isError = true)
                }
            }

            "hook_trace_method" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing package_name")
                val cls = args["class_name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing class_name")
                val method = args["method_name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing method_name")
                val action = args["action"]?.jsonPrimitive?.content ?: "trace"

                val resp = HookClientManager.sendCommand(
                    pkg,
                    HookIpcRequest(
                        action = "TRACE_METHOD",
                        subAction = action,
                        targetPackage = pkg,
                        className = cls,
                        methodName = method
                    )
                )

                val contentText = if (resp.success) {
                    "${resp.message}\nData:\n${resp.data?.toString() ?: "No records"}"
                } else {
                    resp.message ?: "Failed"
                }

                CallToolResult(content = listOf(ContentItem(text = contentText)), isError = !resp.success)
            }

            "install_apk" -> {
                val rawPath = args["path"]?.jsonPrimitive?.contentOrNull
                    ?: args["file_path"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing path or file_path")
                val reinstall = args["reinstall"]?.jsonPrimitive?.booleanOrNull ?: true
                val downgrade = args["downgrade"]?.jsonPrimitive?.booleanOrNull ?: true
                val grantPerms = args["grant_permissions"]?.jsonPrimitive?.booleanOrNull ?: true

                // SELinux policy: system_server cannot read /sdcard or /storage directly.
                // Stage the APK in /data/local/tmp if it's located elsewhere.
                val needsStaging = !rawPath.startsWith("/data/local/tmp/")
                val stagedPath = if (needsStaging) "/data/local/tmp/_mcp_staged_${System.currentTimeMillis()}.apk" else rawPath

                val flags = StringBuilder()
                if (reinstall) flags.append(" -r")
                if (downgrade) flags.append(" -d")
                if (grantPerms) flags.append(" -g")

                val execCmd = if (needsStaging) {
                    "cp \"$rawPath\" \"$stagedPath\" && chmod 666 \"$stagedPath\" && pm install$flags \"$stagedPath\"; ret=\$?; rm -f \"$stagedPath\"; exit \$ret"
                } else {
                    "pm install$flags \"$rawPath\""
                }

                val (code, out) = if (ShizukuBridge.hasPermission()) {
                    ShizukuBridge.exec("sh", "-c", execCmd)
                } else if (RootBridge.isRootAvailable()) {
                    RootBridge.exec(execCmd)
                } else {
                    -1 to "Shizuku or Root required to silently install APK"
                }

                CallToolResult(
                    content = listOf(ContentItem(text = "Command: pm install$flags (code=$code)\nOutput:\n$out")),
                    isError = code != 0 || out.contains("Failure", ignoreCase = true)
                )
            }

            "pull_apk" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing package_name")
                val destPath = args["dest_path"]?.jsonPrimitive?.contentOrNull
                    ?: args["destination_path"]?.jsonPrimitive?.contentOrNull
                    ?: "/sdcard/Download/${pkg}_base.apk"

                val (pathCode, pathOut) = if (ShizukuBridge.hasPermission()) {
                    ShizukuBridge.exec("pm", "path", pkg)
                } else if (RootBridge.isRootAvailable()) {
                    RootBridge.exec("pm path $pkg")
                } else {
                    -1 to "Shizuku or Root required to pull APK"
                }

                val sourceApk = pathOut.lines().firstOrNull { it.startsWith("package:") }?.substringAfter("package:")?.trim()

                if (pathCode != 0 || sourceApk.isNullOrBlank()) {
                    return CallToolResult(
                        content = listOf(ContentItem(text = "Failed to find APK path for $pkg: $pathOut")),
                        isError = true
                    )
                }

                val copyCmd = "cp \"$sourceApk\" \"$destPath\" && chmod 666 \"$destPath\" && ls -lh \"$destPath\""
                val (copyCode, copyOut) = if (RootBridge.isRootAvailable()) {
                    RootBridge.exec(copyCmd)
                } else if (ShizukuBridge.hasPermission()) {
                    ShizukuBridge.exec("sh", "-c", copyCmd)
                } else {
                    -1 to "No Root or Shizuku available to copy APK"
                }

                CallToolResult(
                    content = listOf(ContentItem(text = "Source: $sourceApk\nDestination: $destPath\nResult:\n$copyOut")),
                    isError = copyCode != 0
                )
            }

            "run_actions" -> {
                val actionsJson = args["actions"]?.jsonArray?.mapNotNull { if (it is JsonObject) it else null }
                    ?: throw IllegalArgumentException("Missing or invalid actions array")
                val delayMs = args["delay_between_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 300L

                val (allOk, report) = MacroManager.executeActions(actionsJson, delayMs)
                CallToolResult(
                    content = listOf(ContentItem(text = report)),
                    isError = !allOk
                )
            }

            "manage_macro" -> {
                val action = args["action"]?.jsonPrimitive?.content ?: "list"
                val name = args["macro_name"]?.jsonPrimitive?.contentOrNull ?: args["name"]?.jsonPrimitive?.contentOrNull
                val delayMs = args["delay_between_ms"]?.jsonPrimitive?.intOrNull?.toLong() ?: 300L

                when (action.lowercase()) {
                    "save" -> {
                        val macroName = name ?: throw IllegalArgumentException("Missing macro_name for save")
                        val actionsJson = args["actions"]?.jsonArray?.mapNotNull { if (it is JsonObject) it else null }
                            ?: throw IllegalArgumentException("Missing actions array for save")
                        val ok = MacroManager.saveMacro(context, macroName, actionsJson)
                        CallToolResult(content = listOf(ContentItem(text = if (ok) "Successfully saved macro '$macroName' with ${actionsJson.size} step(s)" else "Failed to save macro")))
                    }
                    "run", "replay" -> {
                        val macroName = name ?: throw IllegalArgumentException("Missing macro_name for run")
                        val macroActions = MacroManager.getMacro(context, macroName)
                            ?: return CallToolResult(content = listOf(ContentItem(text = "Macro '$macroName' not found")), isError = true)
                        val (allOk, report) = MacroManager.executeActions(macroActions, delayMs)
                        CallToolResult(content = listOf(ContentItem(text = "Executed macro '$macroName':\n$report")), isError = !allOk)
                    }
                    "list" -> {
                        val list = MacroManager.listMacros(context)
                        val text = if (list.isEmpty()) "No saved macros." else list.entries.joinToString("\n") { "• ${it.key}: ${it.value} step(s)" }
                        CallToolResult(content = listOf(ContentItem(text = text)))
                    }
                    "delete", "remove" -> {
                        val macroName = name ?: throw IllegalArgumentException("Missing macro_name for delete")
                        val ok = MacroManager.deleteMacro(context, macroName)
                        CallToolResult(content = listOf(ContentItem(text = if (ok) "Deleted macro '$macroName'" else "Macro '$macroName' not found")))
                    }
                    else -> throw IllegalArgumentException("Unknown manage_macro action: $action")
                }
            }

            else -> CallToolResult(
                content = listOf(ContentItem(text = "Unknown tool: $name")),
                isError = true
            )
        }
    }

    private fun getAvailableResources(): List<Resource> {
        return listOf(
            Resource(uri = "device://info", name = "Device Information", description = "Current device specs, display, and battery info"),
            Resource(uri = "device://current_app", name = "Current Foreground App", description = "Current focused package and activity"),
            Resource(uri = "device://installed_apps", name = "Installed Applications", description = "List of all user and system installed apps"),
            Resource(uri = "device://status", name = "Privilege Status", description = "Active status of LSPosed, Shizuku, Root, Accessibility, Notifications"),
            Resource(uri = "device://notifications", name = "Device Notifications", description = "Active and recent system notifications")
        )
    }

    private suspend fun readResource(uri: String): ReadResourceResult {
        return when (uri) {
            "device://notifications" -> {
                val service = McpNotificationListenerService.instance
                val rawList = if (service != null) {
                    val active = service.getActiveNotificationsList()
                    val history = McpNotificationListenerService.notificationHistory.toList()
                    (active + history).distinctBy { it.key ?: "${it.packageName}:${it.postTime}" }
                } else {
                    McpNotificationListenerService.queryNotificationsViaShell()
                }
                ReadResourceResult(
                    contents = listOf(
                        ResourceContent(uri = uri, mimeType = "application/json", text = jsonConfig.encodeToString(rawList))
                    )
                )
            }
            "device://info" -> {
                val info = privilegeManager.getDeviceInfo(context)
                ReadResourceResult(
                    contents = listOf(
                        ResourceContent(uri = uri, mimeType = "application/json", text = jsonConfig.encodeToString(info))
                    )
                )
            }
            "device://current_app" -> {
                val (pkg, act) = privilegeManager.getForegroundApp(context)
                val json = buildJsonObject {
                    put("package", JsonPrimitive(pkg))
                    put("activity", JsonPrimitive(act))
                }
                ReadResourceResult(
                    contents = listOf(
                        ResourceContent(uri = uri, mimeType = "application/json", text = jsonConfig.encodeToString(json))
                    )
                )
            }
            "device://installed_apps" -> {
                val apps = privilegeManager.getInstalledApps(includeSystem = false)
                ReadResourceResult(
                    contents = listOf(
                        ResourceContent(uri = uri, mimeType = "application/json", text = jsonConfig.encodeToString(apps))
                    )
                )
            }
            "device://status" -> {
                val status = privilegeManager.getPrivilegeStatus()
                ReadResourceResult(
                    contents = listOf(
                        ResourceContent(uri = uri, mimeType = "application/json", text = jsonConfig.encodeToString(status))
                    )
                )
            }
            else -> throw IllegalArgumentException("Resource not found: $uri")
        }
    }

    private fun getAvailablePrompts(): List<Prompt> {
        return listOf(
            Prompt(
                name = "ui_test_analyze",
                description = "System instructions and strategy for autonomous UI testing and screen navigation on Android."
            ),
            Prompt(
                name = "reverse_app_inspect",
                description = "Instructions for using LSPosed in-process tools to inspect and control app internal state."
            )
        )
    }

    private fun getPrompt(name: String): GetPromptResult {
        return when (name) {
            "ui_test_analyze" -> GetPromptResult(
                description = "UI Automation & Test Agent Prompt",
                messages = listOf(
                    PromptMessage(
                        role = "user",
                        content = ContentItem(
                            text = "You are an autonomous Android UI testing agent. Start by calling 'get_ui_hierarchy' and 'capture_screenshot' with annotate_som=true. Use element_id from the compact hierarchy to perform precise 'tap', 'long_press', 'input_text', 'swipe', or 'drag_and_drop' operations. When waiting for page loads or asynchronous changes, always prefer 'wait_for_element' (with optional auto_click=true) instead of repeated querying. To capture 2FA SMS codes or alerts, use 'get_notifications' or 'wait_for_notification'."
                        )
                    )
                )
            )
            "reverse_app_inspect" -> GetPromptResult(
                description = "App Reverse Engineering Prompt",
                messages = listOf(
                    PromptMessage(
                        role = "user",
                        content = ContentItem(
                            text = "Use 'hook_inspect_activity' and 'hook_get_view_tree' to analyze internal Activity classes and Compose views. Use 'hook_call_method' and 'hook_set_field' to interact directly with internal methods and variables in memory."
                        )
                    )
                )
            )
            else -> throw IllegalArgumentException("Unknown prompt: $name")
        }
    }

    private suspend fun dumpViaUiAutomator(): UiNode? = withContext(Dispatchers.IO) {
        val tmpFile = "/data/local/tmp/mcp_uidump.xml"
        val (code, _) = if (RootBridge.isRootAvailable()) {
            RootBridge.exec("rm -f $tmpFile && uiautomator dump $tmpFile")
        } else if (ShizukuBridge.hasPermission()) {
            ShizukuBridge.exec("sh", "-c", "rm -f $tmpFile && uiautomator dump $tmpFile")
        } else {
            return@withContext null
        }

        if (code != 0) return@withContext null

        val xmlContent = if (RootBridge.isRootAvailable()) {
            val (readCode, out) = RootBridge.exec("cat $tmpFile && rm -f $tmpFile")
            if (readCode == 0) out else null
        } else {
            val (readCode, out) = ShizukuBridge.exec("cat", tmpFile)
            ShizukuBridge.exec("rm", "-f", tmpFile)
            if (readCode == 0) out else null
        } ?: return@withContext null

        parseUiAutomatorXml(xmlContent)
    }

    private class MutableUiNode(
        val id: Int,
        val text: String?,
        val description: String?,
        val resourceId: String?,
        val className: String?,
        val packageName: String?,
        val bounds: RectBounds,
        val clickable: Boolean,
        val scrollable: Boolean,
        val children: MutableList<MutableUiNode> = mutableListOf()
    ) {
        fun toImmutable(): UiNode {
            return UiNode(
                id = id,
                text = text,
                description = description,
                resourceId = resourceId,
                className = className,
                packageName = packageName,
                bounds = bounds,
                clickable = clickable,
                scrollable = scrollable,
                children = children.map { it.toImmutable() }
            )
        }
    }

    private fun parseUiAutomatorXml(xml: String): UiNode? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            val stack = ArrayDeque<MutableUiNode>()
            var rootNode: MutableUiNode? = null
            var currentId = 1

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "node") {
                            val text = parser.getAttributeValue(null, "text") ?: ""
                            val resId = parser.getAttributeValue(null, "resource-id") ?: ""
                            val className = parser.getAttributeValue(null, "class") ?: ""
                            val pkg = parser.getAttributeValue(null, "package") ?: ""
                            val contentDesc = parser.getAttributeValue(null, "content-desc") ?: ""
                            val clickable = parser.getAttributeValue(null, "clickable") == "true"
                            val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
                            val boundsStr = parser.getAttributeValue(null, "bounds") ?: "[0,0][0,0]"

                            val boundsMatch = BOUNDS_REGEX.find(boundsStr)
                            val left = boundsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val top = boundsMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
                            val right = boundsMatch?.groupValues?.get(3)?.toIntOrNull() ?: 0
                            val bottom = boundsMatch?.groupValues?.get(4)?.toIntOrNull() ?: 0

                            val node = MutableUiNode(
                                id = if (clickable || text.isNotBlank() || contentDesc.isNotBlank()) currentId++ else 0,
                                className = className.ifBlank { null },
                                packageName = pkg.ifBlank { null },
                                text = text.ifBlank { null },
                                description = contentDesc.ifBlank { null },
                                resourceId = resId.ifBlank { null },
                                bounds = RectBounds(left, top, right, bottom),
                                clickable = clickable,
                                scrollable = scrollable
                            )

                            if (stack.isNotEmpty()) {
                                stack.peek()?.children?.add(node)
                            } else {
                                rootNode = node
                            }
                            stack.push(node)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "node" && stack.isNotEmpty()) {
                            stack.pop()
                        }
                    }
                }
                eventType = parser.next()
            }
            rootNode?.toImmutable()
        } catch (e: Throwable) {
            Log.e("McpProtocolHandler", "Failed to parse uiautomator xml: ${e.message}")
            null
        }
    }

    private fun parseSharedPrefsXml(xml: String, filterKey: String?): String {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val result = mutableMapOf<String, Any>()
            var eventType = parser.eventType
            var currentSetKey: String? = null
            var currentSetList: MutableList<String>? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name
                        val nameAttr = parser.getAttributeValue(null, "name")
                        val valueAttr = parser.getAttributeValue(null, "value")

                        when (tagName) {
                            "string" -> {
                                val text = parser.nextText()
                                if (nameAttr != null) result[nameAttr] = text
                            }
                            "int", "long" -> {
                                if (nameAttr != null && valueAttr != null) {
                                    result[nameAttr] = valueAttr.toLongOrNull() ?: valueAttr
                                }
                            }
                            "float" -> {
                                if (nameAttr != null && valueAttr != null) {
                                    result[nameAttr] = valueAttr.toFloatOrNull() ?: valueAttr
                                }
                            }
                            "boolean" -> {
                                if (nameAttr != null && valueAttr != null) {
                                    result[nameAttr] = valueAttr == "true"
                                }
                            }
                            "set" -> {
                                currentSetKey = nameAttr
                                currentSetList = mutableListOf()
                            }
                            "value" -> {
                                if (currentSetList != null) {
                                    val item = parser.nextText()
                                    currentSetList.add(item)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "set" && currentSetKey != null && currentSetList != null) {
                            result[currentSetKey] = currentSetList
                            currentSetKey = null
                            currentSetList = null
                        }
                    }
                }
                eventType = parser.next()
            }

            val filtered = if (filterKey != null) {
                result.filterKeys { it.contains(filterKey, ignoreCase = true) }
            } else {
                result
            }

            val jsonObject = buildJsonObject {
                filtered.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> put(k, JsonPrimitive(v))
                        is Number -> put(k, JsonPrimitive(v))
                        is String -> put(k, JsonPrimitive(v))
                        is List<*> -> put(k, buildJsonArray {
                            v.forEach { add(JsonPrimitive(it.toString())) }
                        })
                        else -> put(k, JsonPrimitive(v.toString()))
                    }
                }
            }
            jsonConfig.encodeToString(jsonObject)
        } catch (e: Throwable) {
            e.printStackTrace()
            xml
        }
    }

    private suspend fun querySqliteViaFramework(dbPath: String, query: String, limit: Int): String = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "mcp_query_${System.currentTimeMillis()}.db")
        try {
            val (copyCode, _) = RootBridge.exec("cp \"$dbPath\" \"${tempFile.absolutePath}\" && chmod 666 \"${tempFile.absolutePath}\"")
            if (copyCode != 0 || !tempFile.exists()) {
                return@withContext "Failed to copy database for inspection"
            }
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                tempFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            db.use { database ->
                val safeQuery = if (query.trim().uppercase().startsWith("SELECT") && !query.uppercase().contains("LIMIT")) "$query LIMIT $limit" else query
                val cursor = database.rawQuery(safeQuery, null)
                cursor.use { c ->
                    val colNames = c.columnNames
                    val rows = mutableListOf<Map<String, String?>>()
                    while (c.moveToNext()) {
                        val row = mutableMapOf<String, String?>()
                        for (i in colNames.indices) {
                            row[colNames[i]] = when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
                                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).toString()
                                android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                                android.database.Cursor.FIELD_TYPE_BLOB -> "[BLOB ${c.getBlob(i)?.size ?: 0} bytes]"
                                else -> c.getString(i)
                            }
                        }
                        rows.add(row)
                    }
                    val jsonArray = buildJsonArray {
                        rows.forEach { rowMap ->
                            add(buildJsonObject {
                                rowMap.forEach { (col, valStr) ->
                                    if (valStr == null) put(col, kotlinx.serialization.json.JsonNull)
                                    else put(col, JsonPrimitive(valStr))
                                }
                            })
                        }
                    }
                    jsonConfig.encodeToString(jsonArray)
                }
            }
        } catch (e: Throwable) {
            "SQLite query error: ${e.message}"
        } finally {
            try { tempFile.delete() } catch (_: Throwable) {}
        }
    }
}

