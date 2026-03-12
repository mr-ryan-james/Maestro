package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import maestro.AutomationNode
import maestro.AutomationQueryRequest
import maestro.AutomationQueryResult
import maestro.AutomationQueryMatch
import maestro.AutomationSelector
import maestro.AutomationSnapshot
import maestro.AutomationSnapshotMode
import maestro.AutomationSnapshotRequest
import maestro.AutomationWaitRequest
import maestro.AutomationWaitResult
import maestro.DEFAULT_AUTOMATION_FIELDS
import maestro.KeyCode
import maestro.Point
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.cli.mcp.McpSessionRegistry
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

    fun optionalSessionId(request: CallToolRequest): String? =
        optionalString(request, "session_id")

    fun resolveDeviceId(request: CallToolRequest): String? =
        requiredString(request, "device_id")
            ?: optionalSessionId(request)?.let { McpSessionRegistry.deviceId(it) }

    fun resolveDeviceId(request: CallToolRequest, deviceId: String?): String? =
        deviceId ?: resolveDeviceId(request)

    fun requireDeviceIdMessage(): String =
        "Either device_id or a valid session_id is required"

    fun <T> withSession(
        sessionManager: MaestroSessionManager,
        request: CallToolRequest,
        deviceId: String?,
        block: (MaestroSessionManager.MaestroSession) -> T,
    ): T {
        val resolvedDeviceId = deviceId ?: resolveDeviceId(request)
            ?: error(requireDeviceIdMessage())
        val sessionId = optionalSessionId(request)
        if (sessionId != null) {
            return McpSessionRegistry.withSession(sessionId, resolvedDeviceId, block)
        }

        return sessionManager.newSession(
            host = null,
            port = null,
            driverHostPort = null,
            deviceId = resolvedDeviceId,
            platform = null,
        ) { session ->
            block(session)
        }
    }

    fun executeSession(
        sessionManager: MaestroSessionManager,
        request: CallToolRequest,
        deviceId: String?,
        block: (MaestroSessionManager.MaestroSession) -> JsonObject,
    ): String {
        return withSession(sessionManager, request, deviceId) { session ->
            block(session).toString()
        }
    }

    fun runCommand(
        sessionManager: MaestroSessionManager,
        request: CallToolRequest,
        deviceId: String?,
        command: Command,
        message: String,
        extra: (MutableMap<String, Any?>.() -> Unit)? = null,
    ): String {
        val sessionId = optionalSessionId(request)
        val resolvedDeviceId = resolveDeviceId(request, deviceId)
            ?: error(requireDeviceIdMessage())
        return executeSession(sessionManager, request, deviceId) { session ->
            runCommands(session, listOf(MaestroCommand(command = command)))
            McpSessionRegistry.invalidateHierarchy(sessionId)
            successJson(
                deviceId = resolvedDeviceId,
                sessionId = sessionId,
                message = message,
                extra = extra,
            )
        }
    }

    fun runCommands(
        session: MaestroSessionManager.MaestroSession,
        commands: List<MaestroCommand>,
    ) {
        val orchestra = Orchestra(session.maestro)
        runBlocking {
            orchestra.executeCommands(commands)
        }
    }

    fun loadViewHierarchy(
        sessionManager: MaestroSessionManager,
        request: CallToolRequest,
        deviceId: String?,
        refresh: Boolean = false,
    ): ViewHierarchy {
        val resolvedDeviceId = deviceId ?: resolveDeviceId(request)
            ?: error(requireDeviceIdMessage())
        val sessionId = optionalSessionId(request)
        if (sessionId != null && !refresh) {
            McpSessionRegistry.cachedHierarchy(sessionId)?.let { return it }
        }

        val hierarchy = if (sessionId != null) {
            McpSessionRegistry.withSession(sessionId, resolvedDeviceId) { session ->
                session.maestro.viewHierarchy()
            }
        } else {
            withSession(sessionManager, request, resolvedDeviceId) { session ->
                session.maestro.viewHierarchy()
            }
        }

        if (sessionId != null) {
            McpSessionRegistry.cacheHierarchy(sessionId, hierarchy)
        }
        return hierarchy
    }

    fun buildAutomationSelectors(
        request: CallToolRequest,
        requireSelector: Boolean = true,
    ): List<AutomationSelector>? {
        val selectors = request.arguments["selectors"]?.jsonArray
            ?.map { jsonElement ->
                val selector = jsonElement.jsonObject
                AutomationSelector(
                    id = selector.string("id"),
                    text = selector.string("text"),
                    index = selector.int("index"),
                    useFuzzyMatching = selector.boolean("use_fuzzy_matching") ?: true,
                    enabled = selector.boolean("enabled"),
                    checked = selector.boolean("checked"),
                    focused = selector.boolean("focused"),
                    selected = selector.boolean("selected"),
                )
            }
            ?.filterNot { selector ->
                selector.id == null
                    && selector.text == null
                    && selector.enabled == null
                    && selector.checked == null
                    && selector.focused == null
                    && selector.selected == null
            }
            ?.takeIf { it.isNotEmpty() }

        if (selectors != null) {
            return selectors
        }

        val selector = buildAutomationSelector(request, requireSelector)
        return when {
            selector != null -> listOf(selector)
            requireSelector -> null
            else -> emptyList()
        }
    }

    fun buildAutomationSelector(
        request: CallToolRequest,
        requireSelector: Boolean = true,
    ): AutomationSelector? {
        val text = optionalString(request, "text")
        val id = optionalString(request, "id")
        val index = optionalInt(request, "index")
        val useFuzzyMatching = optionalBoolean(request, "use_fuzzy_matching") ?: true
        val enabled = optionalBoolean(request, "enabled")
        val checked = optionalBoolean(request, "checked")
        val focused = optionalBoolean(request, "focused")
        val selected = optionalBoolean(request, "selected")

        if (text == null && id == null && enabled == null && checked == null && focused == null && selected == null && requireSelector) {
            return null
        }

        return AutomationSelector(
            id = id,
            text = text,
            index = index,
            useFuzzyMatching = useFuzzyMatching,
            enabled = enabled,
            checked = checked,
            focused = focused,
            selected = selected,
        )
    }

    fun buildAutomationSnapshotRequest(request: CallToolRequest): AutomationSnapshotRequest {
        val fields = optionalStringList(request, "fields")?.toSet()?.takeIf { it.isNotEmpty() } ?: DEFAULT_AUTOMATION_FIELDS
        val mode = when (optionalString(request, "mode")?.lowercase()) {
            "full" -> AutomationSnapshotMode.FULL
            else -> AutomationSnapshotMode.MINIMAL
        }

        return AutomationSnapshotRequest(
            mode = mode,
            flat = optionalBoolean(request, "flat") ?: true,
            interactiveOnly = optionalBoolean(request, "interactive_only") ?: false,
            fields = fields,
            maxDepth = optionalInt(request, "max_depth"),
            includeStatusBars = optionalBoolean(request, "include_status_bars") ?: false,
            includeSafariWebViews = optionalBoolean(request, "include_safari_web_views") ?: false,
            excludeKeyboardElements = optionalBoolean(request, "exclude_keyboard_elements") ?: false,
            sinceToken = optionalString(request, "since_token"),
        )
    }

    fun buildAutomationQueryRequest(request: CallToolRequest): AutomationQueryRequest? {
        val selectors = buildAutomationSelectors(request) ?: return null
        return AutomationQueryRequest(
            selectors = selectors,
            interactiveOnly = optionalBoolean(request, "interactive_only") ?: false,
            fields = optionalStringList(request, "fields")?.toSet()?.takeIf { it.isNotEmpty() } ?: DEFAULT_AUTOMATION_FIELDS,
            maxDepth = optionalInt(request, "max_depth"),
            includeStatusBars = optionalBoolean(request, "include_status_bars") ?: false,
            includeSafariWebViews = optionalBoolean(request, "include_safari_web_views") ?: false,
            excludeKeyboardElements = optionalBoolean(request, "exclude_keyboard_elements") ?: false,
        )
    }

    fun buildAutomationWaitRequest(request: CallToolRequest): AutomationWaitRequest? {
        val selectors = buildAutomationSelectors(request) ?: return null
        return AutomationWaitRequest(
            selectors = selectors,
            notVisible = optionalBoolean(request, "not_visible") ?: false,
            timeoutMs = optionalLong(request, "timeout_ms") ?: 5_000L,
            pollIntervalMs = optionalLong(request, "poll_interval_ms") ?: 200L,
            interactiveOnly = optionalBoolean(request, "interactive_only") ?: false,
            fields = optionalStringList(request, "fields")?.toSet()?.takeIf { it.isNotEmpty() } ?: DEFAULT_AUTOMATION_FIELDS,
            maxDepth = optionalInt(request, "max_depth"),
            includeStatusBars = optionalBoolean(request, "include_status_bars") ?: false,
            includeSafariWebViews = optionalBoolean(request, "include_safari_web_views") ?: false,
            excludeKeyboardElements = optionalBoolean(request, "exclude_keyboard_elements") ?: false,
        )
    }

    fun automationSnapshotJson(
        deviceId: String,
        sessionId: String?,
        snapshot: AutomationSnapshot,
    ): JsonObject {
        return buildJsonObject {
            put("success", JsonPrimitive(true))
            put("device_id", JsonPrimitive(deviceId))
            sessionId?.let { put("session_id", JsonPrimitive(it)) }
            put("source", JsonPrimitive(snapshot.source))
            put("mode", JsonPrimitive(snapshot.mode.name.lowercase()))
            put("changed", JsonPrimitive(snapshot.changed))
            snapshot.token?.let { put("token", JsonPrimitive(it)) }
            put("node_count", JsonPrimitive(snapshot.nodeCount))
            put("nodes", JsonArray(snapshot.nodes.map(::automationNodeJson)))
        }
    }

    fun automationQueryJson(
        deviceId: String,
        sessionId: String?,
        result: AutomationQueryResult,
    ): JsonObject {
        return buildJsonObject {
            put("success", JsonPrimitive(true))
            put("device_id", JsonPrimitive(deviceId))
            sessionId?.let { put("session_id", JsonPrimitive(it)) }
            put("source", JsonPrimitive(result.source))
            result.token?.let { put("token", JsonPrimitive(it)) }
            put("match_count", JsonPrimitive(result.matches.sumOf(AutomationQueryMatch::matchCount)))
            put("matches", JsonArray(result.matches.map { match ->
                buildJsonObject {
                    put("selector_index", JsonPrimitive(match.selectorIndex))
                    put("match_count", JsonPrimitive(match.matchCount))
                    put("nodes", JsonArray(match.nodes.map(::automationNodeJson)))
                }
            }))
        }
    }

    fun automationWaitJson(
        deviceId: String,
        sessionId: String?,
        result: AutomationWaitResult,
    ): JsonObject {
        return buildJsonObject {
            put("success", JsonPrimitive(result.satisfied))
            put("device_id", JsonPrimitive(deviceId))
            sessionId?.let { put("session_id", JsonPrimitive(it)) }
            put("satisfied", JsonPrimitive(result.satisfied))
            put("source", JsonPrimitive(result.source))
            put("elapsed_ms", JsonPrimitive(result.elapsedMs))
            result.token?.let { put("token", JsonPrimitive(it)) }
            result.snapshot?.let { snapshot ->
                put("snapshot", automationSnapshotJson(deviceId, sessionId, snapshot))
            }
        }
    }

    fun successJson(
        deviceId: String,
        sessionId: String? = null,
        message: String,
        extra: (MutableMap<String, Any?>.() -> Unit)? = null,
    ): JsonObject {
        val values = linkedMapOf<String, Any?>(
            "success" to true,
            "device_id" to deviceId,
            "session_id" to sessionId,
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

    fun nodeToJson(node: TreeNode): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(node.attributes["text"] ?: ""))
        put("id", JsonPrimitive(node.attributes["resource-id"] ?: node.attributes["identifier"] ?: node.attributes["id"] ?: ""))
        put("bounds", JsonPrimitive(node.attributes["bounds"] ?: ""))
        put("enabled", JsonPrimitive(node.enabled))
        put("checked", JsonPrimitive(node.checked))
        put("focused", JsonPrimitive(node.focused))
        put("selected", JsonPrimitive(node.selected))
        putJsonObject("attributes") {
            node.attributes.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
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

    private fun automationNodeJson(node: AutomationNode): JsonObject = buildJsonObject {
        node.id?.let { put("id", JsonPrimitive(it)) }
        node.text?.let { put("text", JsonPrimitive(it)) }
        node.bounds?.let { put("bounds", JsonPrimitive(it)) }
        node.enabled?.let { put("enabled", JsonPrimitive(it)) }
        node.checked?.let { put("checked", JsonPrimitive(it)) }
        node.focused?.let { put("focused", JsonPrimitive(it)) }
        node.selected?.let { put("selected", JsonPrimitive(it)) }
        node.clickable?.let { put("clickable", JsonPrimitive(it)) }
        put("depth", JsonPrimitive(node.depth))
    }

    private fun optionalStringList(request: CallToolRequest, name: String): List<String>? =
        request.arguments[name]
            ?.jsonArray
            ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(name: String): Boolean? =
        this[name]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.int(name: String): Int? =
        this[name]?.jsonPrimitive?.intOrNull

    private fun selectorRegex(value: String, useFuzzyMatching: Boolean): String {
        return if (useFuzzyMatching) {
            ".*${Regex.escape(value)}.*"
        } else {
            value
        }
    }
}
