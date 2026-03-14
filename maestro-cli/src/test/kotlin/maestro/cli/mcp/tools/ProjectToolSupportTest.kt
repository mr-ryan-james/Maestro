package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

internal class ProjectToolSupportTest {

    @Test
    fun `listFlows returns shared flow summaries`() {
        val projectRoot = Files.createTempDirectory("project-tool-support-list")
        val flowDir = projectRoot.resolve("packages/mobile-app/maestro/flows")
        Files.createDirectories(flowDir)
        flowDir.resolve("main.yaml").writeText(
            """
            appId: com.example.app
            name: Main Flow
            tags:
              - smoke
            ---
            - tapOn:
                id: home-tab-chats
            """.trimIndent(),
        )

        val payload = ProjectToolSupport.listFlows(
            CallToolRequest(
                name = "list_flows",
                arguments = jsonObject(
                    "project_root" to projectRoot.toString(),
                ),
            ),
        )

        assertThat(payload.stringValue("project_root")).isEqualTo(projectRoot.toAbsolutePath().normalize().toString())
        assertThat(payload.intValue("total_flows")).isEqualTo(1)
        assertThat(payload["flows"].toString()).contains("Main Flow")
        assertThat(payload["flows"].toString()).contains("main.yaml")
    }

    @Test
    fun `analyzeFlow returns selector and reference analysis`() {
        val projectRoot = Files.createTempDirectory("project-tool-support-analyze")
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

        val payload = ProjectToolSupport.analyzeFlow(
            CallToolRequest(
                name = "analyze_flow",
                arguments = jsonObject(
                    "project_root" to projectRoot.toString(),
                    "flow_path" to flowPath.toString(),
                ),
            ),
        )

        assertThat(payload.stringValue("flow_path")).isEqualTo(".maestro/flow.yaml")
        assertThat(payload.intValue("command_count")).isEqualTo(2)
        assertThat(payload["selectors"].toString()).contains("home-tab-chats")
        assertThat(payload["referenced_files"].toString()).contains("missing-child.yaml")
    }

    @Test
    fun `validateTestIds returns requested and discovered counts`() {
        val projectRoot = Files.createTempDirectory("project-tool-support-validate")
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

        val payload = ProjectToolSupport.validateTestIds(
            CallToolRequest(
                name = "validate_testids",
                arguments = jsonObject(
                    "project_root" to projectRoot.toString(),
                    "flow_path" to flowPath.toString(),
                    "test_ids" to listOf("conversation-row-.*"),
                ),
            ),
        )

        assertThat(payload.intValue("requested_count")).isEqualTo(2)
        assertThat(payload.intValue("discovered_count")).isAtLeast(2)
        assertThat(payload["results"].toString()).contains("home-tab-chats")
        assertThat(payload["results"].toString()).contains("conversation-row-.*")
    }

    private fun jsonObject(vararg entries: Pair<String, Any?>): JsonObject {
        return buildJsonObject {
            entries.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is String -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value.toString()))
                    is Boolean -> put(key, JsonPrimitive(value))
                    is JsonObject -> put(key, value)
                    is JsonArray -> put(key, value)
                    is List<*> -> put(
                        key,
                        JsonArray(
                            value.map { item ->
                                when (item) {
                                    null -> JsonPrimitive("")
                                    is String -> JsonPrimitive(item)
                                    is Number -> JsonPrimitive(item.toString())
                                    is Boolean -> JsonPrimitive(item)
                                    else -> JsonPrimitive(item.toString())
                                }
                            },
                        ),
                    )
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
    }

    private fun JsonObject.stringValue(key: String): String {
        return this[key]?.toString()?.trim('"') ?: error("missing key: $key")
    }

    private fun JsonObject.intValue(key: String): Int {
        return this[key]?.toString()?.toInt() ?: error("missing key: $key")
    }
}
