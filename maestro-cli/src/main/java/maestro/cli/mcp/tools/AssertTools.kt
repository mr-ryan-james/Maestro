package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.AssertCommand

private fun assertInputSchema(): Tool.Input {
    return Tool.Input(
        properties = buildJsonObject {
            putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device") }
            putJsonObject("session_id") {
                put("type", "string")
                put("description", "Optional hot session id returned by open_session")
            }
            putJsonObject("text") { put("type", "string") }
            putJsonObject("id") { put("type", "string") }
            putJsonObject("index") { put("type", "integer") }
            putJsonObject("use_fuzzy_matching") { put("type", "boolean") }
            putJsonObject("enabled") { put("type", "boolean") }
            putJsonObject("checked") { put("type", "boolean") }
            putJsonObject("focused") { put("type", "boolean") }
            putJsonObject("selected") { put("type", "boolean") }
            putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Optional timeout in milliseconds") }
        },
        required = emptyList(),
    )
}

object AssertVisibleTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "assert_visible",
                description = "Assert that an element is visible on screen.",
                inputSchema = assertInputSchema(),
            ),
        ) { request ->
            val deviceId = ToolSupport.resolveDeviceId(request)
            val selector = ToolSupport.buildSelector(request, requireSelector = true)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(
                    listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                    isError = true,
                )
            }
            if (selector == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("A selector is required via text or id")), isError = true)
            }
            val timeoutMs = ToolSupport.optionalLong(request, "timeout_ms")
            try {
                val result = ToolSupport.runCommand(
                    sessionManager = sessionManager,
                    request = request,
                    deviceId = deviceId,
                    command = AssertCommand(visible = selector, timeout = timeoutMs),
                    message = "Visibility assertion passed",
                    extra = { put("timeout_ms", timeoutMs ?: 0L) },
                )
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(listOf(TextContent("Visibility assertion failed: ${e.message}")), isError = true)
            }
        }
    }
}

object AssertNotVisibleTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "assert_not_visible",
                description = "Assert that an element is not visible on screen.",
                inputSchema = assertInputSchema(),
            ),
        ) { request ->
            val deviceId = ToolSupport.resolveDeviceId(request)
            val selector = ToolSupport.buildSelector(request, requireSelector = true)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(
                    listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                    isError = true,
                )
            }
            if (selector == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("A selector is required via text or id")), isError = true)
            }
            val timeoutMs = ToolSupport.optionalLong(request, "timeout_ms")
            try {
                val result = ToolSupport.runCommand(
                    sessionManager = sessionManager,
                    request = request,
                    deviceId = deviceId,
                    command = AssertCommand(notVisible = selector, timeout = timeoutMs),
                    message = "Non-visibility assertion passed",
                    extra = { put("timeout_ms", timeoutMs ?: 0L) },
                )
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(listOf(TextContent("Non-visibility assertion failed: ${e.message}")), isError = true)
            }
        }
    }
}
