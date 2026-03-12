package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.mcp.McpSessionRegistry
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.WorkingDirectory
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.util.Env.withDefaultEnvVars
import kotlinx.coroutines.runBlocking

object RunFlowTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_flow",
                description = """
                    Use this when interacting with a device and running adhoc commands, preferably one at a time.

                    Whenever you're exploring an app, testing out commands or debugging, prefer using this tool over creating temp files and using run_flow_files.

                    Run a set of Maestro commands (one or more). This can be a full maestro script (including headers), a set of commands (one per line) or simply a single command (eg '- tapOn: 123').

                    If this fails due to no device running, please ask the user to start a device!

                    If you don't have an up-to-date view hierarchy or screenshot on which to execute the commands, please call inspect_view_hierarchy first, instead of blindly guessing.

                    *** You don't need to call check_syntax before executing this, as syntax will be checked as part of the execution flow. ***

                    Use the `inspect_view_hierarchy` tool to retrieve the current view hierarchy and use it to execute commands on the device.
                    Use the `cheat_sheet` tool to retrieve a summary of Maestro's flow syntax before using any of the other tools.

                    Examples of valid inputs:
                    ```
                    - tapOn: 123
                    ```

                    ```
                    appId: any
                    ---
                    - tapOn: 123
                    ```

                    ```
                    appId: any
                    # other headers here
                    ---
                    - tapOn: 456
                    - scroll
                    # other commands here
                    ```
                """.trimIndent(),
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to run the flow on")
                        }
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Optional hot session id returned by open_session")
                        }
                        putJsonObject("flow_yaml") {
                            put("type", "string")
                            put("description", "YAML-formatted Maestro flow content to execute")
                        }
                        putJsonObject("env") {
                            put("type", "object")
                            put("description", "Optional environment variables to inject into the flow (e.g., {\"APP_ID\": \"com.example.app\", \"LANGUAGE\": \"en\"})")
                            putJsonObject("additionalProperties") {
                                put("type", "string")
                            }
                        }
                    },
                    required = listOf("flow_yaml")
                )
            )
        ) { request ->
            try {
                val deviceId = ToolSupport.resolveDeviceId(request)
                val flowYaml = ToolSupport.requiredString(request, "flow_yaml")
                val envParam = request.arguments["env"]?.jsonObject

                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                        isError = true
                    )
                }
                if (flowYaml == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("flow_yaml is required")),
                        isError = true
                    )
                }
                
                // Parse environment variables from JSON object
                val env = envParam?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                
                val result = ToolSupport.withSession(sessionManager, request, deviceId) { session ->
                    val inlineFlowPath = WorkingDirectory.baseDir
                        .toPath()
                        .resolve(".maestro-mcp-inline-flow.yaml")
                    val commands = CompiledFlowCache.readInlineFlow(inlineFlowPath, flowYaml)
                    val finalEnv = env
                        .withInjectedShellEnvVars()
                        .withDefaultEnvVars(inlineFlowPath.toFile(), deviceId)
                    val commandsWithEnv = commands.withEnv(finalEnv)

                    val orchestra = Orchestra(session.maestro)

                    runBlocking {
                        orchestra.runFlow(commandsWithEnv)
                    }

                    buildJsonObject {
                        put("success", true)
                        put("device_id", deviceId)
                        ToolSupport.optionalSessionId(request)?.let { put("session_id", it) }
                        put("commands_executed", commands.size)
                        put("message", "Flow executed successfully")
                        if (finalEnv.isNotEmpty()) {
                            putJsonObject("env_vars") {
                                finalEnv.forEach { (key, value) ->
                                    put(key, value)
                                }
                            }
                        }
                    }.toString()
                }
                McpSessionRegistry.invalidateHierarchy(ToolSupport.optionalSessionId(request))
                
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to run flow: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
