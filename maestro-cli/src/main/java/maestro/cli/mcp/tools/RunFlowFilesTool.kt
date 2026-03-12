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

object RunFlowFilesTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_flow_files",
                description = "Run one or more full Maestro test files. If no device is running, you'll need to start a device first. If the command fails using a relative path, try using an absolute path.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to run the flows on")
                        }
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Optional hot session id returned by open_session")
                        }
                        putJsonObject("flow_files") {
                            put("type", "string")
                            put("description", "Comma-separated file paths to YAML flow files to execute (e.g., 'flow1.yaml,flow2.yaml')")
                        }
                        putJsonObject("env") {
                            put("type", "object")
                            put("description", "Optional environment variables to inject into the flows (e.g., {\"APP_ID\": \"com.example.app\", \"LANGUAGE\": \"tr\", \"COUNTRY\": \"TR\"})")
                            putJsonObject("additionalProperties") {
                                put("type", "string")
                            }
                        }
                    },
                    required = listOf("flow_files")
                )
            )
        ) { request ->
            try {
                val deviceId = ToolSupport.resolveDeviceId(request)
                val flowFilesString = ToolSupport.requiredString(request, "flow_files")
                val envParam = request.arguments["env"]?.jsonObject

                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                        isError = true
                    )
                }
                if (flowFilesString == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("flow_files is required")),
                        isError = true
                    )
                }
                
                val flowFiles = flowFilesString.split(",").map { it.trim() }
                
                if (flowFiles.isEmpty()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("At least one flow file must be provided")),
                        isError = true
                    )
                }
                
                // Parse environment variables from JSON object
                val env = envParam?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                
                // Resolve all flow files to File objects once
                val resolvedFiles = flowFiles.map { WorkingDirectory.resolve(it) }
                // Validate all files exist before executing
                val missingFiles = resolvedFiles.filter { !it.exists() }
                if (missingFiles.isNotEmpty()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Files not found: ${missingFiles.joinToString(", ") { it.absolutePath }}")),
                        isError = true
                    )
                }
                
                val result = ToolSupport.withSession(sessionManager, request, deviceId) { session ->
                    val orchestra = Orchestra(session.maestro)
                    val results = mutableListOf<Map<String, Any>>()
                    var totalCommands = 0
                    
                    for (fileObj in resolvedFiles) {
                        try {
                            val commands = CompiledFlowCache.readFlowFile(fileObj.toPath())
                            val finalEnv = env
                                .withInjectedShellEnvVars()
                                .withDefaultEnvVars(fileObj, deviceId)
                            val commandsWithEnv = commands.withEnv(finalEnv)
                            
                            runBlocking {
                                orchestra.runFlow(commandsWithEnv)
                            }
                            results.add(mapOf(
                                "file" to fileObj.absolutePath,
                                "success" to true,
                                "commands_executed" to commands.size,
                                "message" to "Flow executed successfully"
                            ))
                            totalCommands += commands.size
                        } catch (e: Exception) {
                            results.add(mapOf(
                                "file" to fileObj.absolutePath,
                                "success" to false,
                                "error" to (e.message ?: "Unknown error"),
                                "message" to "Flow execution failed"
                            ))
                        }
                    }
                    
                    val finalEnv = env
                        .withInjectedShellEnvVars()
                        .withDefaultEnvVars(deviceId = deviceId)
                    
                    buildJsonObject {
                        put("success", results.all { (it["success"] as Boolean) })
                        put("device_id", deviceId)
                        ToolSupport.optionalSessionId(request)?.let { put("session_id", it) }
                        put("total_files", flowFiles.size)
                        put("total_commands_executed", totalCommands)
                        putJsonArray("results") {
                            results.forEach { result ->
                                addJsonObject {
                                    result.forEach { (key, value) ->
                                        when (value) {
                                            is String -> put(key, value)
                                            is Boolean -> put(key, value)
                                            is Int -> put(key, value)
                                            else -> put(key, value.toString())
                                        }
                                    }
                                }
                            }
                        }
                        if (finalEnv.isNotEmpty()) {
                            putJsonObject("env_vars") {
                                finalEnv.forEach { (key, value) ->
                                    put(key, value)
                                }
                            }
                        }
                        put("message", if (results.all { (it["success"] as Boolean) }) 
                            "All flows executed successfully" 
                        else 
                            "Some flows failed to execute")
                    }.toString()
                }
                McpSessionRegistry.invalidateHierarchy(ToolSupport.optionalSessionId(request))
                
                // Check if any flows failed and return isError accordingly
                val anyFlowsFailed = result.contains("\"success\":false")                
                CallToolResult(
                    content = listOf(TextContent(result)),
                    isError = anyFlowsFailed
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to run flow files: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
