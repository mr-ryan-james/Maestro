package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.mcp.McpSessionRegistry
import maestro.cli.session.MaestroSessionManager
import okio.Buffer
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object TakeScreenshotTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "take_screenshot",
                description = "Take a screenshot of the current device screen",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to take a screenshot from")
                        }
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Optional hot session id returned by open_session")
                        }
                    },
                    required = emptyList()
                )
            )
        ) { request ->
            try {
                val deviceId = ToolSupport.resolveDeviceId(request)
                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                        isError = true
                    )
                }
                
                val result = ToolSupport.withSession(sessionManager, request, deviceId) { session ->
                    val buffer = Buffer()
                    session.maestro.takeScreenshot(buffer, true)
                    val rawPngBytes = buffer.readByteArray()
                    val pngBytes = maestro.utils.ScreenshotUtils.resizeBytesIfNeeded(rawPngBytes)

                    // Convert PNG to JPEG
                    val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
                    val jpegOutput = ByteArrayOutputStream()
                    ImageIO.write(pngImage, "JPEG", jpegOutput)
                    val jpegBytes = jpegOutput.toByteArray()

                    val base64 = Base64.getEncoder().encodeToString(jpegBytes)
                    base64
                }
                McpSessionRegistry.invalidateHierarchy(ToolSupport.optionalSessionId(request))
                
                val imageContent = ImageContent(
                    data = result,
                    mimeType = "image/jpeg"
                )
                
                CallToolResult(content = listOf(imageContent))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to take screenshot: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
