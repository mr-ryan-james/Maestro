package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*

object QueryDocsTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "query_docs",
                description = "Query bundled Maestro documentation without requiring any cloud token. Returns relevant syntax, command, and troubleshooting guidance.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("question") {
                            put("type", "string")
                            put("description", "The question to ask about Maestro documentation")
                        }
                    },
                    required = listOf("question"),
                ),
            ),
        ) { request ->
            val question = ToolSupport.requiredString(request, "question")
            if (question == null) {
                return@RegisteredTool CallToolResult(
                    content = listOf(TextContent("question parameter is required")),
                    isError = true,
                )
            }

            CallToolResult(
                content = listOf(TextContent(LocalDocsRepository.search(question))),
            )
        }
    }
}
