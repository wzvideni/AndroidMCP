package com.wzvideni.androidmcp.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalSerializationApi::class)
val jsonConfig = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

// ----------------------------------------------------
// JSON-RPC 2.0 Base Protocol
// ----------------------------------------------------

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

// ----------------------------------------------------
// MCP Protocol Initialization & Capabilities
// ----------------------------------------------------

@Serializable
data class InitializeResult(
    val protocolVersion: String = "2024-11-05",
    val capabilities: ServerCapabilities = ServerCapabilities(),
    val serverInfo: Implementation = Implementation(
        name = "AndroidMCP",
        version = "1.0.0"
    )
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability = ToolsCapability(),
    val resources: ResourcesCapability = ResourcesCapability(),
    val prompts: PromptsCapability = PromptsCapability(),
    val logging: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean = true
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean = true,
    val listChanged: Boolean = true
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean = true
)

@Serializable
data class Implementation(
    val name: String,
    val version: String
)

// ----------------------------------------------------
// MCP Tools Specification
// ----------------------------------------------------

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val inputSchema: ToolInputSchema
)

@Serializable
data class ToolInputSchema(
    val type: String = "object",
    val properties: JsonObject = JsonObject(emptyMap()),
    val required: List<String> = emptyList()
)

@Serializable
data class ListToolsResult(
    val tools: List<Tool>
)

@Serializable
data class CallToolResult(
    val content: List<ContentItem>,
    val isError: Boolean = false
)

@Serializable
data class ContentItem(
    val type: String = "text", // "text", "image", "resource"
    val text: String? = null,
    val data: String? = null, // base64 for image
    val mimeType: String? = null
)

// ----------------------------------------------------
// MCP Resources Specification
// ----------------------------------------------------

@Serializable
data class Resource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = "application/json"
)

@Serializable
data class ListResourcesResult(
    val resources: List<Resource>
)

@Serializable
data class ReadResourceResult(
    val contents: List<ResourceContent>
)

@Serializable
data class ResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
)

// ----------------------------------------------------
// MCP Prompts Specification
// ----------------------------------------------------

@Serializable
data class Prompt(
    val name: String,
    val description: String? = null,
    val arguments: List<PromptArgument> = emptyList()
)

@Serializable
data class PromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false
)

@Serializable
data class ListPromptsResult(
    val prompts: List<Prompt>
)

@Serializable
data class GetPromptResult(
    val description: String? = null,
    val messages: List<PromptMessage>
)

@Serializable
data class PromptMessage(
    val role: String, // "user", "assistant"
    val content: ContentItem
)

// ----------------------------------------------------
// UI Hierarchy & Domain Data Structures
// ----------------------------------------------------

@Serializable
data class RectBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun toShortString(): String = "[$left,$top][$right,$bottom]"
}

@Serializable
data class UiNode(
    val id: Int, // Numeric Set-of-Mark ID
    val text: String? = null,
    val description: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val bounds: RectBounds = RectBounds(),
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val visible: Boolean = true,
    val children: List<UiNode> = emptyList(),
    val extras: Map<String, String>? = null
) {
    /**
     * Compact one-line representation for Agent prompt context
     */
    fun toCompactString(): String {
        val parts = mutableListOf<String>()
        parts.add("[$id]")
        if (!resourceId.isNullOrBlank()) {
            val shortId = resourceId.substringAfter(":id/")
            parts.add("id=$shortId")
        }
        val type = className?.substringAfterLast(".") ?: "View"
        parts.add(type)
        if (!text.isNullOrBlank()) parts.add("\"${text.replace("\n", " ").take(40)}\"")
        if (!description.isNullOrBlank()) parts.add("desc=\"${description.replace("\n", " ").take(30)}\"")
        if (clickable) parts.add("[clickable]")
        if (editable) parts.add("[editable]")
        if (scrollable) parts.add("[scrollable]")
        if (checked) parts.add("[checked]")
        parts.add(bounds.toShortString())
        return parts.joinToString(" ")
    }
}

@Serializable
data class DeviceInfo(
    val brand: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val densityDpi: Int,
    val orientation: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val currentPackage: String?,
    val currentActivity: String?,
    val ipAddresses: List<String>
)

@Serializable
data class NotificationItem(
    val id: Int = 0,
    val key: String? = null,
    val packageName: String,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val postTime: Long = System.currentTimeMillis(),
    val isClearable: Boolean = true
)

@Serializable
data class PrivilegeStatus(
    val lsposedActive: Boolean,
    val hookedAppsCount: Int,
    val hookedApps: List<String> = emptyList(),
    val shizukuRunning: Boolean,
    val shizukuAuthorized: Boolean,
    val rootAvailable: Boolean,
    val accessibilityActive: Boolean,
    val notificationActive: Boolean = false
)

// ----------------------------------------------------
// Hook IPC Protocol Models
// ----------------------------------------------------

@Serializable
data class HookIpcRequest(
    val action: String, // "GET_ACTIVITY_INFO", "GET_VIEW_TREE", "GET_COMPOSE_TREE", "CALL_METHOD", "SET_FIELD", "CLICK_VIEW", "LONG_CLICK_VIEW", "GET_FRAGMENTS", "TRACE_METHOD", "PING"
    val subAction: String? = null,
    val targetPackage: String? = null,
    val targetActivity: String? = null,
    val targetId: String? = null,
    val viewId: Int? = null,
    val className: String? = null,
    val methodName: String? = null,
    val fieldName: String? = null,
    val fieldValue: String? = null,
    val params: List<String> = emptyList()
)

@Serializable
data class HookIpcResponse(
    val success: Boolean,
    val message: String? = null,
    val data: JsonElement? = null,
    val uiNode: UiNode? = null
)
