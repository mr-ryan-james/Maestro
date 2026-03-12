package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.InputTextCommand

object InputTextTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "input_text",
                description = "Input text into the currently focused text field",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to input text on")
                        }
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Optional hot session id returned by open_session")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "The text to input")
                        }
                    },
                    required = listOf("text")
                )
            )
        ) { request ->
            try {
                val deviceId = ToolSupport.resolveDeviceId(request)
                val text = ToolSupport.requiredString(request, "text")

                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                        isError = true
                    )
                }
                if (text == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("text is required")),
                        isError = true
                    )
                }

                val result = ToolSupport.runCommand(
                    sessionManager = sessionManager,
                    request = request,
                    deviceId = deviceId,
                    command = InputTextCommand(
                        text = text,
                        label = null,
                        optional = false,
                    ),
                    message = "Text input successful",
                    extra = { put("text", text) },
                )


                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to input text: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
