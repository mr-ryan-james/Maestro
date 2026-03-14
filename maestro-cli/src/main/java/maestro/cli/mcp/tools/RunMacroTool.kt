package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import maestro.cli.daemon.runMacro
import maestro.cli.session.MaestroSessionManager

object RunMacroTool {

    fun create(
        sessionManager: MaestroSessionManager,
    ): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_macro",
                description = "Run a typed automation macro using the shared Maestro control plane.",
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
                            "owner",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "label",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "driver_host_port",
                            buildJsonObject { put("type", JsonPrimitive("integer")) },
                        )
                        put(
                            "ttl_ms",
                            buildJsonObject { put("type", JsonPrimitive("integer")) },
                        )
                        put(
                            "app_id",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "macro",
                            buildJsonObject { put("type", JsonPrimitive("string")) },
                        )
                        put(
                            "args",
                            buildJsonObject { put("type", JsonPrimitive("object")) },
                        )
                    },
                    required = listOf("macro"),
                ),
            ),
        ) { request ->
            val payload = runCatching {
                ProjectToolSupport.withDiagnosticSession(sessionManager, request) { sessionHandle ->
                    runBlocking {
                        runMacro(sessionHandle, request.arguments)
                    }
                }
            }.getOrElse { error ->
                return@RegisteredTool CallToolResult(
                    content = listOf(TextContent(error.message ?: "run_macro failed")),
                    isError = true,
                )
            }

            CallToolResult(content = listOf(TextContent(payload.toString())))
        }
    }
}
