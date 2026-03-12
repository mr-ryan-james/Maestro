package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import maestro.AutomationQueryRequest
import maestro.AutomationSelector
import maestro.AutomationSnapshotMode
import maestro.AutomationSnapshotRequest
import maestro.DEFAULT_AUTOMATION_FIELDS
import maestro.cli.mcp.McpSessionRegistry
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.DismissKnownOverlaysCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.TapFirstVisibleNowCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.WaitForAnimationToEndCommand

private fun baseSessionProperties() = buildJsonObject {
    putJsonObject("device_id") {
        put("type", "string")
        put("description", "The device id for the session")
    }
    putJsonObject("session_id") {
        put("type", "string")
        put("description", "Existing hot session id returned by open_session")
    }
}

private fun selectorSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("id") { put("type", "string") }
        putJsonObject("text") { put("type", "string") }
        putJsonObject("index") { put("type", "integer") }
        putJsonObject("use_fuzzy_matching") { put("type", "boolean") }
        putJsonObject("enabled") { put("type", "boolean") }
        putJsonObject("checked") { put("type", "boolean") }
        putJsonObject("focused") { put("type", "boolean") }
        putJsonObject("selected") { put("type", "boolean") }
    }
}

private fun automationCommonProperties(includeSelectors: Boolean = false): JsonObject = buildJsonObject {
    baseSessionProperties().forEach { (key, value) -> put(key, value) }
    if (includeSelectors) {
        putJsonObject("selectors") {
            put("type", "array")
            put("description", "Optional array of selector objects. Falls back to top-level id/text selector fields when omitted.")
            put("items", selectorSchema())
        }
        putJsonObject("text") { put("type", "string") }
        putJsonObject("id") { put("type", "string") }
        putJsonObject("index") { put("type", "integer") }
        putJsonObject("use_fuzzy_matching") { put("type", "boolean") }
        putJsonObject("enabled") { put("type", "boolean") }
        putJsonObject("checked") { put("type", "boolean") }
        putJsonObject("focused") { put("type", "boolean") }
        putJsonObject("selected") { put("type", "boolean") }
    }
    putJsonObject("interactive_only") {
        put("type", "boolean")
        put("description", "Only return interactive or semantically meaningful nodes")
    }
    putJsonObject("fields") {
        put("type", "array")
        put("description", "Optional field whitelist for returned nodes")
        putJsonObject("items") {
            put("type", "string")
        }
    }
    putJsonObject("max_depth") {
        put("type", "integer")
        put("description", "Optional maximum depth to traverse")
    }
    putJsonObject("include_status_bars") { put("type", "boolean") }
    putJsonObject("include_safari_web_views") { put("type", "boolean") }
    putJsonObject("exclude_keyboard_elements") { put("type", "boolean") }
}

private fun snapshotProperties(): JsonObject = buildJsonObject {
    automationCommonProperties(includeSelectors = false).forEach { (key, value) -> put(key, value) }
    putJsonObject("mode") {
        put("type", "string")
        put("description", "minimal or full")
    }
    putJsonObject("flat") {
        put("type", "boolean")
        put("description", "Whether to return flattened nodes")
    }
    putJsonObject("since_token") {
        put("type", "string")
        put("description", "If provided, returns changed=false and no nodes when the token matches")
    }
}

private fun normalizeBatchActionType(raw: String): String =
    raw.trim().replace("-", "_").lowercase()

private fun selectorRegex(value: String, useFuzzyMatching: Boolean): String {
    return if (useFuzzyMatching) {
        ".*${Regex.escape(value)}.*"
    } else {
        value
    }
}

private fun JsonObject.stringValue(vararg keys: String): String? =
    keys.asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        .firstOrNull()

private fun JsonObject.booleanValue(vararg keys: String): Boolean? =
    keys.asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.booleanOrNull }
        .firstOrNull()

private fun JsonObject.intValue(vararg keys: String): Int? =
    keys.asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.intOrNull }
        .firstOrNull()

private fun JsonObject.longValue(vararg keys: String): Long? =
    keys.asSequence()
        .mapNotNull { key -> this[key]?.jsonPrimitive?.longOrNull }
        .firstOrNull()

