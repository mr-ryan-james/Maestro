package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import maestro.cli.session.MaestroSessionManager

object SuggestSelectorsTool {

    fun create(
        sessionManager: MaestroSessionManager,
    ): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "suggest_selectors",
                description = "Suggest Maestro selectors from the current device hierarchy and the target project.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        put(
                            "project_root",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "session_id",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "device_id",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "device_name_contains",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "platform",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "query",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "max_suggestions",
                            buildJsonObject { put("type", JsonPrimitive("integer")) },
                        )
                        put(
                            "hierarchy_csv",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                    },
                    required = listOf("query"),
                ),
            ),
        ) { request ->
            val payload = runCatching {
                ProjectToolSupport.suggestSelectors(sessionManager, request)
            }.getOrElse { error ->
                return@RegisteredTool CallToolResult(
                    content = listOf(TextContent(error.message ?: "suggest_selectors failed")),
                    isError = true,
                )
            }

            CallToolResult(content = listOf(TextContent(payload.toString())))
        }
    }
}
