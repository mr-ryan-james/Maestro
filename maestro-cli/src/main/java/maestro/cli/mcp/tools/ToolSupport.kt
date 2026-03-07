package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import maestro.KeyCode
import maestro.Point
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.ElementSelector
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra

internal object ToolSupport {

    fun requiredString(request: CallToolRequest, name: String): String? =
        request.arguments[name]?.jsonPrimitive?.contentOrNull?.takeIf { value -> value.isNotBlank() }

    fun optionalString(request: CallToolRequest, name: String): String? =
        request.arguments[name]?.jsonPrimitive?.contentOrNull?.takeIf { value -> value.isNotBlank() }

    fun optionalBoolean(request: CallToolRequest, name: String): Boolean? =
        request.arguments[name]?.jsonPrimitive?.booleanOrNull

    fun optionalInt(request: CallToolRequest, name: String): Int? =
        request.arguments[name]?.jsonPrimitive?.intOrNull

    fun optionalLong(request: CallToolRequest, name: String): Long? =
        request.arguments[name]?.jsonPrimitive?.longOrNull

    fun optionalDouble(request: CallToolRequest, name: String): Double? =
        request.arguments[name]?.jsonPrimitive?.doubleOrNull

    fun executeSession(
        sessionManager: MaestroSessionManager,
        deviceId: String,
        block: (MaestroSessionManager.MaestroSession) -> JsonObject,
    ): String {
        return sessionManager.newSession(
            host = null,
            port = null,
            driverHostPort = null,
            deviceId = deviceId,
            platform = null,
        ) { session ->
            block(session).toString()
        }
    }

    fun runCommand(
        sessionManager: MaestroSessionManager,
        deviceId: String,
        command: Command,
        message: String,
        extra: (MutableMap<String, Any?>.() -> Unit)? = null,
    ): String {
        return executeSession(sessionManager, deviceId) { session ->
            val orchestra = Orchestra(session.maestro)
            runBlocking {
                orchestra.executeCommands(listOf(MaestroCommand(command = command)))
            }
            successJson(deviceId, message, extra)
        }
    }

    fun successJson(
        deviceId: String,
        message: String,
        extra: (MutableMap<String, Any?>.() -> Unit)? = null,
    ): JsonObject {
        val values = linkedMapOf<String, Any?>(
            "success" to true,
            "device_id" to deviceId,
            "message" to message,
        )
        extra?.invoke(values)
        return jsonObject(values)
    }

    fun jsonObject(values: Map<String, Any?>): JsonObject = buildJsonObject {
        values.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Boolean -> put(key, JsonPrimitive(value))
                is Int -> put(key, JsonPrimitive(value))
                is Long -> put(key, JsonPrimitive(value))
                is Double -> put(key, JsonPrimitive(value))
                is Float -> put(key, JsonPrimitive(value.toDouble()))
                is String -> put(key, JsonPrimitive(value))
                is JsonObject -> put(key, value)
                is JsonPrimitive -> put(key, value)
                else -> put(key, JsonPrimitive(value.toString()))
            }
        }
    }

    fun buildSelector(request: CallToolRequest, requireSelector: Boolean = true): ElementSelector? {
        val text = optionalString(request, "text")
        val id = optionalString(request, "id")
        val index = optionalInt(request, "index")
        val useFuzzyMatching = optionalBoolean(request, "use_fuzzy_matching") ?: true
        val enabled = optionalBoolean(request, "enabled")
        val checked = optionalBoolean(request, "checked")
        val focused = optionalBoolean(request, "focused")
        val selected = optionalBoolean(request, "selected")

        if (text == null && id == null && requireSelector) {
            return null
        }

        return ElementSelector(
            textRegex = text?.let { selectorRegex(it, useFuzzyMatching) },
            idRegex = id?.let { selectorRegex(it, useFuzzyMatching) },
            index = index?.toString(),
            enabled = enabled,
            checked = checked,
            focused = focused,
            selected = selected,
        )
    }

    fun buildHierarchySelectorPredicate(request: CallToolRequest): (TreeNode) -> Boolean {
        val text = optionalString(request, "text")
        val id = optionalString(request, "id")
        val useFuzzyMatching = optionalBoolean(request, "use_fuzzy_matching") ?: true
        val enabled = optionalBoolean(request, "enabled")
        val checked = optionalBoolean(request, "checked")
        val focused = optionalBoolean(request, "focused")
        val selected = optionalBoolean(request, "selected")

        val textRegex = text?.let { Regex(selectorRegex(it, useFuzzyMatching)) }
        val idRegex = id?.let { Regex(selectorRegex(it, useFuzzyMatching)) }

        return { node: TreeNode ->
            val nodeText = node.attributes["text"]
                ?: node.attributes["hintText"]
                ?: node.attributes["accessibilityText"]
            val nodeId = node.attributes["resource-id"] ?: node.attributes["identifier"] ?: node.attributes["id"]

            val matchesText = textRegex?.matches(nodeText ?: "") ?: true
            val matchesId = idRegex?.matches(nodeId ?: "") ?: true
            val matchesEnabled = enabled?.let { node.enabled == it } ?: true
            val matchesChecked = checked?.let { node.checked == it } ?: true
            val matchesFocused = focused?.let { node.focused == it } ?: true
            val matchesSelected = selected?.let { node.selected == it } ?: true

            matchesText && matchesId && matchesEnabled && matchesChecked && matchesFocused && matchesSelected
        }
    }

    fun findMatchingNodes(viewHierarchy: ViewHierarchy, request: CallToolRequest): List<TreeNode> {
        val index = optionalInt(request, "index")
        val predicate = buildHierarchySelectorPredicate(request)
        val matches = viewHierarchy.aggregate().filter(predicate)
        return if (index != null) {
            matches.getOrNull(index)?.let(::listOf) ?: emptyList()
        } else {
            matches
        }
    }

    fun parseSwipeDirection(raw: String?): SwipeDirection? =
        raw?.let { enumValues<SwipeDirection>().find { value -> value.name.equals(it, ignoreCase = true) } }

    fun parseScrollDirection(raw: String?): ScrollDirection? =
        raw?.let { enumValues<ScrollDirection>().find { value -> value.name.equals(it, ignoreCase = true) } }

    fun parseKeyCode(raw: String?): KeyCode? =
        raw?.let { KeyCode.getByName(it) ?: enumValues<KeyCode>().find { value -> value.name.equals(it, ignoreCase = true) } }

    fun pointFrom(request: CallToolRequest, prefix: String): Point? {
        val x = optionalInt(request, "${prefix}_x") ?: return null
        val y = optionalInt(request, "${prefix}_y") ?: return null
        return Point(x, y)
    }

    private fun selectorRegex(value: String, useFuzzyMatching: Boolean): String {
        return if (useFuzzyMatching) {
            ".*${Regex.escape(value)}.*"
        } else {
            value
        }
    }
}
