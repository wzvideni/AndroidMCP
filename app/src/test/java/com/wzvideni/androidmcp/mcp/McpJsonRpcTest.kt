package com.wzvideni.androidmcp.mcp

import com.wzvideni.androidmcp.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class McpJsonRpcTest {

    @Test
    fun testJsonRpcRequestDeserialization() {
        val json = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val req = jsonConfig.decodeFromString<JsonRpcRequest>(json)
        assertEquals("2.0", req.jsonrpc)
        assertEquals(1L, req.id?.jsonPrimitive?.longOrNull)
        assertEquals("ping", req.method)
        assertNull(req.params)
    }

    @Test
    fun testJsonRpcResponseOmitNullFields() {
        val resp = JsonRpcResponse(
            jsonrpc = "2.0",
            id = JsonPrimitive(42),
            result = JsonPrimitive("pong")
        )
        val serialized = jsonConfig.encodeToString(resp)
        assertTrue(serialized.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(serialized.contains("\"id\":42"))
        assertTrue(serialized.contains("\"result\":\"pong\""))
        assertFalse(serialized.contains("\"error\":null"))
    }

    @Test
    fun testInitializeResultSerialization() {
        val init = InitializeResult()
        val json = jsonConfig.encodeToString(init)
        assertTrue(json.contains("\"protocolVersion\":\"2024-11-05\""))
        assertTrue(json.contains("\"name\":\"AndroidMCP\""))
        assertTrue(json.contains("\"capabilities\":{"))
        assertFalse(json.contains("\"roots\":null"))
        assertFalse(json.contains("\"sampling\":null"))
    }

    @Test
    fun testToolInputSchemaSerialization() {
        val props = buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("action name"))
            })
        }
        val schema = ToolInputSchema(
            properties = props,
            required = listOf("action")
        )
        val json = jsonConfig.encodeToString(schema)
        assertTrue(json.contains("\"type\":\"object\""))
        assertTrue(json.contains("\"action\":{\"type\":\"string\",\"description\":\"action name\"}"))
        assertTrue(json.contains("\"required\":[\"action\"]"))
    }
}
