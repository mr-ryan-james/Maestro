package maestro.cli.mcp

import maestro.cli.util.WorkingDirectory
import maestro.debuglog.LogConfig

fun runMaestroMcpServer() {
    LogConfig.configure(logFileName = null, printToConsole = false)

    val server = MaestroStdioMcpServer(
        serverName = "maestro",
        serverVersion = "1.0.0",
        tools = MaestroMcpToolRegistry.all(),
        instructions = "Use open_session for repeated device work. Daemon and bridge helpers are additive; direct non-daemon flows remain supported.",
    )

    System.err.println("MCP Server: Started. Waiting for messages. Working directory: ${WorkingDirectory.baseDir}")
    try {
        server.run()
    } finally {
        McpSessionRegistry.closeAll()
    }
}
