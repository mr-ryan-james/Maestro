package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import maestro.cli.session.MaestroSessionManager

object RunFlowWithDiagnosticsTool {

    fun create(
        sessionManager: MaestroSessionManager,
    ): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_flow_with_diagnostics",
                description = "Run a Maestro flow and capture hierarchy and screenshot diagnostics.",
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
                            "flow_path",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "flow_yaml",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "env",
                            buildJsonObject { put("type", JsonPrimitive("object")) },
                        )
                        put(
                            "capture_diagnostics_on_success",
                            buildJsonObject { put("type", JsonPrimitive("boolean")) },
                        )
                        put(
                            "debug_output_dir",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "test_output_dir",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                    },
                    required = emptyList(),
                ),
            ),
        ) { request ->
            val payload = runCatching {
                ProjectToolSupport.runFlowWithDiagnostics(sessionManager, request)
            }.getOrElse { error ->
                return@RegisteredTool CallToolResult(
                    content = listOf(TextContent(error.message ?: "run_flow_with_diagnostics failed")),
                    isError = true,
                )
            }

            CallToolResult(content = listOf(TextContent(payload.toString())))
        }
    }
}
