package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.TreeNode
import kotlinx.coroutines.runBlocking

object InspectViewHierarchyTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "inspect_view_hierarchy",
                description = "Get the nested view hierarchy of the current screen in CSV format. Returns UI elements " +
                    "with bounds coordinates for interaction. Use this to understand screen layout, find specific elements " +
                    "by text/id, or locate interactive components. Elements include bounds (x,y,width,height), text content, " +
                    "resource IDs, and interaction states (clickable, enabled, checked).",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to get hierarchy from")
                        }
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Optional hot session id returned by open_session")
                        }
                        putJsonObject("refresh") {
                            put("type", "boolean")
                            put("description", "Refresh hierarchy instead of using cached session hierarchy")
                        }
                    },
                    required = emptyList()
                )
            )
        ) { request ->
            try {
                val deviceId = ToolSupport.resolveDeviceId(request)
                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                        isError = true
                    )
                }
                
                val viewHierarchy = ToolSupport.loadViewHierarchy(
                    sessionManager = sessionManager,
                    request = request,
                    deviceId = deviceId,
                    refresh = ToolSupport.optionalBoolean(request, "refresh") ?: false,
                )
                val result = ViewHierarchyFormatters.extractCsvOutput(viewHierarchy.root)
                
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to inspect UI: ${e.message}")),
                    isError = true
                )
            }
        }
    }
    
}
