package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.StopAppCommand

object StopAppTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "stop_app",
                description = "Stop an application on the connected device",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to stop the app on")
                        }
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Optional hot session id returned by open_session")
                        }
                        putJsonObject("appId") {
                            put("type", "string")
                            put("description", "Bundle ID or app ID to stop")
                        }
                    },
                    required = listOf("appId")
                )
            )
        ) { request ->
            try {
                val deviceId = ToolSupport.resolveDeviceId(request)
                val appId = ToolSupport.requiredString(request, "appId")

                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                        isError = true
                    )
                }
                if (appId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("appId is required")),
                        isError = true
                    )
                }

                val result = ToolSupport.runCommand(
                    sessionManager = sessionManager,
                    request = request,
                    deviceId = deviceId,
                    command = StopAppCommand(
                        appId = appId,
                        label = null,
                        optional = false,
                    ),
                    message = "App stopped successfully",
                    extra = { put("app_id", appId) },
                )

                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to stop app: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