private fun JsonObject.stringSetValue(vararg keys: String): Set<String>? =
    keys.asSequence()
        .mapNotNull { key ->
            this[key]
                ?.jsonArray
                ?.mapNotNull { item -> item.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
        }
        .firstOrNull()

private fun JsonObject.selectorObject(): JsonObject? =
    this["selector"]?.jsonObject ?: this

private fun buildBatchElementSelector(action: JsonObject): ElementSelector? {
    val selector = action.selectorObject() ?: return null
    val text = selector.stringValue("text")
    val id = selector.stringValue("id")
    val index = selector.intValue("index")
    val useFuzzyMatching = selector.booleanValue("use_fuzzy_matching", "useFuzzyMatching") ?: true
    val enabled = selector.booleanValue("enabled")
    val checked = selector.booleanValue("checked")
    val focused = selector.booleanValue("focused")
    val selected = selector.booleanValue("selected")

    if (text == null && id == null && enabled == null && checked == null && focused == null && selected == null) {
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

private fun buildBatchElementSelectors(action: JsonObject): List<ElementSelector> {
    val selectors = action["selectors"]?.jsonArray
        ?.mapNotNull { item -> buildBatchElementSelector(item.jsonObject) }
        ?.takeIf { it.isNotEmpty() }
    if (selectors != null) {
        return selectors
    }

    return buildBatchElementSelector(action)?.let(::listOf) ?: emptyList()
}

private fun buildBatchAutomationSelector(action: JsonObject): AutomationSelector? {
    val selector = action.selectorObject() ?: return null
    val text = selector.stringValue("text")
    val id = selector.stringValue("id")
    val index = selector.intValue("index")
    val useFuzzyMatching = selector.booleanValue("use_fuzzy_matching", "useFuzzyMatching") ?: true
    val enabled = selector.booleanValue("enabled")
    val checked = selector.booleanValue("checked")
    val focused = selector.booleanValue("focused")
    val selected = selector.booleanValue("selected")

    if (text == null && id == null && enabled == null && checked == null && focused == null && selected == null) {
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

private fun buildBatchSnapshotRequest(action: JsonObject): AutomationSnapshotRequest {
    val fields = action.stringSetValue("fields") ?: DEFAULT_AUTOMATION_FIELDS
    val mode = when (action.stringValue("mode")?.lowercase()) {
        "full" -> AutomationSnapshotMode.FULL
        else -> AutomationSnapshotMode.MINIMAL
    }

    return AutomationSnapshotRequest(
        mode = mode,
        flat = action.booleanValue("flat") ?: true,
        interactiveOnly = action.booleanValue("interactive_only", "interactiveOnly") ?: false,
        fields = fields,
        maxDepth = action.intValue("max_depth", "maxDepth"),
        includeStatusBars = action.booleanValue("include_status_bars", "includeStatusBars") ?: false,
        includeSafariWebViews = action.booleanValue("include_safari_web_views", "includeSafariWebViews") ?: false,
        excludeKeyboardElements = action.booleanValue("exclude_keyboard_elements", "excludeKeyboardElements") ?: false,
        sinceToken = action.stringValue("since_token", "sinceToken"),
    )
}

private fun buildBatchQueryRequest(action: JsonObject): AutomationQueryRequest? {
    val selector = buildBatchAutomationSelector(action) ?: return null
    return AutomationQueryRequest(
        selectors = listOf(selector),
        interactiveOnly = action.booleanValue("interactive_only", "interactiveOnly") ?: false,
        fields = action.stringSetValue("fields") ?: DEFAULT_AUTOMATION_FIELDS,
        maxDepth = action.intValue("max_depth", "maxDepth"),
        includeStatusBars = action.booleanValue("include_status_bars", "includeStatusBars") ?: false,
        includeSafariWebViews = action.booleanValue("include_safari_web_views", "includeSafariWebViews") ?: false,
        excludeKeyboardElements = action.booleanValue("exclude_keyboard_elements", "excludeKeyboardElements") ?: false,
    )
}

private class HotBatchExecutor(
    private val orchestra: Orchestra,
) {
    private var jsInitialized = false

    fun run(commands: List<MaestroCommand>) {
        runBlocking {
            orchestra.executeCommands(
                commands = commands,
                shouldReinitJsEngine = !jsInitialized,
            )
        }
        jsInitialized = true
    }
}

private fun batchResultJson(
    index: Int,
    type: String,
    ok: Boolean,
    optional: Boolean,
    skipped: Boolean = false,
    summary: String,
    payload: JsonObject? = null,
    error: String? = null,
): JsonObject = buildJsonObject {
    put("index", JsonPrimitive(index))
    put("type", JsonPrimitive(type))
    put("ok", JsonPrimitive(ok))
    put("optional", JsonPrimitive(optional))
    put("skipped", JsonPrimitive(skipped))
    put("summary", JsonPrimitive(summary))
    payload?.let { put("payload", it) }
    error?.let { put("error", JsonPrimitive(it)) }
}

object OpenSessionTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "open_session",
                description = "Open a long-lived device session for fast repeated calls.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The device id to open a session for")
                        }
                        putJsonObject("app_id") {
                            put("type", "string")
                            put("description", "Optional app/bundle id associated with the session")
                        }
                        putJsonObject("project_root") {
                            put("type", "string")
                            put("description", "Optional project root associated with the session")
                        }
                        putJsonObject("owner") {
                            put("type", "string")
                            put("description", "Optional owner tag used for shared-host coordination")
                        }
                        putJsonObject("label") {
                            put("type", "string")
                            put("description", "Optional run/session label used for shared-host coordination")
                        }
                        putJsonObject("driver_host_port") {
                            put("type", "integer")
                            put("description", "Optional driver host port override")
                        }
                        putJsonObject("ttl_ms") {
                            put("type", "integer")
                            put("description", "Optional idle time-to-live for the hot session in milliseconds")
                        }
                    },
                    required = emptyList(),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.resolveDeviceId(request)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(
                    listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                    isError = true,
                )
            }

            val summary = McpSessionRegistry.openSession(
                sessionManager = sessionManager,
                deviceId = deviceId,
                appId = ToolSupport.optionalString(request, "app_id"),
                projectRoot = ToolSupport.optionalString(request, "project_root"),
                owner = ToolSupport.optionalString(request, "owner"),
                label = ToolSupport.optionalString(request, "label"),
                driverHostPort = ToolSupport.optionalInt(request, "driver_host_port"),
                ttlMsOverride = ToolSupport.optionalLong(request, "ttl_ms"),
            )
            val response = ToolSupport.jsonObject(
                mapOf(
                    "success" to true,
                    "session_id" to summary.sessionId,
                    "device_id" to summary.deviceId,
                    "app_id" to summary.appId,
                    "project_root" to summary.projectRoot,
                    "owner" to summary.owner,
                    "label" to summary.label,
                    "platform" to summary.platform,
                    "driver_host_port" to summary.driverHostPort,
                    "opened_at_ms" to summary.createdAt,
                    "last_accessed_at_ms" to summary.lastUsedAt,
                    "ttl_ms" to summary.ttlMs,
                    "reused" to summary.reused,
                ),
            ).toString()
            CallToolResult(content = listOf(TextContent(response)))
        }
    }
}

object ResumeSessionTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "resume_session",
                description = "Refresh and optionally extend the TTL of an existing hot session.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Existing session id returned by open_session")
                        }
                        putJsonObject("ttl_ms") {
                            put("type", "integer")
                            put("description", "Optional idle time-to-live override in milliseconds")
                        }
                    },
                    required = listOf("session_id"),
                ),
            ),
        ) { request ->
            val sessionId = ToolSupport.requiredString(request, "session_id")
                ?: return@RegisteredTool CallToolResult(
                    listOf(TextContent("session_id is required")),
                    isError = true,
                )
            val summary = McpSessionRegistry.resumeSession(
                sessionId = sessionId,
                ttlMsOverride = ToolSupport.optionalLong(request, "ttl_ms"),
            ) ?: return@RegisteredTool CallToolResult(
                listOf(TextContent("Unknown or expired session_id: $sessionId")),
                isError = true,
            )
            val response = ToolSupport.jsonObject(
                mapOf(
                    "success" to true,
                    "session_id" to summary.sessionId,
                    "device_id" to summary.deviceId,
                    "app_id" to summary.appId,
                    "project_root" to summary.projectRoot,
                    "owner" to summary.owner,
                    "label" to summary.label,
                    "platform" to summary.platform,
                    "driver_host_port" to summary.driverHostPort,
                    "opened_at_ms" to summary.createdAt,
                    "last_accessed_at_ms" to summary.lastUsedAt,
                    "ttl_ms" to summary.ttlMs,
                    "reused" to summary.reused,
                ),
            ).toString()
            CallToolResult(content = listOf(TextContent(response)))
        }
    }
}

object CloseSessionTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "close_session",
                description = "Close a previously opened long-lived device session.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Existing session id returned by open_session")
                        }
                    },
                    required = listOf("session_id"),
                ),
            ),
        ) { request ->
            val sessionId = ToolSupport.requiredString(request, "session_id")
            if (sessionId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("session_id is required")), isError = true)
            }

            val closed = McpSessionRegistry.closeSession(sessionId)
            val response = ToolSupport.jsonObject(
                mapOf(
                    "success" to closed,
                    "session_id" to sessionId,
                    "message" to if (closed) "Session closed successfully" else "Unknown session_id",
                ),
            ).toString()
            CallToolResult(content = listOf(TextContent(response)), isError = !closed)
        }
    }
}

object ListSessionsTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "list_sessions",
                description = "List active long-lived device sessions owned by the MCP server.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {},
                    required = emptyList(),
                ),
            ),
        ) {
            val sessions = McpSessionRegistry.listSessions()
            val response = buildJsonObject {
                put("success", JsonPrimitive(true))
                put("count", JsonPrimitive(sessions.size))
                put("sessions", buildJsonArray {
                    sessions.forEach { session ->
                        add(
                            ToolSupport.jsonObject(
                                mapOf(
                                    "session_id" to session.sessionId,
                                    "device_id" to session.deviceId,
                                    "app_id" to session.appId,
                                    "project_root" to session.projectRoot,
                                    "owner" to session.owner,
                                    "label" to session.label,
                                    "platform" to session.platform,
                                    "driver_host_port" to session.driverHostPort,
                                    "opened_at_ms" to session.createdAt,
                                    "last_accessed_at_ms" to session.lastUsedAt,
                                    "ttl_ms" to session.ttlMs,
                                ),
                            ),
                        )
                    }
                })
            }.toString()
            CallToolResult(content = listOf(TextContent(response)))
        }
    }
}

object HardResetSessionTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "hard_reset_session",
                description = "Close and recreate an existing hot session for the same device.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("session_id") {
                            put("type", "string")
                            put("description", "Existing session id returned by open_session")
                        }
                    },
                    required = listOf("session_id"),
                ),
            ),
        ) { request ->
            val sessionId = ToolSupport.requiredString(request, "session_id")
            if (sessionId == null) {
                return@RegisteredTool CallToolResult(listOf(TextContent("session_id is required")), isError = true)
            }

            val previous = McpSessionRegistry.sessionHandle(sessionId)
            if (previous == null) {
                return@RegisteredTool CallToolResult(
                    content = listOf(TextContent("Unknown or expired session_id: $sessionId")),
                    isError = true,
                )
            }

            McpSessionRegistry.closeSession(sessionId, source = "hard_reset")
            val reopened = McpSessionRegistry.openSession(
                sessionManager = sessionManager,
                deviceId = previous.deviceId,
                appId = previous.appId,
                projectRoot = previous.projectRoot,
                owner = previous.owner,
                label = previous.label,
                driverHostPort = previous.driverHostPort,
                ttlMsOverride = previous.ttlMs,
            )
            val response = ToolSupport.jsonObject(
                mapOf(
                    "success" to true,
                    "previous_session_id" to sessionId,
                    "session_id" to reopened.sessionId,
                    "device_id" to reopened.deviceId,
                    "app_id" to reopened.appId,
                    "project_root" to reopened.projectRoot,
                    "owner" to reopened.owner,
                    "label" to reopened.label,
                    "platform" to reopened.platform,
                    "driver_host_port" to reopened.driverHostPort,
                    "opened_at_ms" to reopened.createdAt,
                    "last_accessed_at_ms" to reopened.lastUsedAt,
                    "ttl_ms" to reopened.ttlMs,
                ),
            ).toString()
            CallToolResult(content = listOf(TextContent(response)))
        }
    }
}

object QueryElementsTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "query_elements",
                description = "Query matching elements with lightweight JSON output.",
                inputSchema = Tool.Input(
                    properties = automationCommonProperties(includeSelectors = true),
                    required = emptyList(),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.resolveDeviceId(request)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(
                    listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                    isError = true,
                )
            }

            val queryRequest = ToolSupport.buildAutomationQueryRequest(request)
                ?: return@RegisteredTool CallToolResult(
                    listOf(TextContent("At least one selector is required via selectors or top-level id/text fields")),
                    isError = true,
                )
            val sessionId = ToolSupport.optionalSessionId(request)
            val response = ToolSupport.executeSession(sessionManager, request, deviceId) { session ->
                val queryResult = session.maestro.driver.queryAutomationElements(queryRequest)
                ToolSupport.automationQueryJson(
                    deviceId = deviceId,
                    sessionId = sessionId,
                    result = queryResult,
                )
            }
            CallToolResult(content = listOf(TextContent(response)))
        }
    }
}

object SnapshotTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "snapshot",
                description = "Return a lightweight flattened snapshot of the current UI.",
                inputSchema = Tool.Input(
                    properties = snapshotProperties(),
                    required = emptyList(),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.resolveDeviceId(request)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(
                    listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                    isError = true,
                )
            }

            val sessionId = ToolSupport.optionalSessionId(request)
            val snapshotRequest = ToolSupport.buildAutomationSnapshotRequest(request)
            val response = ToolSupport.executeSession(sessionManager, request, deviceId) { session ->
                val snapshot = session.maestro.driver.automationSnapshot(snapshotRequest)
                ToolSupport.automationSnapshotJson(
                    deviceId = deviceId,
                    sessionId = sessionId,
                    snapshot = snapshot,
                )
            }
            CallToolResult(content = listOf(TextContent(response)))
        }
    }
}

object AwaitEventTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "await_event",
                description = "Wait for selectors to become visible or disappear using the fast automation abstraction.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        automationCommonProperties(includeSelectors = true).forEach { (key, value) -> put(key, value) }
                        putJsonObject("timeout_ms") {
                            put("type", "integer")
                            put("description", "Maximum wait time in milliseconds")
                        }
                        putJsonObject("poll_interval_ms") {
                            put("type", "integer")
                            put("description", "Polling interval in milliseconds")
                        }
                        putJsonObject("not_visible") {
                            put("type", "boolean")
                            put("description", "Invert the wait and succeed when nothing matches")
                        }
                    },
                    required = emptyList(),
                ),
            ),
        ) { request ->
            val deviceId = ToolSupport.resolveDeviceId(request)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(
                    listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                    isError = true,
                )
            }

            val waitRequest = ToolSupport.buildAutomationWaitRequest(request)
                ?: return@RegisteredTool CallToolResult(
                    listOf(TextContent("At least one selector is required via selectors or top-level id/text fields")),
                    isError = true,
                )
            val sessionId = ToolSupport.optionalSessionId(request)
            val resultJson = ToolSupport.executeSession(sessionManager, request, deviceId) { session ->
                val waitResult = session.maestro.driver.awaitAutomation(waitRequest)
                ToolSupport.automationWaitJson(
                    deviceId = deviceId,
                    sessionId = sessionId,
                    result = waitResult,
                )
            }
            val satisfied = runCatching {
                Json.parseToJsonElement(resultJson).jsonObject["satisfied"]?.jsonPrimitive?.booleanOrNull
            }.getOrNull() ?: false
            CallToolResult(content = listOf(TextContent(resultJson)), isError = !satisfied)
        }
    }
}

object ExecuteBatchTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "execute_batch",
                description = "Execute a typed batch of hot-session automation tool calls in one request.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        baseSessionProperties().forEach { (key, value) -> put(key, value) }
                        putJsonObject("stop_on_error") {
                            put("type", "boolean")
                            put("description", "Stop the batch after the first non-optional failure")
                        }
                        putJsonObject("actions") {
                            put("type", "array")
                            put("description", "Ordered array of actions with action name and arguments")
                            putJsonObject("items") {
                                put("type", "object")
                            }
                        }
                    },
                    required = listOf("actions"),
                ),
            ),
        ) { request ->
            val actions = request.arguments["actions"]?.jsonArray
                ?: return@RegisteredTool CallToolResult(
                    listOf(TextContent("actions is required")),
                    isError = true,
                )
            val deviceId = ToolSupport.resolveDeviceId(request)
            if (deviceId == null) {
                return@RegisteredTool CallToolResult(
                    listOf(TextContent(ToolSupport.requireDeviceIdMessage())),
                    isError = true,
                )
            }
            val sessionId = ToolSupport.optionalSessionId(request)
            val stopOnError = ToolSupport.optionalBoolean(request, "stop_on_error")
                ?: ToolSupport.optionalBoolean(request, "stopOnError")
                ?: true
            val response = ToolSupport.withSession(sessionManager, request, deviceId) { session ->
                val batchExecutor = HotBatchExecutor(Orchestra(session.maestro))
                val results = mutableListOf<JsonObject>()
                var success = true
                var stoppedEarly = false

                fun invalidateIfHotSession() {
                    sessionId?.let(McpSessionRegistry::invalidateHierarchy)
                }

                for ((index, actionElement) in actions.withIndex()) {
                    val result = try {
                        val action = actionElement.jsonObject
                        val type = normalizeBatchActionType(
                            action.stringValue("type")
                                ?: error("actions[$index].type is required"),
                        )
                        val optional = action.booleanValue("optional") ?: false
                        val timeoutMs = action.longValue("timeout_ms", "timeoutMs")

                        when (type) {
                            "tap_on" -> {
                                val selector = buildBatchElementSelector(action)
                                    ?: error("actions[$index].selector.id or selector.text is required")
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            TapOnElementCommand(
                                                selector = selector,
                                                retryIfNoChange = false,
                                                waitUntilVisible = false,
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                invalidateIfHotSession()
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "tap_on completed",
                                )
                            }

                            "tap_first_visible_now" -> {
                                val selectors = buildBatchElementSelectors(action)
                                if (selectors.isEmpty()) {
                                    error("actions[$index].selectors or selector.id/selector.text is required")
                                }
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            TapFirstVisibleNowCommand(
                                                selectors = selectors,
                                                waitToSettleTimeoutMs = action.intValue("wait_to_settle_timeout_ms", "waitToSettleTimeoutMs"),
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                invalidateIfHotSession()
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "tap_first_visible_now completed",
                                )
                            }

                            "input_text" -> {
                                val text = action.stringValue("text")
                                    ?: error("actions[$index].text is required")
                                val selector = buildBatchElementSelector(action)
                                val commands = mutableListOf<MaestroCommand>()
                                selector?.let {
                                    commands += MaestroCommand(command =
                                        TapOnElementCommand(
                                            selector = it,
                                            retryIfNoChange = false,
                                            waitUntilVisible = false,
                                            optional = optional,
                                        ),
                                    )
                                }
                                commands += MaestroCommand(command =
                                    InputTextCommand(
                                        text = text,
                                        optional = optional,
                                    ),
                                )
                                batchExecutor.run(commands)
                                invalidateIfHotSession()
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "input_text completed",
                                )
                            }

                            "press_key" -> {
                                val key = action.stringValue("key")
                                    ?: error("actions[$index].key is required")
                                val keyCode = ToolSupport.parseKeyCode(key)
                                    ?: error("Unsupported key code: $key")
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            PressKeyCommand(
                                                code = keyCode,
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                invalidateIfHotSession()
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "press_key completed",
                                )
                            }

                            "assert_visible" -> {
                                val selector = buildBatchElementSelector(action)
                                    ?: error("actions[$index].selector.id or selector.text is required")
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            AssertConditionCommand(
                                                condition = Condition(visible = selector),
                                                timeout = timeoutMs?.toString(),
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "assert_visible completed",
                                )
                            }

                            "assert_visible_now" -> {
                                val selector = buildBatchElementSelector(action)
                                    ?: error("actions[$index].selector.id or selector.text is required")
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            AssertConditionCommand(
                                                condition = Condition(visibleNow = selector),
                                                timeout = "0",
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "assert_visible_now completed",
                                )
                            }

                            "assert_not_visible" -> {
                                val selector = buildBatchElementSelector(action)
                                    ?: error("actions[$index].selector.id or selector.text is required")
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            AssertConditionCommand(
                                                condition = Condition(notVisible = selector),
                                                timeout = timeoutMs?.toString(),
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "assert_not_visible completed",
                                )
                            }

                            "assert_not_visible_now" -> {
                                val selector = buildBatchElementSelector(action)
                                    ?: error("actions[$index].selector.id or selector.text is required")
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            AssertConditionCommand(
                                                condition = Condition(notVisibleNow = selector),
                                                timeout = "0",
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "assert_not_visible_now completed",
                                )
                            }

                            "dismiss_known_overlays" -> {
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            DismissKnownOverlaysCommand(
                                                maxPasses = (action.intValue("max_passes", "maxPasses") ?: 2).coerceIn(1, 5),
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                invalidateIfHotSession()
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "dismiss_known_overlays completed",
                                )
                            }

                            "hide_keyboard" -> {
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            HideKeyboardCommand(optional = optional),
                                        ),
                                    ),
                                )
                                invalidateIfHotSession()
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "hide_keyboard completed",
                                )
                            }

                            "snapshot" -> {
                                val snapshot = session.maestro.driver.automationSnapshot(
                                    buildBatchSnapshotRequest(action),
                                )
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "snapshot completed",
                                    payload = ToolSupport.automationSnapshotJson(
                                        deviceId = deviceId,
                                        sessionId = sessionId,
                                        snapshot = snapshot,
                                    ),
                                )
                            }

                            "query_elements" -> {
                                val queryRequest = buildBatchQueryRequest(action)
                                    ?: error("actions[$index].selector.id or selector.text is required")
                                val queryResult = session.maestro.driver.queryAutomationElements(queryRequest)
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "query_elements completed",
                                    payload = ToolSupport.automationQueryJson(
                                        deviceId = deviceId,
                                        sessionId = sessionId,
                                        result = queryResult,
                                    ),
                                )
                            }

                            "open_link" -> {
                                val link = action.stringValue("link")
                                    ?: error("actions[$index].link is required")
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            OpenLinkCommand(
                                                link = link,
                                                autoVerify = action.booleanValue("auto_verify", "autoVerify"),
                                                browser = action.booleanValue("browser"),
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                invalidateIfHotSession()
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "open_link completed",
                                )
                            }

                            "wait_for_animation_to_end", "wait_for_idle" -> {
                                batchExecutor.run(
                                    listOf(
                                        MaestroCommand(command =
                                            WaitForAnimationToEndCommand(
                                                timeout = timeoutMs,
                                                optional = optional,
                                            ),
                                        ),
                                    ),
                                )
                                batchResultJson(
                                    index = index,
                                    type = type,
                                    ok = true,
                                    optional = optional,
                                    summary = "$type completed",
                                )
                            }

                            else -> error("Unsupported action type: $type")
                        }
                    } catch (error: Throwable) {
                        val action = actionElement as? JsonObject
                        val type = action?.stringValue("type")?.let(::normalizeBatchActionType) ?: "unknown"
                        val optional = action?.booleanValue("optional") ?: false
                        if (optional) {
                            batchResultJson(
                                index = index,
                                type = type,
                                ok = true,
                                optional = true,
                                skipped = true,
                                summary = "$type skipped: ${error.message ?: error}",
                                error = error.message ?: error.toString(),
                            )
                        } else {
                            success = false
                            batchResultJson(
                                index = index,
                                type = type,
                                ok = false,
                                optional = false,
                                summary = "$type failed: ${error.message ?: error}",
                                error = error.message ?: error.toString(),
                            )
                        }
                    }

                    results += result
                    val actionOk = result["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (!actionOk && stopOnError) {
                        stoppedEarly = index < actions.lastIndex
                        break
                    }
                }

                buildJsonObject {
                    put("success", JsonPrimitive(success))
                    put("device_id", JsonPrimitive(deviceId))
                    sessionId?.let { put("session_id", JsonPrimitive(it)) }
                    put("action_count", JsonPrimitive(actions.size))
                    put("executed_count", JsonPrimitive(results.size))
                    put("stopped_early", JsonPrimitive(stoppedEarly))
                    put("results", JsonArray(results))
                }.toString()
            }
            val success = runCatching {
                Json.parseToJsonElement(response).jsonObject["success"]?.jsonPrimitive?.booleanOrNull
            }.getOrNull() ?: false
            CallToolResult(content = listOf(TextContent(response)), isError = !success)
        }
    }
}
