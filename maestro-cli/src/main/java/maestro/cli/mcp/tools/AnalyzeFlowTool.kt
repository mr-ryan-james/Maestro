package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object AnalyzeFlowTool {

    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "analyze_flow",
                description = "Parse a Maestro flow and return command, selector, and file-reference analysis.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        put(
                            "project_root",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "flow_path",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                    },
                    required = listOf("flow_path"),
                ),
            ),
        ) { request ->
            try {
                CallToolResult(content = listOf(TextContent(ProjectToolSupport.analyzeFlow(request).toString())))
            } catch (error: IllegalStateException) {
                CallToolResult(
                    content = listOf(TextContent(error.message ?: "flow_path is required")),
                    isError = true,
                )
            }
        }
    }
}
