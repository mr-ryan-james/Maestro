package maestro.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.AudioContent
import io.modelcontextprotocol.kotlin.sdk.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.ImageContent
import io.modelcontextprotocol.kotlin.sdk.PromptMessageContent
import io.modelcontextprotocol.kotlin.sdk.ResourceContents
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.UnknownContent
import io.modelcontextprotocol.kotlin.sdk.UnknownResourceContents
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlin.text.Charsets.UTF_8

internal class MaestroStdioMcpServer(
    private val serverName: String,
    private val serverVersion: String,
    tools: List<RegisteredTool>,
    private val instructions: String? = null,
    inputStream: InputStream = System.`in`,
    outputStream: OutputStream = System.out,
) {
    private enum class FramingMode {
        CONTENT_LENGTH,
        JSONL,
    }

    private data class IncomingMessage(
        val framingMode: FramingMode,
        val message: JsonObject,
    )

    private val input = BufferedInputStream(inputStream)
    private val output = BufferedOutputStream(outputStream)
    private val toolsByName = tools.associateBy { it.tool.name }

    @Volatile
    private var negotiatedFramingMode: FramingMode? = null

    private var negotiatedProtocolVersion = SUPPORTED_PROTOCOL_VERSIONS.first()
    private var initializeCompleted = false

    fun run() {
        while (true) {
            val incoming = try {
                readNextMessage() ?: break
            } catch (t: Throwable) {
                logError("Failed to read MCP message", t)
                break
            }

            if (negotiatedFramingMode == null) {
                negotiatedFramingMode = incoming.framingMode
            }

            val id = incoming.message["id"]
            val method = incoming.message["method"]?.jsonPrimitive?.contentOrNull
            val params = incoming.message["params"] as? JsonObject ?: EMPTY_OBJECT

            if (method == null) {
                if (id != null) {
                    write(jsonRpcError(id = id, code = -32600, message = "Invalid request: missing method"))
                }
                continue
            }

            if (id == null) {
                handleNotification(method, params)
                continue
            }

            val response = try {
                handleRequest(id = id, method = method, params = params)
            } catch (t: Throwable) {
                logError("Unhandled MCP request error for $method", t)
                jsonRpcError(id = id, code = -32603, message = t.message ?: "Internal error")
            }
            write(response)
        }

        runCatching { output.flush() }
    }

    private fun handleNotification(method: String, @Suppress("UNUSED_PARAMETER") params: JsonObject) {
        when (method) {
            "notifications/initialized" -> Unit
            "notifications/cancelled" -> Unit
            else -> Unit
        }
    }

    private fun handleRequest(id: JsonElement, method: String, params: JsonObject): JsonObject {
        if (!initializeCompleted && method !in setOf("initialize", "ping")) {
            return jsonRpcError(id = id, code = -32002, message = "Server not initialized")
        }

        return when (method) {
            "initialize" -> handleInitialize(id, params)
            "ping" -> jsonRpcResult(id = id, result = EMPTY_OBJECT)
            "tools/list" -> handleToolsList(id)
            "tools/call" -> handleToolsCall(id, params)
            else -> jsonRpcError(id = id, code = -32601, message = "Method not found: $method")
        }
    }

    private fun handleInitialize(id: JsonElement, params: JsonObject): JsonObject {
        val requestedVersion = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
        negotiatedProtocolVersion =
            if (requestedVersion != null && requestedVersion in SUPPORTED_PROTOCOL_VERSIONS) {
                requestedVersion
            } else {
                SUPPORTED_PROTOCOL_VERSIONS.first()
            }
        initializeCompleted = true

        return jsonRpcResult(
            id = id,
            result = buildJsonObject {
                put("protocolVersion", JsonPrimitive(negotiatedProtocolVersion))
                put(
                    "capabilities",
                    buildJsonObject {
                        put(
                            "tools",
                            buildJsonObject {
                                put("listChanged", JsonPrimitive(false))
                            },
                        )
                    },
                )
                put(
                    "serverInfo",
                    buildJsonObject {
                        put("name", JsonPrimitive(serverName))
                        put("version", JsonPrimitive(serverVersion))
                    },
                )
                if (!instructions.isNullOrBlank()) {
                    put("instructions", JsonPrimitive(instructions))
                }
            },
        )
    }

    private fun handleToolsList(id: JsonElement): JsonObject {
        val tools = toolsByName.values
            .sortedBy { it.tool.name }
            .map { registered -> toolDescriptor(registered.tool) }
        return jsonRpcResult(
            id = id,
            result = buildJsonObject {
                put("tools", JsonArray(tools))
            },
        )
    }

    private fun handleToolsCall(id: JsonElement, params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return jsonRpcError(id = id, code = -32602, message = "tools/call requires params.name")
        val arguments = params["arguments"] as? JsonObject ?: EMPTY_OBJECT
        val registered = toolsByName[name]
            ?: return jsonRpcError(id = id, code = -32602, message = "Unknown tool: $name")

        val result = try {
            runBlocking {
                registered.handler(CallToolRequest(name = name, arguments = arguments))
            }
        } catch (t: Throwable) {
            CallToolResult(
                content = listOf(TextContent(t.message ?: "Tool call failed")),
                isError = true,
            )
        }

        return jsonRpcResult(
            id = id,
            result = buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        result.content.forEach { add(contentBlock(it)) }
                    },
                )
                put("isError", JsonPrimitive(result.isError == true))
                parseStructuredContent(result)?.let { put("structuredContent", it) }
            },
        )
    }

    private fun toolDescriptor(tool: Tool): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(tool.name))
        tool.description?.let { put("description", JsonPrimitive(it)) }
        put(
            "inputSchema",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", tool.inputSchema.properties)
                tool.inputSchema.required
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { required ->
                        put(
                            "required",
                            buildJsonArray {
                                required.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                    }
            },
        )
    }

    private fun contentBlock(content: PromptMessageContent): JsonObject =
        when (content) {
            is TextContent -> buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(content.text.orEmpty()))
            }

            is ImageContent -> buildJsonObject {
                put("type", JsonPrimitive("image"))
                put("data", JsonPrimitive(content.data))
                put("mimeType", JsonPrimitive(content.mimeType))
            }

            is AudioContent -> buildJsonObject {
                put("type", JsonPrimitive("audio"))
                put("data", JsonPrimitive(content.data))
                put("mimeType", JsonPrimitive(content.mimeType))
            }

            is EmbeddedResource -> buildJsonObject {
                put("type", JsonPrimitive("resource"))
                put("resource", resourceBlock(content.resource))
            }

            is UnknownContent -> buildJsonObject {
                put("type", JsonPrimitive(content.type))
            }

            else -> buildJsonObject {
                put("type", JsonPrimitive(content.type))
            }
        }

    private fun resourceBlock(resource: ResourceContents): JsonObject =
        when (resource) {
            is TextResourceContents -> buildJsonObject {
                put("uri", JsonPrimitive(resource.uri))
                resource.mimeType?.let { put("mimeType", JsonPrimitive(it)) }
                put("text", JsonPrimitive(resource.text))
            }

            is BlobResourceContents -> buildJsonObject {
                put("uri", JsonPrimitive(resource.uri))
                resource.mimeType?.let { put("mimeType", JsonPrimitive(it)) }
                put("blob", JsonPrimitive(resource.blob))
            }

            is UnknownResourceContents -> buildJsonObject {
                put("uri", JsonPrimitive(resource.uri))
                resource.mimeType?.let { put("mimeType", JsonPrimitive(it)) }
            }

            else -> EMPTY_OBJECT
        }

    private fun parseStructuredContent(result: CallToolResult): JsonElement? {
        val onlyText = result.content.singleOrNull() as? TextContent ?: return null
        val raw = onlyText.text?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { WIRE_JSON.parseToJsonElement(raw) }.getOrNull()
    }

    private fun readNextMessage(): IncomingMessage? {
        while (true) {
            val firstLine = readAsciiLine() ?: return null
            if (firstLine.isBlank()) continue

            return if (firstLine.startsWith("Content-Length:", ignoreCase = true)) {
                val contentLength = parseContentLength(firstLine)
                while (true) {
                    val headerLine = readAsciiLine()
                        ?: throw EOFException("Unexpected EOF while reading MCP headers")
                    if (headerLine.isBlank()) break
                }
                IncomingMessage(
                    framingMode = FramingMode.CONTENT_LENGTH,
                    message = parseMessage(readExact(contentLength).toString(UTF_8)),
                )
            } else {
                IncomingMessage(
                    framingMode = FramingMode.JSONL,
                    message = parseMessage(firstLine),
                )
            }
        }
    }

    private fun readAsciiLine(): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val nextByte = input.read()
            if (nextByte == -1) {
                if (buffer.size() == 0) {
                    return null
                }
                break
            }
            if (nextByte == '\n'.code) {
                break
            }
            buffer.write(nextByte)
        }

        val raw = buffer.toByteArray()
        val lineBytes =
            if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.copyOf(raw.size - 1) else raw
        return String(lineBytes, UTF_8)
    }

    private fun readExact(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(buffer, offset, length - offset)
            if (read == -1) {
                throw EOFException("Unexpected EOF while reading $length-byte MCP payload")
            }
            offset += read
        }
        return buffer
    }

    private fun parseContentLength(headerLine: String): Int {
        val value = headerLine.substringAfter(':', "").trim()
        return value.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid Content-Length header: $headerLine")
    }

    private fun parseMessage(rawMessage: String): JsonObject {
        return WIRE_JSON.parseToJsonElement(rawMessage) as? JsonObject
            ?: throw IllegalArgumentException("MCP request must be a JSON object")
    }

    private fun write(payload: JsonObject) {
        val encoded = payload.toString().toByteArray(UTF_8)
        when (negotiatedFramingMode ?: FramingMode.CONTENT_LENGTH) {
            FramingMode.CONTENT_LENGTH -> {
                val header = "Content-Length: ${encoded.size}\r\n\r\n".toByteArray(UTF_8)
                output.write(header)
                output.write(encoded)
            }

            FramingMode.JSONL -> {
                output.write(encoded)
                output.write('\n'.code)
            }
        }
        output.flush()
    }

    private fun jsonRpcResult(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id)
        put("result", result)
    }

    private fun jsonRpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", JsonPrimitive("2.0"))
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", JsonPrimitive(code))
                put("message", JsonPrimitive(message))
            },
        )
    }

    private fun logError(message: String, throwable: Throwable) {
        System.err.println("MCP Server: $message")
        throwable.printStackTrace(System.err)
    }

    private companion object {
        private val EMPTY_OBJECT: JsonObject = buildJsonObject {}
        private val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-06-18", "2024-11-05")
        private val WIRE_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }
    }
}
