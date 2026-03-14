package maestro.cli.mcp

import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MaestroStdioMcpServerTest {

    @Test
    fun contentLengthInitializeAndToolsListWorks() {
        val output = ByteArrayOutputStream()
        serverFor(
            framedMessages(
                request(
                    id = 1,
                    method = "initialize",
                    params = """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"1.0"}}""",
                ),
                request(
                    id = null,
                    method = "notifications/initialized",
                    params = "{}",
                ),
                request(
                    id = 2,
                    method = "tools/list",
                    params = "{}",
                ),
            ),
            output,
        ).run()

        val responses = parseFramedResponses(output.toByteArray())
        assertThat(responses).hasSize(2)

        val initialize = responses[0]
        assertThat(initialize["id"]?.toString()).isEqualTo("1")
        assertThat(initialize.resultObject()["protocolVersion"]?.jsonPrimitiveString()).isEqualTo("2025-06-18")

        val tools = responses[1].resultObject()["tools"]?.toString().orEmpty()
        assertThat(tools).contains("open_session")
        assertThat(tools).contains("execute_batch")
        assertThat(tools).contains("list_flows")
        assertThat(tools).contains("analyze_flow")
        assertThat(tools).contains("run_macro")
        assertThat(tools).contains("validate_testids")
        assertThat(tools).contains("suggest_selectors")
        assertThat(tools).contains("run_flow_with_diagnostics")
    }

    @Test
    fun jsonlInitializeAndToolsCallStaysJsonlCompatible() {
        val output = ByteArrayOutputStream()
        serverFor(
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"1.0"}}}
            {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo_tool","arguments":{"value":"ok"}}}
            """.trimIndent() + "\n",
            output,
            tools = listOf(
                RegisteredTool(
                    tool = Tool(
                        name = "echo_tool",
                        description = "Echo test tool",
                        inputSchema = Tool.Input(
                            properties = buildJsonObject {
                                put(
                                    "value",
                                    buildJsonObject {
                                        put("type", "string")
                                    },
                                )
                            },
                            required = listOf("value"),
                        ),
                    ),
                ) { request ->
                    CallToolResult(
                        content = listOf(
                            TextContent(
                                buildJsonObject {
                                    put("echo", request.arguments["value"] ?: error("missing value"))
                                }.toString(),
                            ),
                        ),
                    )
                },
            ),
        ).run()

        val lines = output.toString(Charsets.UTF_8).trim().lines()
        assertThat(lines).hasSize(2)
        val initialize = JSON.parseToJsonElement(lines[0]) as JsonObject
        val toolsCall = JSON.parseToJsonElement(lines[1]) as JsonObject

        assertThat(initialize.resultObject()["protocolVersion"]?.jsonPrimitiveString()).isEqualTo("2024-11-05")
        assertThat(toolsCall.resultObject()["structuredContent"].toString()).contains("\"echo\":\"ok\"")
    }

    private fun serverFor(
        inputPayload: String,
        output: ByteArrayOutputStream,
        tools: List<RegisteredTool> = MaestroMcpToolRegistry.all(),
    ): MaestroStdioMcpServer {
        return MaestroStdioMcpServer(
            serverName = "maestro",
            serverVersion = "test",
            tools = tools,
            inputStream = ByteArrayInputStream(inputPayload.toByteArray(Charsets.UTF_8)),
            outputStream = output,
        )
    }

    private fun request(id: Int?, method: String, params: String): String {
        val idPart = id?.let { ""","id":$it""" } ?: ""
        return """{"jsonrpc":"2.0"$idPart,"method":"$method","params":$params}"""
    }

    private fun framedMessages(vararg messages: String): String {
        return buildString {
            messages.forEach { message ->
                val payload = message.toByteArray(Charsets.UTF_8)
                append("Content-Length: ${payload.size}\r\n\r\n")
                append(message)
            }
        }
    }

    private fun parseFramedResponses(bytes: ByteArray): List<JsonObject> {
        val text = bytes.toString(Charsets.UTF_8)
        val responses = mutableListOf<JsonObject>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val headerEnd = remaining.indexOf("\r\n\r\n")
            if (headerEnd == -1) break
            val header = remaining.substring(0, headerEnd)
            val contentLength = header
                .lineSequence()
                .first { it.startsWith("Content-Length:", ignoreCase = true) }
                .substringAfter(':')
                .trim()
                .toInt()
            val payloadStart = headerEnd + 4
            val payloadEnd = payloadStart + contentLength
            val payload = remaining.substring(payloadStart, payloadEnd)
            responses += JSON.parseToJsonElement(payload) as JsonObject
            remaining = remaining.substring(payloadEnd)
        }
        return responses
    }

    private fun JsonObject.resultObject(): JsonObject {
        return this["result"] as? JsonObject ?: error("missing result")
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveString(): String {
        return this.toString().trim('"')
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
        }
    }
}
