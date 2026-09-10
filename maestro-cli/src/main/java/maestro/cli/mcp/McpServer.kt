package maestro.cli.mcp

import maestro.cli.util.WorkingDirectory
import maestro.debuglog.LogConfig

fun runMaestroMcpServer() {
    LogConfig.configure(logFileName = null, printToConsole = false)

    // stdout carries the JSON-RPC stream. Anything else printed to System.out
    // (PrintUtils.message, driver-build spinners) corrupts the protocol and
    // leaves the client waiting forever, so move console output to stderr and
    // hand the original stream to the transport explicitly.
    val protocolOut = System.out
    System.setOut(System.err)

    val server = MaestroStdioMcpServer(
        serverName = "maestro",
        serverVersion = "1.0.0",
        tools = MaestroMcpToolRegistry.all(),
        instructions = "Use open_session for repeated device work. Daemon and bridge helpers are additive; direct non-daemon flows remain supported.",
        outputStream = protocolOut,
    )

    System.err.println("MCP Server: Started. Waiting for messages. Working directory: ${WorkingDirectory.baseDir}")
    try {
        server.run()
    } finally {
        McpSessionRegistry.closeAll()
    }
}
