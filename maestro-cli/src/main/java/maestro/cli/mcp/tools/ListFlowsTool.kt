package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object ListFlowsTool {

    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "list_flows",
                description = "Scan a project for executable Maestro flows and summarize them.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        put(
                            "project_root",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                            },
                        )
                    },
                    required = emptyList(),
                ),
            ),
        ) { request ->
            CallToolResult(content = listOf(TextContent(ProjectToolSupport.listFlows(request).toString())))
        }
    }
}
