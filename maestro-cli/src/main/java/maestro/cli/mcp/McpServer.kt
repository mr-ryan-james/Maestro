package maestro.cli.mcp

import io.ktor.utils.io.streams.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.io.*
import maestro.cli.session.MaestroSessionManager
import maestro.debuglog.LogConfig
import maestro.cli.mcp.tools.ListDevicesTool
import maestro.cli.mcp.tools.StartDeviceTool
import maestro.cli.mcp.tools.LaunchAppTool
import maestro.cli.mcp.tools.TakeScreenshotTool
import maestro.cli.mcp.tools.TapOnTool
import maestro.cli.mcp.tools.InputTextTool
import maestro.cli.mcp.tools.BackTool
import maestro.cli.mcp.tools.StopAppTool
import maestro.cli.mcp.tools.RunFlowTool
import maestro.cli.mcp.tools.RunFlowFilesTool
import maestro.cli.mcp.tools.CheckFlowSyntaxTool
import maestro.cli.mcp.tools.InspectViewHierarchyTool
import maestro.cli.mcp.tools.CheatSheetTool
import maestro.cli.mcp.tools.QueryDocsTool
import maestro.cli.mcp.tools.HideKeyboardTool
import maestro.cli.mcp.tools.SwipeTool
import maestro.cli.mcp.tools.ScrollTool
import maestro.cli.mcp.tools.ScrollUntilVisibleTool
import maestro.cli.mcp.tools.EraseTextTool
import maestro.cli.mcp.tools.AssertVisibleTool
import maestro.cli.mcp.tools.AssertNotVisibleTool
import maestro.cli.mcp.tools.WaitForAnimationToEndTool
import maestro.cli.mcp.tools.PressKeyTool
import maestro.cli.mcp.tools.ClearStateTool
import maestro.cli.mcp.tools.SetLocationTool
import maestro.cli.mcp.tools.CopyTextFromTool
import maestro.cli.mcp.tools.OpenLinkTool
import maestro.cli.mcp.tools.OpenSessionTool
import maestro.cli.mcp.tools.ResumeSessionTool
import maestro.cli.mcp.tools.CloseSessionTool
import maestro.cli.mcp.tools.ListSessionsTool
import maestro.cli.mcp.tools.HardResetSessionTool
import maestro.cli.mcp.tools.QueryElementsTool
import maestro.cli.mcp.tools.SnapshotTool
import maestro.cli.mcp.tools.AwaitEventTool
import maestro.cli.mcp.tools.ExecuteBatchTool
import maestro.cli.util.WorkingDirectory

// Main function to run the Maestro MCP server
fun runMaestroMcpServer() {
    // Disable all console logging to prevent interference with JSON-RPC communication
    LogConfig.configure(logFileName = null, printToConsole = false)
    
    val sessionManager = MaestroSessionManager

    // Create the MCP Server instance with Maestro implementation
    val server = Server(
        Implementation(
            name = "maestro",
            version = "1.0.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    // Register tools
    server.addTools(listOf(
        ListDevicesTool.create(),
        StartDeviceTool.create(),
        OpenSessionTool.create(sessionManager),
        ResumeSessionTool.create(),
        CloseSessionTool.create(),
        ListSessionsTool.create(),
        HardResetSessionTool.create(sessionManager),
        LaunchAppTool.create(sessionManager),
        TakeScreenshotTool.create(sessionManager),
        TapOnTool.create(sessionManager),
        InputTextTool.create(sessionManager),
        BackTool.create(sessionManager),
        StopAppTool.create(sessionManager),
        RunFlowTool.create(sessionManager),
        RunFlowFilesTool.create(sessionManager),
        CheckFlowSyntaxTool.create(),
        InspectViewHierarchyTool.create(sessionManager),
        CheatSheetTool.create(),
        QueryDocsTool.create(),
        HideKeyboardTool.create(sessionManager),
        SwipeTool.create(sessionManager),
        ScrollTool.create(sessionManager),
        ScrollUntilVisibleTool.create(sessionManager),
        EraseTextTool.create(sessionManager),
        AssertVisibleTool.create(sessionManager),
        AssertNotVisibleTool.create(sessionManager),
        WaitForAnimationToEndTool.create(sessionManager),
        PressKeyTool.create(sessionManager),
        ClearStateTool.create(sessionManager),
        SetLocationTool.create(sessionManager),
        CopyTextFromTool.create(sessionManager),
        OpenLinkTool.create(sessionManager),
        QueryElementsTool.create(sessionManager),
        SnapshotTool.create(sessionManager),
        AwaitEventTool.create(sessionManager),
        ExecuteBatchTool.create(sessionManager)
    ))


    // Create a transport using standard IO for server communication
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )

    System.err.println("MCP Server: Started. Waiting for messages. Working directory: ${WorkingDirectory.baseDir}")

    runBlocking {
        server.connect(transport)
        val done = Job()
        server.onClose {
            McpSessionRegistry.closeAll()
            done.complete()
        }
        done.join()
    }
}
