package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object ValidateTestIdsTool {

    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "validate_testids",
                description = "Scan a project for test IDs and validate the selectors used by a Maestro flow or an explicit list.",
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
                        put(
                            "test_ids",
                            buildJsonObject {
                                put("type", JsonPrimitive("array"))
                            },
                        )
                    },
                    required = emptyList(),
                ),
            ),
        ) { request ->
            CallToolResult(content = listOf(TextContent(ProjectToolSupport.validateTestIds(request).toString())))
        }
    }
}
