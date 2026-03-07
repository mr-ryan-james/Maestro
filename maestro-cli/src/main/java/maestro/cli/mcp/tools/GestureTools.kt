package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SwipeCommand

object HideKeyboardTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "hide_keyboard",
                description = "Dismiss the software keyboard on the current device.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to dismiss the keyboard on")
                        }
                    },
                    required = listOf("device_id"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id is required")), isError = true)
            }

            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = HideKeyboardCommand(),
                        message = "Keyboard hidden successfully",
                    ),
                )),
            )
        }
    }
}

object ScrollTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "scroll",
                description = "Scroll vertically on the current screen.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to scroll on")
                        }
                    },
                    required = listOf("device_id"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id is required")), isError = true)
            }

            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = ScrollCommand(),
                        message = "Scroll executed successfully",
                    ),
                )),
            )
        }
    }
}

object SwipeTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "swipe",
                description = "Swipe on the current device using a direction, relative coordinates, absolute coordinates, or an element selector.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device to swipe on") }
                        putJsonObject("direction") { put("type", "string"); put("description", "Swipe direction: up, down, left, or right") }
                        putJsonObject("duration_ms") { put("type", "integer"); put("description", "Swipe duration in milliseconds") }
                        putJsonObject("wait_to_settle_timeout_ms") { put("type", "integer"); put("description", "Optional settle timeout in milliseconds") }
                        putJsonObject("start_x") { put("type", "integer") }
                        putJsonObject("start_y") { put("type", "integer") }
                        putJsonObject("end_x") { put("type", "integer") }
                        putJsonObject("end_y") { put("type", "integer") }
                        putJsonObject("start_relative") { put("type", "string"); put("description", "Relative start point, e.g. 50%,80%") }
                        putJsonObject("end_relative") { put("type", "string"); put("description", "Relative end point, e.g. 50%,20%") }
                        putJsonObject("text") { put("type", "string") }
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("index") { put("type", "integer") }
                        putJsonObject("use_fuzzy_matching") { put("type", "boolean") }
                    },
                    required = listOf("device_id"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id is required")), isError = true)
            }

            val direction = ToolSupport.parseSwipeDirection(ToolSupport.optionalString(request, "direction"))
            val selector = ToolSupport.buildSelector(request, requireSelector = false)
            val start = ToolSupport.pointFrom(request, "start")
            val end = ToolSupport.pointFrom(request, "end")
            val startRelative = ToolSupport.optionalString(request, "start_relative")
            val endRelative = ToolSupport.optionalString(request, "end_relative")
            val durationMs = ToolSupport.optionalLong(request, "duration_ms") ?: 400L
            val settleTimeoutMs = ToolSupport.optionalInt(request, "wait_to_settle_timeout_ms")

            val hasValidMode =
                direction != null ||
                    (start != null && end != null) ||
                    (startRelative != null && endRelative != null)

            if (!hasValidMode) {
                return@RegisteredTool CallToolResult(
                    listOf(TextContent("Provide either direction, start/end coordinates, or start/end relative coordinates")),
                    isError = true,
                )
            }

            val command = SwipeCommand(
                direction = direction,
                startPoint = start,
                endPoint = end,
                elementSelector = selector,
                startRelative = startRelative,
                endRelative = endRelative,
                duration = durationMs,
                waitToSettleTimeoutMs = settleTimeoutMs,
            )

            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = command,
                        message = "Swipe executed successfully",
                        extra = {
                            put("direction", direction?.name ?: "")
                            put("duration_ms", durationMs)
                        },
                    ),
                )),
            )
        }
    }
}

object ScrollUntilVisibleTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "scroll_until_visible",
                description = "Scroll until a target selector becomes visible.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device to scroll on") }
                        putJsonObject("direction") { put("type", "string"); put("description", "Scroll direction: up, down, left, or right") }
                        putJsonObject("text") { put("type", "string") }
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("index") { put("type", "integer") }
                        putJsonObject("use_fuzzy_matching") { put("type", "boolean") }
                        putJsonObject("enabled") { put("type", "boolean") }
                        putJsonObject("checked") { put("type", "boolean") }
                        putJsonObject("focused") { put("type", "boolean") }
                        putJsonObject("selected") { put("type", "boolean") }
                        putJsonObject("timeout_ms") { put("type", "integer") }
                        putJsonObject("speed") { put("type", "string"); put("description", "Scroll speed percentage from YAML semantics, default 40") }
                        putJsonObject("visibility_percentage") { put("type", "integer") }
                        putJsonObject("center_element") { put("type", "boolean") }
                        putJsonObject("wait_to_settle_timeout_ms") { put("type", "integer") }
                    },
                    required = listOf("device_id", "direction"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            val direction = ToolSupport.parseScrollDirection(ToolSupport.requiredString(request, "direction"))
            val selector = ToolSupport.buildSelector(request, requireSelector = true)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id is required")), isError = true)
            }
            if (direction == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("direction must be one of up, down, left, right")), isError = true)
            }
            if (selector == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("A selector is required via text or id")), isError = true)
            }

            val command = ScrollUntilVisibleCommand(
                selector = selector,
                direction = direction,
                scrollDuration = ToolSupport.optionalString(request, "speed") ?: ScrollUntilVisibleCommand.DEFAULT_SCROLL_DURATION,
                visibilityPercentage = ToolSupport.optionalInt(request, "visibility_percentage")
                    ?: ScrollUntilVisibleCommand.DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE,
                timeout = (ToolSupport.optionalLong(request, "timeout_ms")
                    ?: ScrollUntilVisibleCommand.DEFAULT_TIMEOUT_IN_MILLIS.toLong()).toString(),
                waitToSettleTimeoutMs = ToolSupport.optionalInt(request, "wait_to_settle_timeout_ms"),
                centerElement = ToolSupport.optionalBoolean(request, "center_element")
                    ?: ScrollUntilVisibleCommand.DEFAULT_CENTER_ELEMENT,
            )

            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = command,
                        message = "Scroll-until-visible executed successfully",
                        extra = {
                            put("direction", direction.name)
                            put("visibility_percentage", command.visibilityPercentage)
                        },
                    ),
                )),
            )
        }
    }
}

object WaitForAnimationToEndTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "wait_for_animation_to_end",
                description = "Wait for on-screen animations and transitions to settle.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") { put("type", "string"); put("description", "The ID of the device to wait on") }
                        putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Optional timeout in milliseconds") }
                    },
                    required = listOf("device_id"),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.requiredString(request, "device_id")
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("device_id is required")), isError = true)
            }
            val timeoutMs = ToolSupport.optionalLong(request, "timeout_ms")
            val command = maestro.orchestra.WaitForAnimationToEndCommand(timeoutMs)

            CallToolResult(
                content = listOf(TextContent(
                    ToolSupport.runCommand(
                        sessionManager = sessionManager,
                        deviceId = deviceId,
                        command = command,
                        message = "Animation wait completed successfully",
                        extra = { put("timeout_ms", timeoutMs ?: 0L) },
                    ),
                )),
            )
        }
    }
}
