package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Paths
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.DismissKnownOverlaysCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.RunFlowCommand
import org.junit.jupiter.api.Test

internal class CompiledFlowCacheTest {

    @Test
    fun `compileInlineFlow optimizes zero-time guards and redundant waits`() {
        val flow = """
            appId: com.example.app
            ---
            - waitForAnimationToEnd
            - assertVisibleNow:
                id: home-tab-chats
            - runFlow:
                when:
                  visible:
                    id: overlay
                  conditionTimeoutMs: 0
                commands:
                  - waitForAnimationToEnd
                  - assertNotVisibleNow:
                      text: Loading
        """.trimIndent()

        val compiled = CompiledFlowCache.compileInlineFlow(
            flowPath = Paths.get("/tmp/compiled-flow-optimization.yaml"),
            flowYaml = flow,
            env = mapOf("EXAMPLE" to "1"),
            projectIndexVersion = "test-index",
        )

        assertThat(compiled.sourceCommandCount).isEqualTo(4)
        assertThat(compiled.optimizedCommandCount).isEqualTo(3)
        assertThat(compiled.optimizationSummary["redundant_animation_waits_stripped"]).isEqualTo(2)
        assertThat(compiled.optimizationSummary["zero_time_condition_rewrites"]).isEqualTo(1)

        val commands = compiled.commands.mapNotNull { it.asCommand() }
        assertThat(commands).hasSize(3)
        assertThat(commands[1]).isEqualTo(
            AssertConditionCommand(
                condition = maestro.orchestra.Condition(
                    visibleNow = ElementSelector(idRegex = "home-tab-chats"),
                ),
                timeout = "0",
            ),
        )

        val runFlow = commands[2] as RunFlowCommand
        assertThat(runFlow.condition).isEqualTo(
            maestro.orchestra.Condition(
                visibleNow = ElementSelector(idRegex = "overlay"),
                conditionTimeoutMs = 0L,
            ),
        )
        assertThat(runFlow.commands.mapNotNull { it.asCommand() }).containsExactly(
            AssertConditionCommand(
                condition = maestro.orchestra.Condition(
                    notVisibleNow = ElementSelector(textRegex = "Loading"),
                ),
                timeout = "0",
            ),
        )
    }

    @Test
    fun `compileInlineFlow collapses consecutive overlay dismissals`() {
        val flow = """
            appId: com.example.app
            ---
            - dismissKnownOverlays:
                maxPasses: 1
            - dismissKnownOverlays:
                maxPasses: 4
            - tapOn: OK
            - dismissKnownOverlays:
                maxPasses: 2
        """.trimIndent()

        val compiled = CompiledFlowCache.compileInlineFlow(
            flowPath = Paths.get("/tmp/compiled-flow-overlay-collapse.yaml"),
            flowYaml = flow,
        )

        assertThat(compiled.sourceCommandCount).isEqualTo(5)
        assertThat(compiled.optimizedCommandCount).isEqualTo(4)
        assertThat(compiled.optimizationSummary["overlay_sweeps_collapsed"]).isEqualTo(1)

        val commands = compiled.commands.mapNotNull { it.asCommand() }
        assertThat(commands[1]).isEqualTo(DismissKnownOverlaysCommand(maxPasses = 4))
        assertThat(commands[3]).isEqualTo(DismissKnownOverlaysCommand(maxPasses = 2))
    }

    @Test
    fun `compileFlowFile cache invalidates on content change`() {
        val flowPath = Files.createTempFile("compiled-flow-cache-file", ".yaml")
        Files.writeString(
            flowPath,
            """
            appId: com.example.app
            ---
            - assertVisibleNow:
                id: home-tab-chats
            """.trimIndent(),
        )

        val firstCompiled = CompiledFlowCache.compileFlowFile(flowPath)

        Files.writeString(
            flowPath,
            """
            appId: com.example.app
            ---
            - assertVisibleNow:
                id: home-tab-discover
            """.trimIndent(),
        )

        val secondCompiled = CompiledFlowCache.compileFlowFile(flowPath)
        val secondCommand = secondCompiled.commands.single().asCommand() as AssertConditionCommand

        assertThat(firstCompiled.flowHash).isNotEqualTo(secondCompiled.flowHash)
        assertThat(secondCommand.condition.visibleNow?.idRegex).isEqualTo("home-tab-discover")
    }
}
