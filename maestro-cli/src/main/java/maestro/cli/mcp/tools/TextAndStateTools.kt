package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.SetLocationCommand

object EraseTextTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "erase_text",
                description = "Erase text from the currently focused input field.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device") }
                        putJsonObject("characters_to_erase") { put("type", "integer"); put("description", "Optional number of characters to erase") }
                    },
                    required = listOf("device_id"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id is required")), isError = true)
            }
            val count = ToolSupport.optionalInt(request, "characters_to_erase")
            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = EraseTextCommand(count),
                        message = "Text erased successfully",
                        extra = { put("characters_to_erase", count ?: 0) },
                    ),
                )),
            )
        }
    }
}

object PressKeyTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "press_key",
                description = "Press a hardware or software key supported by Maestro.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device") }
                        putJsonObject("key") { put("type", "string"); put("description", "Key name such as enter, tab, backspace, back, home, escape" ) }
                    },
                    required = listOf("device_id", "key"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            val rawKey = ToolSupport.requiredString(request, "key")
            val key = ToolSupport.parseKeyCode(rawKey)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id is required")), isError = true)
            }
            if (key == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("Unsupported key: ${rawKey ?: ""}")), isError = true)
            }
            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = PressKeyCommand(key),
                        message = "Key press executed successfully",
                        extra = { put("key", key.name) },
                    ),
                )),
            )
        }
    }
}

object ClearStateTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "clear_state",
                description = "Clear the application state for a specific app id.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device") }
                        putJsonObject("app_id") { put("type", "string"); put("description", "The app id / bundle id to clear") }
                    },
                    required = listOf("device_id", "app_id"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            val appId = ToolSupport.requiredString(request, "app_id")
            if (deviceId == null || appId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("Both device_id and app_id are required")), isError = true)
            }
            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = ClearStateCommand(appId),
                        message = "App state cleared successfully",
                        extra = { put("app_id", appId) },
                    ),
                )),
            )
        }
    }
}

object SetLocationTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "set_location",
                description = "Mock the device location to a latitude and longitude.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device") }
                        putJsonObject("latitude") { put("type", "number"); put("description", "Latitude" ) }
                        putJsonObject("longitude") { put("type", "number"); put("description", "Longitude" ) }
                    },
                    required = listOf("device_id", "latitude", "longitude"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            val latitude = ToolSupport.optionalDouble(request, "latitude")
            val longitude = ToolSupport.optionalDouble(request, "longitude")
            if (deviceId == null || latitude == null || longitude == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id, latitude, and longitude are required")), isError = true)
            }
            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = SetLocationCommand(latitude.toString(), longitude.toString()),
                        message = "Location set successfully",
                        extra = {
                            put("latitude", latitude)
                            put("longitude", longitude)
                        },
                    ),
                )),
            )
        }
    }
}

object OpenLinkTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "open_link",
                description = "Open a deep link or URL on the current device.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device") }
                        putJsonObject("link") { put("type", "string"); put("description", "The link or deep link to open") }
                        putJsonObject("auto_verify") { put("type", "boolean"); put("description", "Whether to request auto verification") }
                        putJsonObject("browser") { put("type", "boolean"); put("description", "Whether to force browser open") }
                    },
                    required = listOf("device_id", "link"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            val link = ToolSupport.requiredString(request, "link")
            if (deviceId == null || link == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("Both device_id and link are required")), isError = true)
            }
            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = OpenLinkCommand(
                            link = link,
                            autoVerify = ToolSupport.optionalBoolean(request, "auto_verify"),
                            browser = ToolSupport.optionalBoolean(request, "browser"),
                        ),
                        message = "Link opened successfully",
                        extra = { put("link", link) },
                    ),
                )),
            )
        }
    }
}

object CopyTextFromTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "copy_text_from",
                description = "Read text content from an element matched by selector.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device") }
                        putJsonObject("text") { put("type", "string") }
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("index") { put("type", "integer") }
                        putJsonObject("use_fuzzy_matching") { put("type", "boolean") }
                        putJsonObject("enabled") { put("type", "boolean") }
                        putJsonObject("checked") { put("type", "boolean") }
                        putJsonObject("focused") { put("type", "boolean") }
                        putJsonObject("selected") { put("type", "boolean") }
                    },
                    required = listOf("device_id"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            val selector = ToolSupport.buildSelector(request, requireSelector = true)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id is required")), isError = true)
            }
            if (selector == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("A selector is required via text or id")), isError = true)
            }

            try {
                val result = ToolSupport.executeSession(sessionManager, deviceId) { session ->
                    val hierarchy = session.maestro.viewHierarchy()
                    val matches = ToolSupport.findMatchingNodes(hierarchy, request)
                    val node = matches.firstOrNull()
                        ?: throw IllegalArgumentException("No matching element found")

                    val copiedText = node.attributes["text"]
                        ?: node.attributes["hintText"]
                        ?: node.attributes["accessibilityText"]
                        ?: throw IllegalArgumentException("Element does not expose text content")

                    ToolSupport.successJson(
                        deviceId = deviceId,
                        message = "Copied text successfully",
                    ) {
                        put("text", copiedText)
                        put("match_count", matches.size)
                    }
                }
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to copy text: ${e.message}")),
                    isError = true,
                )
            }
        }
    }
}
