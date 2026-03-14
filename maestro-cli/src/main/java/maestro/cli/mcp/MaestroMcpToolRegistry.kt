package maestro.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import maestro.cli.mcp.tools.AwaitEventTool
import maestro.cli.mcp.tools.BackTool
import maestro.cli.mcp.tools.AnalyzeFlowTool
import maestro.cli.mcp.tools.CheckFlowSyntaxTool
import maestro.cli.mcp.tools.CheatSheetTool
import maestro.cli.mcp.tools.ClearStateTool
import maestro.cli.mcp.tools.CloseSessionTool
import maestro.cli.mcp.tools.CopyTextFromTool
import maestro.cli.mcp.tools.EraseTextTool
import maestro.cli.mcp.tools.ExecuteBatchTool
import maestro.cli.mcp.tools.HardResetSessionTool
import maestro.cli.mcp.tools.HideKeyboardTool
import maestro.cli.mcp.tools.InputTextTool
import maestro.cli.mcp.tools.InspectViewHierarchyTool
import maestro.cli.mcp.tools.LaunchAppTool
import maestro.cli.mcp.tools.ListFlowsTool
import maestro.cli.mcp.tools.ListDevicesTool
import maestro.cli.mcp.tools.ListSessionsTool
import maestro.cli.mcp.tools.OpenLinkTool
import maestro.cli.mcp.tools.OpenSessionTool
import maestro.cli.mcp.tools.PressKeyTool
import maestro.cli.mcp.tools.QueryDocsTool
import maestro.cli.mcp.tools.QueryElementsTool
import maestro.cli.mcp.tools.ResumeSessionTool
import maestro.cli.mcp.tools.RunMacroTool
import maestro.cli.mcp.tools.RunFlowFilesTool
import maestro.cli.mcp.tools.RunFlowTool
import maestro.cli.mcp.tools.ScrollTool
import maestro.cli.mcp.tools.ScrollUntilVisibleTool
import maestro.cli.mcp.tools.SetLocationTool
import maestro.cli.mcp.tools.SnapshotTool
import maestro.cli.mcp.tools.StartDeviceTool
import maestro.cli.mcp.tools.StopAppTool
import maestro.cli.mcp.tools.SuggestSelectorsTool
import maestro.cli.mcp.tools.SwipeTool
import maestro.cli.mcp.tools.TakeScreenshotTool
import maestro.cli.mcp.tools.TapOnTool
import maestro.cli.mcp.tools.RunFlowWithDiagnosticsTool
import maestro.cli.mcp.tools.ValidateTestIdsTool
import maestro.cli.mcp.tools.WaitForAnimationToEndTool
import maestro.cli.mcp.tools.AssertNotVisibleTool
import maestro.cli.mcp.tools.AssertVisibleTool
import maestro.cli.session.MaestroSessionManager

internal object MaestroMcpToolRegistry {

    fun all(): List<RegisteredTool> {
        val sessionManager = MaestroSessionManager
        return listOf(
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
            ListFlowsTool.create(),
            AnalyzeFlowTool.create(),
            RunMacroTool.create(sessionManager),
            SuggestSelectorsTool.create(sessionManager),
            ValidateTestIdsTool.create(),
            RunFlowWithDiagnosticsTool.create(sessionManager),
            QueryElementsTool.create(sessionManager),
            SnapshotTool.create(sessionManager),
            AwaitEventTool.create(sessionManager),
            ExecuteBatchTool.create(sessionManager),
        )
    }
}
