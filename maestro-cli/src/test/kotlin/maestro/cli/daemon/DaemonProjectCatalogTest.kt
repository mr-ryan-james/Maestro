package maestro.cli.daemon

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.io.path.writeText
import org.junit.jupiter.api.Test

internal class DaemonProjectCatalogTest {

    @Test
    fun `discoverFlows summarizes maestro-owned yaml files`() {
        val projectRoot = Files.createTempDirectory("daemon-project-catalog-test")
        val flowDir = projectRoot.resolve("packages/mobile-app/maestro/flows")
        Files.createDirectories(flowDir)
        val subflowPath = flowDir.resolve("child.yaml")
        subflowPath.writeText(
            """
            appId: com.example.app
            ---
            - assertVisible:
                id: child-screen
            """.trimIndent(),
        )
        val mainFlowPath = flowDir.resolve("main.yaml")
        mainFlowPath.writeText(
            """
            appId: com.example.app
            name: Main Flow
            tags:
              - smoke
            ---
            - tapOn:
                id: home-tab-chats
            - runFlow:
                file: child.yaml
            """.trimIndent(),
        )

        val flows = DaemonProjectCatalog.discoverFlows(projectRoot)

        assertThat(flows).hasSize(2)
        val mainFlow = flows.first { it.relativePath.endsWith("main.yaml") }
        assertThat(mainFlow.appId).isEqualTo("com.example.app")
        assertThat(mainFlow.name).isEqualTo("Main Flow")
        assertThat(mainFlow.tags).containsExactly("smoke")
        assertThat(mainFlow.commandCount).isEqualTo(2)
        assertThat(mainFlow.selectorCount).isAtLeast(1)
        assertThat(mainFlow.firstCommands).containsExactly("tapOn", "runFlow")
    }

    @Test
    fun `analyzeFlow reports selectors and missing referenced files`() {
        val projectRoot = Files.createTempDirectory("daemon-project-analyze-test")
        val flowDir = projectRoot.resolve(".maestro")
        Files.createDirectories(flowDir)
        val flowPath = flowDir.resolve("flow.yaml")
        flowPath.writeText(
            """
            appId: com.example.app
            ---
            - assertVisible:
                id: home-tab-chats
            - runFlow:
                file: missing-child.yaml
            """.trimIndent(),
        )

        val analysis = DaemonProjectCatalog.analyzeFlow(projectRoot, flowPath.toString())

        assertThat(analysis.relativePath).isEqualTo(".maestro/flow.yaml")
        assertThat(analysis.commandCount).isEqualTo(2)
        assertThat(analysis.selectors.map { it.value }).contains("home-tab-chats")
        assertThat(analysis.referencedFiles).hasSize(1)
        assertThat(analysis.referencedFiles.single().existsOnDisk).isFalse()
    }

    @Test
    fun `validateTestIds finds exact and pattern matches`() {
        val projectRoot = Files.createTempDirectory("daemon-project-validate-testids")
        val mobileDir = projectRoot.resolve("packages/mobile-app/src")
        Files.createDirectories(mobileDir)
        mobileDir.resolve("Chats.tsx").writeText(
            """
            export function Chats() {
              return (
                <>
                  <View testID="home-tab-chats" />
                  <View testID={`conversation-row-${'$'}{conversationId}`} />
                </>
              );
            }
            """.trimIndent(),
        )
        val flowDir = projectRoot.resolve("packages/mobile-app/maestro")
        Files.createDirectories(flowDir)
        val flowPath = flowDir.resolve("flow.yaml")
        flowPath.writeText(
            """
            appId: com.example.app
            ---
            - assertVisible:
                id: home-tab-chats
            """.trimIndent(),
        )

        val catalog = DaemonProjectCatalog.scanTestIds(projectRoot)
        assertThat(catalog.map { it.value }).containsAtLeast("home-tab-chats", "conversation-row-${'$'}{conversationId}")

        val (requestedIds, results) = DaemonProjectCatalog.validateTestIds(
            projectRoot = projectRoot,
            requestedIds = listOf("home-tab-chats", "conversation-row-.*"),
            flowPath = flowPath.toString(),
        )

        assertThat(requestedIds).containsExactly("conversation-row-.*", "home-tab-chats")
        val exact = results.first { it.value == "home-tab-chats" }
        assertThat(exact.status).isEqualTo("found")
        val pattern = results.first { it.value == "conversation-row-.*" }
        assertThat(pattern.status).isEqualTo("pattern_match")
        assertThat(pattern.matches.map { it.value }).contains("conversation-row-${'$'}{conversationId}")
    }
}
