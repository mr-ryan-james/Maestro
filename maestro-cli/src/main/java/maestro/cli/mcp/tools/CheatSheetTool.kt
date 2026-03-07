package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.buildJsonObject

object CheatSheetTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "cheat_sheet",
                description = "Get the bundled Maestro cheat sheet with common commands, selectors, and syntax examples. No cloud token required.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
            ),
        ) {
            CallToolResult(
                content = listOf(TextContent(LocalDocsRepository.cheatSheet())),
            )
        }
    }
}
