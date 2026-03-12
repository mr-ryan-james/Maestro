package maestro.cli.daemon

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import maestro.AutomationQueryMatch
import maestro.AutomationQueryRequest
import maestro.AutomationSelector
import maestro.AutomationSnapshotMode
import maestro.AutomationSnapshotRequest
import maestro.AutomationWaitRequest
import maestro.DEFAULT_AUTOMATION_FIELDS
import maestro.KeyCode
import maestro.ScrollDirection
import maestro.cli.mcp.McpSessionRegistry
import maestro.cli.mcp.tools.CompiledFlowCache
import maestro.cli.mcp.tools.ToolSupport
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.WorkingDirectory
import maestro.debuglog.LiveTraceLogger
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.DismissKnownOverlaysCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapFirstVisibleNowCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.utils.MaestroRunMetadata
import org.slf4j.LoggerFactory

private val daemonLogger = LoggerFactory.getLogger("MaestroDaemon")
private val daemonJson = Json { ignoreUnknownKeys = true }
private val daemonMapper = jacksonObjectMapper()

fun runMaestroDaemonServer(port: Int) {
    val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
        install(WebSockets)
        installStatusPages()

        routing {
            get("/health") {
                call.respondJson(
                    buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("port", JsonPrimitive(port))
                        put("working_dir", JsonPrimitive(WorkingDirectory.baseDir.absolutePath))
                        put("session_count", JsonPrimitive(McpSessionRegistry.sessionCount()))
                        put("bridge_connections", JsonPrimitive(DaemonBridgeRegistry.connectionCount()))
                    },
                )
            }

            get("/api/sessions") {
                call.respondJson(sessionListJson())
            }

            post("/api/open_session") {
                val body = call.receiveJsonObject()
                val deviceId = body.stringValue("device_id")
                    ?: error("device_id is required")
                val owner = body.stringValue("owner")
                val label = body.stringValue("label")
                initializeRunMetadata(owner, label)

                val handle = McpSessionRegistry.openSession(
                    sessionManager = MaestroSessionManager,
                    deviceId = deviceId,
                    appId = body.stringValue("app_id"),
                    projectRoot = resolveProjectRoot(body),
                    owner = owner,
                    label = label,
                    driverHostPort = body.intValue("driver_host_port"),
                    ttlMsOverride = body.longValue("ttl_ms"),
                )
                call.respondJson(sessionJson(handle))
            }

            post("/api/resume_session") {
                val body = call.receiveJsonObject()
                val sessionId = body.stringValue("session_id")
                    ?: error("session_id is required")
                val handle = McpSessionRegistry.resumeSession(
                    sessionId = sessionId,
                    ttlMsOverride = body.longValue("ttl_ms"),
                ) ?: throw IllegalStateException("Unknown or expired session_id: $sessionId")
                call.respondJson(sessionJson(handle))
            }

            post("/api/close_session") {
                val body = call.receiveJsonObject()
                val sessionId = body.stringValue("session_id")
                    ?: error("session_id is required")
                call.respondJson(
                    buildJsonObject {
                        put("ok", JsonPrimitive(McpSessionRegistry.closeSession(sessionId, source = "daemon_api")))
                        put("session_id", JsonPrimitive(sessionId))
                    },
                )
            }

            post("/api/hard_reset_session") {
                val body = call.receiveJsonObject()
                val sessionId = body.stringValue("session_id")
                    ?: error("session_id is required")
                val existing = McpSessionRegistry.sessionHandle(sessionId)
                    ?: throw IllegalStateException("Unknown or expired session_id: $sessionId")
                McpSessionRegistry.closeSession(sessionId, source = "daemon_hard_reset")
                val reopened = McpSessionRegistry.openSession(
                    sessionManager = MaestroSessionManager,
                    deviceId = existing.deviceId,
                    appId = existing.appId,
                    projectRoot = existing.projectRoot,
                    owner = existing.owner,
                    label = existing.label,
                    driverHostPort = existing.driverHostPort,
                    ttlMsOverride = body.longValue("ttl_ms") ?: existing.ttlMs,
                )
                call.respondJson(sessionJson(reopened))
            }

            post("/api/snapshot") {
                val body = call.receiveJsonObject()
                val sessionHandle = requiredSessionHandle(body)
                val request = buildSnapshotRequest(body)
                val snapshot = McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
                    session.maestro.driver.automationSnapshot(request)
                }
                call.respondJson(
                    ToolSupport.automationSnapshotJson(
                        deviceId = sessionHandle.deviceId,
                        sessionId = sessionHandle.sessionId,
                        snapshot = snapshot,
                    ),
                )
            }

            post("/api/query_elements") {
                val body = call.receiveJsonObject()
                val sessionHandle = requiredSessionHandle(body)
                val request = buildQueryRequest(body)
                    ?: error("selectors or selector.id/selector.text is required")
                val result = McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
                    session.maestro.driver.queryAutomationElements(request)
                }
                call.respondJson(
                    ToolSupport.automationQueryJson(
                        deviceId = sessionHandle.deviceId,
                        sessionId = sessionHandle.sessionId,
                        result = result,
                    ),
                )
            }

            post("/api/await_event") {
                val body = call.receiveJsonObject()
                val sessionHandle = requiredSessionHandle(body)
                val event = body["event"]?.jsonObject ?: error("event is required")
                val timeoutMs = body.longValue("timeout_ms") ?: 5_000L
                val pollIntervalMs = body.longValue("poll_interval_ms") ?: 100L
                val result = awaitDaemonEvent(sessionHandle, event, timeoutMs, pollIntervalMs)
                call.respondJson(result)
            }

            post("/api/execute_batch") {
                val body = call.receiveJsonObject()
                val sessionHandle = requiredSessionHandle(body)
                val actions = body["actions"]?.jsonArray ?: error("actions is required")
                val stopOnError = body.booleanValue("stop_on_error") ?: true
                call.respondJson(executeBatch(sessionHandle, actions, stopOnError))
            }

            post("/api/run_compiled_flow") {
                val body = call.receiveJsonObject()
                val sessionHandle = requiredSessionHandle(body)
                call.respondJson(runCompiledFlow(sessionHandle, body))
            }

            post("/run") {
                val body = call.receiveJsonObject()
                call.respondJson(runLegacyDaemonRun(body))
            }

            post("/api/run_macro") {
                val body = call.receiveJsonObject()
                val sessionHandle = requiredSessionHandle(body)
                call.respondJson(runMacro(sessionHandle, body))
            }

            webSocket("/bridge") {
                val connectionId = DaemonBridgeRegistry.register(this)
                daemonLogger.info("Daemon bridge connected connectionId={}", connectionId)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            DaemonBridgeRegistry.handleFrame(connectionId, frame.readText())
                        }
                    }
                } finally {
                    DaemonBridgeRegistry.unregister(connectionId)
                    daemonLogger.info("Daemon bridge disconnected connectionId={}", connectionId)
                }
            }
        }
    }

    daemonLogger.info(
        "Starting Maestro daemon on 127.0.0.1:{} workingDir={}",
        port,
        WorkingDirectory.baseDir.absolutePath,
    )
    server.start(wait = true)
}

private fun Application.installStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            daemonLogger.error(
                "Daemon request failed method={} uri={}",
                call.request.httpMethod.value,
                call.request.uri,
                cause,
            )
            call.respondJson(
                buildJsonObject {
                    put("ok", JsonPrimitive(false))
                    put("error", JsonPrimitive(cause.message ?: cause::class.simpleName ?: "unknown error"))
                    put("path", JsonPrimitive(call.request.uri))
                },
                status = HttpStatusCode.InternalServerError,
            )
        }
    }
}

private fun sessionListJson(): JsonObject = buildJsonObject {
    put("ok", JsonPrimitive(true))
    put(
        "sessions",
        JsonArray(McpSessionRegistry.listSessions().map(::sessionJson)),
    )
}

private fun sessionJson(handle: McpSessionRegistry.SessionHandle): JsonObject = buildJsonObject {
    put("ok", JsonPrimitive(true))
    put("session_id", JsonPrimitive(handle.sessionId))
    put("device_id", JsonPrimitive(handle.deviceId))
    handle.appId?.let { put("app_id", JsonPrimitive(it)) }
    handle.projectRoot?.let { put("project_root", JsonPrimitive(it)) }
    handle.owner?.let { put("owner", JsonPrimitive(it)) }
    handle.label?.let { put("label", JsonPrimitive(it)) }
    put("platform", JsonPrimitive(handle.platform))
    handle.driverHostPort?.let { put("driver_host_port", JsonPrimitive(it)) }
    put("created_at", JsonPrimitive(handle.createdAt))
    put("last_used_at", JsonPrimitive(handle.lastUsedAt))
    put("ttl_ms", JsonPrimitive(handle.ttlMs))
    put("reused", JsonPrimitive(handle.reused))
    put("bridge_connected", JsonPrimitive(DaemonBridgeRegistry.isConnected(handle.appId)))
}

private fun requiredSessionHandle(body: JsonObject): McpSessionRegistry.SessionHandle {
    val sessionId = body.stringValue("session_id")
        ?: error("session_id is required")
    return McpSessionRegistry.sessionHandle(sessionId)
        ?: error("Unknown or expired session_id: $sessionId")
}

private fun buildSnapshotRequest(body: JsonObject): AutomationSnapshotRequest {
    val fields = body.stringSetValue("fields") ?: DEFAULT_AUTOMATION_FIELDS
    val mode = when (body.stringValue("mode")?.lowercase()) {
        "full" -> AutomationSnapshotMode.FULL
        else -> AutomationSnapshotMode.MINIMAL
    }
    return AutomationSnapshotRequest(
        mode = mode,
        flat = body.booleanValue("flat") ?: true,
        interactiveOnly = body.booleanValue("interactive_only", "interactiveOnly") ?: false,
        fields = fields,
        maxDepth = body.intValue("max_depth", "maxDepth"),
        includeStatusBars = body.booleanValue("include_status_bars", "includeStatusBars") ?: false,
        includeSafariWebViews = body.booleanValue("include_safari_web_views", "includeSafariWebViews") ?: false,
        excludeKeyboardElements = body.booleanValue("exclude_keyboard_elements", "excludeKeyboardElements") ?: false,
        sinceToken = body.stringValue("since_token", "sinceToken"),
    )
}

private fun buildQueryRequest(body: JsonObject): AutomationQueryRequest? {
    val selectors = buildAutomationSelectors(body)
    if (selectors.isEmpty()) {
        return null
    }
    return AutomationQueryRequest(
        selectors = selectors,
        interactiveOnly = body.booleanValue("interactive_only", "interactiveOnly") ?: false,
        fields = body.stringSetValue("fields") ?: DEFAULT_AUTOMATION_FIELDS,
        maxDepth = body.intValue("max_depth", "maxDepth"),
        includeStatusBars = body.booleanValue("include_status_bars", "includeStatusBars") ?: false,
        includeSafariWebViews = body.booleanValue("include_safari_web_views", "includeSafariWebViews") ?: false,
        excludeKeyboardElements = body.booleanValue("exclude_keyboard_elements", "excludeKeyboardElements") ?: false,
    )
}

private fun buildWaitRequest(event: JsonObject): AutomationWaitRequest? {
    val selectors = when (event.stringValue("kind")?.lowercase()) {
        "selector_visible", "selector_not_visible" -> buildAutomationSelectors(event)
        else -> emptyList()
    }
    if (selectors.isEmpty()) {
        return null
    }
    return AutomationWaitRequest(
        selectors = selectors,
        notVisible = event.stringValue("kind")?.lowercase() == "selector_not_visible",
        timeoutMs = event.longValue("timeout_ms") ?: 5_000L,
        pollIntervalMs = event.longValue("poll_interval_ms") ?: 100L,
        interactiveOnly = event.booleanValue("interactive_only", "interactiveOnly") ?: false,
        fields = event.stringSetValue("fields") ?: DEFAULT_AUTOMATION_FIELDS,
        maxDepth = event.intValue("max_depth", "maxDepth"),
        includeStatusBars = event.booleanValue("include_status_bars", "includeStatusBars") ?: false,
        includeSafariWebViews = event.booleanValue("include_safari_web_views", "includeSafariWebViews") ?: false,
        excludeKeyboardElements = event.booleanValue("exclude_keyboard_elements", "excludeKeyboardElements") ?: false,
    )
}

private fun executeBatch(
    sessionHandle: McpSessionRegistry.SessionHandle,
    actions: JsonArray,
    stopOnError: Boolean,
): JsonObject {
    val results = mutableListOf<JsonObject>()
    val elapsedMs = measureTimeMillis {
        McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
            val orchestra = Orchestra(session.maestro)
            var jsInitialized = false
            var success = true
            var stoppedEarly = false

            actions.forEachIndexed { index, actionElement ->
                if (stoppedEarly) {
                    results += batchResultJson(
                        index = index,
                        type = "skipped",
                        ok = false,
                        optional = false,
                        skipped = true,
                        summary = "Skipped because a previous action failed and stop_on_error=true",
                    )
                    return@forEachIndexed
                }

                val action = actionElement.jsonObject
                val type = normalizeBatchActionType(action.stringValue("type") ?: error("actions[$index].type is required"))
                val optional = action.booleanValue("optional") ?: false
                val timeoutMs = action.longValue("timeout_ms", "timeoutMs")

                try {
                    when (type) {
                        "tap_on" -> {
                            val selector = buildElementSelector(action)
                                ?: error("actions[$index].selector.id or selector.text is required")
                            runBatchCommands(orchestra, jsInitialized, listOf(
                                MaestroCommand(
                                    command = TapOnElementCommand(
                                        selector = selector,
                                        retryIfNoChange = false,
                                        waitUntilVisible = false,
                                        optional = optional,
                                    ),
                                ),
                            ))
                            jsInitialized = true
                            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "tap_on completed")
                        }

                        "tap_first_visible_now" -> {
                            val selectors = buildElementSelectors(action)
                            if (selectors.isEmpty()) {
                                error("actions[$index].selectors or selector.id/selector.text is required")
                            }
                            runBatchCommands(orchestra, jsInitialized, listOf(
                                MaestroCommand(
                                    command = TapFirstVisibleNowCommand(
                                        selectors = selectors,
                                        waitToSettleTimeoutMs = action.intValue("wait_to_settle_timeout_ms", "waitToSettleTimeoutMs"),
                                        optional = optional,
                                    ),
                                ),
                            ))
                            jsInitialized = true
                            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "tap_first_visible_now completed")
                        }

                        "input_text" -> {
                            val text = action.stringValue("text") ?: error("actions[$index].text is required")
                            val commands = mutableListOf<MaestroCommand>()
                            buildElementSelector(action)?.let { selector ->
                                commands += MaestroCommand(
                                    command = TapOnElementCommand(
                                        selector = selector,
                                        retryIfNoChange = false,
                                        waitUntilVisible = false,
                                        optional = optional,
                                    ),
                                )
                            }
                            commands += MaestroCommand(command = InputTextCommand(text = text, optional = optional))
                            runBatchCommands(orchestra, jsInitialized, commands)
                            jsInitialized = true
                            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "input_text completed")
                        }

                        "press_key" -> {
                            val key = action.stringValue("key") ?: error("actions[$index].key is required")
                            val keyCode = parseKeyCode(key) ?: error("Unsupported key code: $key")
                            runBatchCommands(orchestra, jsInitialized, listOf(MaestroCommand(command = PressKeyCommand(keyCode, optional = optional))))
                            jsInitialized = true
                            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "press_key completed")
                        }

                        "assert_visible_now" -> {
                            val selector = buildElementSelector(action)
                                ?: error("actions[$index].selector.id or selector.text is required")
                            runBatchCommands(orchestra, jsInitialized, listOf(
                                MaestroCommand(command = maestro.orchestra.AssertConditionCommand(
                                    condition = Condition(visibleNow = selector),
                                    timeout = "0",
                                    optional = optional,
                                )),
                            ))
                            jsInitialized = true
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "assert_visible_now completed")
                        }

                        "assert_not_visible_now" -> {
                            val selector = buildElementSelector(action)
                                ?: error("actions[$index].selector.id or selector.text is required")
                            runBatchCommands(orchestra, jsInitialized, listOf(
                                MaestroCommand(command = maestro.orchestra.AssertConditionCommand(
                                    condition = Condition(notVisibleNow = selector),
                                    timeout = "0",
                                    optional = optional,
                                )),
                            ))
                            jsInitialized = true
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "assert_not_visible_now completed")
                        }

                        "dismiss_known_overlays" -> {
                            runBatchCommands(orchestra, jsInitialized, listOf(
                                MaestroCommand(command = DismissKnownOverlaysCommand(
                                    maxPasses = (action.intValue("max_passes", "maxPasses") ?: 2).coerceIn(1, 5),
                                    optional = optional,
                                )),
                            ))
                            jsInitialized = true
                            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "dismiss_known_overlays completed")
                        }

                        "query_elements" -> {
                            val queryRequest = buildQueryRequest(action)
                                ?: error("actions[$index].selector.id or selector.text is required")
                            val query = session.maestro.driver.queryAutomationElements(queryRequest)
                            results += batchResultJson(
                                index = index,
                                type = type,
                                ok = true,
                                optional = optional,
                                summary = "query_elements completed",
                                payload = ToolSupport.automationQueryJson(
                                    deviceId = sessionHandle.deviceId,
                                    sessionId = sessionHandle.sessionId,
                                    result = query,
                                ),
                            )
                        }

                        "snapshot" -> {
                            val snapshot = session.maestro.driver.automationSnapshot(buildSnapshotRequest(action))
                            results += batchResultJson(
                                index = index,
                                type = type,
                                ok = true,
                                optional = optional,
                                summary = "snapshot completed",
                                payload = ToolSupport.automationSnapshotJson(
                                    deviceId = sessionHandle.deviceId,
                                    sessionId = sessionHandle.sessionId,
                                    snapshot = snapshot,
                                ),
                            )
                        }

                        "open_link" -> {
                            val link = action.stringValue("link") ?: error("actions[$index].link is required")
                            runBatchCommands(orchestra, jsInitialized, listOf(
                                MaestroCommand(command = OpenLinkCommand(
                                    link = link,
                                    autoVerify = action.booleanValue("auto_verify", "autoVerify"),
                                    browser = action.booleanValue("browser"),
                                    optional = optional,
                                )),
                            ))
                            jsInitialized = true
                            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "open_link completed")
                        }

                        "wait_for_idle", "wait_for_animation_to_end" -> {
                            runBatchCommands(orchestra, jsInitialized, listOf(
                                MaestroCommand(command = WaitForAnimationToEndCommand(timeout = timeoutMs, optional = optional)),
                            ))
                            jsInitialized = true
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "$type completed")
                        }

                        "hide_keyboard" -> {
                            runBatchCommands(orchestra, jsInitialized, listOf(MaestroCommand(command = HideKeyboardCommand(optional = optional))))
                            jsInitialized = true
                            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "hide_keyboard completed")
                        }

                        "scroll" -> {
                            runBatchCommands(orchestra, jsInitialized, listOf(MaestroCommand(command = maestro.orchestra.ScrollCommand(optional = optional))))
                            jsInitialized = true
                            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
                            results += batchResultJson(index, type, ok = true, optional = optional, summary = "scroll completed")
                        }

                        else -> error("Unsupported action type: $type")
                    }
                } catch (error: Throwable) {
                    success = false
                    results += batchResultJson(
                        index = index,
                        type = type,
                        ok = optional,
                        optional = optional,
                        summary = if (optional) "$type failed but was optional" else "$type failed",
                        error = error.message ?: error.toString(),
                    )
                    if (stopOnError && !optional) {
                        stoppedEarly = true
                    }
                }
            }
        }
    }

    return buildJsonObject {
        put("ok", JsonPrimitive(results.none { it["ok"]?.jsonPrimitive?.booleanOrNull == false && it["optional"]?.jsonPrimitive?.booleanOrNull != true }))
        put("session_id", JsonPrimitive(sessionHandle.sessionId))
        put("device_id", JsonPrimitive(sessionHandle.deviceId))
        put("elapsed_ms", JsonPrimitive(elapsedMs))
        put("results", JsonArray(results))
    }
}

private fun runCompiledFlow(
    sessionHandle: McpSessionRegistry.SessionHandle,
    body: JsonObject,
): JsonObject {
    val flowPathRaw = body.stringValue("flow_path") ?: error("flow_path is required")
    val env = body.objectValue("env")
    val debugOutputDir = body.stringValue("debug_output_dir")
    val testOutputDir = body.stringValue("test_output_dir")
    val flowPath = resolveFlowPath(sessionHandle.projectRoot, flowPathRaw)
    val flowFile = flowPath.toFile()
    if (!flowFile.exists()) {
        error("Flow file not found: ${flowFile.absolutePath}")
    }

    initializeRunMetadata(sessionHandle.owner, sessionHandle.label)
    installDebugOutputs(debugOutputDir, testOutputDir)

    val compiled = CompiledFlowCache.compileFlowFile(
        flowPath = flowPath,
        env = env,
        projectIndexVersion = projectIndexVersion(sessionHandle.projectRoot),
    )
    val finalEnv = env
        .withInjectedShellEnvVars()
        .withDefaultEnvVars(flowFile, sessionHandle.deviceId)

    val commands = compiled.commands.withEnv(finalEnv)
    val flowName = flowFile.nameWithoutExtension
    var flowSucceeded = false
    var flowError: Throwable? = null
    val elapsedMs = measureTimeMillis {
        try {
            McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
                val orchestra = daemonOrchestra(session.maestro, flowName, flowFile)
                flowSucceeded = runBlocking {
                    orchestra.runFlow(commands)
                }
            }
        } catch (error: Throwable) {
            flowError = error
            flowSucceeded = false
            daemonLogger.warn(
                "Compiled flow failed sessionId={} flowPath={}",
                sessionHandle.sessionId,
                flowFile.absolutePath,
                error,
            )
        }
    }
    LiveTraceLogger.flowCompleted(
        flowName,
        flowSucceeded,
        buildString {
            append("compiledFlowMs=")
            append(elapsedMs)
            flowError?.message?.let {
                append(" error=")
                append(it)
            }
        },
    )

    return buildJsonObject {
        put("ok", JsonPrimitive(flowSucceeded))
        put("success", JsonPrimitive(flowSucceeded))
        put("status", JsonPrimitive(if (flowSucceeded) "passed" else "failed"))
        put("exitCode", JsonPrimitive(if (flowSucceeded) 0 else 1))
        put("session_id", JsonPrimitive(sessionHandle.sessionId))
        put("device_id", JsonPrimitive(sessionHandle.deviceId))
        put("flow_path", JsonPrimitive(flowFile.absolutePath))
        put("commands_executed", JsonPrimitive(compiled.commands.size))
        put("compiled_flow_ms", JsonPrimitive(elapsedMs))
        put("flow_hash", JsonPrimitive(compiled.flowHash))
        put("env_hash", JsonPrimitive(compiled.envHash))
        put("project_index_version", JsonPrimitive(compiled.projectIndexVersion))
        put("compiled_at_ms", JsonPrimitive(compiled.compiledAtMs))
        TestDebugReporter.getLiveTracePath()?.let { put("live_trace_path", JsonPrimitive(it.toAbsolutePath().toString())) }
        TestDebugReporter.getLiveStatusPath()?.let { put("live_status_path", JsonPrimitive(it.toAbsolutePath().toString())) }
        put(
            "env",
            buildJsonObject {
                finalEnv.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            },
        )
        flowError?.let { put("error", JsonPrimitive(it.message ?: it::class.simpleName ?: "unknown error")) }
    }
}

private fun runLegacyDaemonRun(body: JsonObject): JsonObject {
    val runMetadata = body["runMetadata"]?.jsonObject ?: buildJsonObject { }
    val deviceId = runMetadata.stringValue("device", "device_id", "deviceId")
        ?: body.stringValue("device_id")
        ?: error("runMetadata.device is required")
    val owner = runMetadata.stringValue("runOwner", "owner")
    val label = runMetadata.stringValue("runLabel", "label")
    val debugOutputDir = runMetadata.stringValue("debugOutputDir", "debug_output_dir")
    val testOutputDir = runMetadata.stringValue("testOutputDir", "test_output_dir")
    val projectRoot = runMetadata.stringValue("projectRoot", "project_root")
        ?: body.stringValue("project_root")
    val appId = runMetadata.stringValue("appId", "app_id")
        ?: body.stringValue("app_id")
    val handle = McpSessionRegistry.openSession(
        sessionManager = MaestroSessionManager,
        deviceId = deviceId,
        appId = appId,
        projectRoot = projectRoot,
        owner = owner,
        label = label,
        driverHostPort = body.intValue("driver_host_port")
            ?: runMetadata.intValue("driverHostPort", "driver_host_port"),
        ttlMsOverride = body.longValue("ttl_ms"),
    )
    val result = runCompiledFlow(
        sessionHandle = handle,
        body = buildJsonObject {
            put("session_id", JsonPrimitive(handle.sessionId))
            put("flow_path", JsonPrimitive(body.stringValue("flow_path", "flowFile") ?: error("flowFile is required")))
            debugOutputDir?.let { put("debug_output_dir", JsonPrimitive(it)) }
            testOutputDir?.let { put("test_output_dir", JsonPrimitive(it)) }
            body["env"]?.let { put("env", it) }
        },
    )

    val success = result["ok"]?.jsonPrimitive?.booleanOrNull == true
    return buildJsonObject {
        result.forEach { (key, value) -> put(key, value) }
        put("success", JsonPrimitive(success))
        put("status", JsonPrimitive(if (success) "passed" else "failed"))
        put("exitCode", JsonPrimitive(if (success) 0 else 1))
    }
}

private suspend fun runMacro(
    sessionHandle: McpSessionRegistry.SessionHandle,
    body: JsonObject,
): JsonObject {
    val macro = normalizeBatchActionType(body.stringValue("macro") ?: error("macro is required"))
    val args = body["args"]?.jsonObject ?: buildJsonObject { }
    return when (macro) {
        "dismiss_known_overlays" -> {
            McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
                val orchestra = Orchestra(session.maestro)
                runBlocking {
                    orchestra.executeCommands(
                        listOf(
                            MaestroCommand(
                                command = DismissKnownOverlaysCommand(
                                    maxPasses = (args.intValue("max_passes", "maxPasses") ?: 2).coerceIn(1, 5),
                                    optional = args.booleanValue("optional") ?: false,
                                ),
                            ),
                        ),
                    )
                }
            }
            McpSessionRegistry.invalidateHierarchy(sessionHandle.sessionId)
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("macro", JsonPrimitive(macro))
                put("summary", JsonPrimitive("Dismissed known overlays."))
            }
        }

        "open_chats" -> {
            val commandId = args.stringValue("command_id") ?: "open-chats-${System.currentTimeMillis()}"
            val result = if (!sessionHandle.appId.isNullOrBlank() && DaemonBridgeRegistry.isConnected(sessionHandle.appId)) {
                DaemonBridgeRegistry.sendCommand(
                    appId = sessionHandle.appId,
                    kind = "openRoute",
                    args = mapOf("route" to "chats", "commandId" to commandId),
                )
            } else {
                openDeepLink(
                    sessionHandle,
                    "thrivify://automation/openRoute?route=chats&commandId=$commandId",
                )
                awaitRouteMarker(sessionHandle, "automation-bridge-route-chats")
                DaemonBridgeRegistry.CommandResult(id = commandId, ok = true, route = "Chats")
            }
            buildJsonObject {
                put("ok", JsonPrimitive(result.ok))
                put("macro", JsonPrimitive(macro))
                put("command_id", JsonPrimitive(commandId))
                result.route?.let { put("route", JsonPrimitive(it)) }
                result.error?.let { put("error", JsonPrimitive(it)) }
                put("summary", JsonPrimitive(if (result.ok) "Opened Chats route." else "Failed to open Chats route."))
            }
        }

        "open_conversation" -> {
            val conversationId = args.stringValue("conversation_id", "conversationId")
                ?: error("run_macro.args.conversationId is required")
            val commandId = args.stringValue("command_id", "commandId")
                ?: "open-conversation-${System.currentTimeMillis()}"
            val result = if (!sessionHandle.appId.isNullOrBlank() && DaemonBridgeRegistry.isConnected(sessionHandle.appId)) {
                DaemonBridgeRegistry.sendCommand(
                    appId = sessionHandle.appId,
                    kind = "openConversation",
                    args = mapOf(
                        "conversationId" to conversationId,
                        "commandId" to commandId,
                        "waitForNavigationMs" to (args.intValue("wait_for_navigation_ms", "waitForNavigationMs") ?: 15_000),
                    ),
                    timeoutMs = (args.longValue("timeout_ms", "timeoutMs") ?: 20_000L),
                )
            } else {
                openDeepLink(
                    sessionHandle,
                    "thrivify://automation/openConversation?conversationId=${urlEncode(conversationId)}&commandId=$commandId",
                )
                awaitDaemonEvent(
                    sessionHandle = sessionHandle,
                    event = buildJsonObject {
                        put("kind", JsonPrimitive("conversation_ready"))
                        put("conversation_id", JsonPrimitive(conversationId))
                    },
                    timeoutMs = args.longValue("timeout_ms", "timeoutMs") ?: 20_000L,
                    pollIntervalMs = 100L,
                )
                DaemonBridgeRegistry.CommandResult(id = commandId, ok = true, route = "ChatView")
            }
            buildJsonObject {
                put("ok", JsonPrimitive(result.ok))
                put("macro", JsonPrimitive(macro))
                put("command_id", JsonPrimitive(commandId))
                put("conversation_id", JsonPrimitive(conversationId))
                result.route?.let { put("route", JsonPrimitive(it)) }
                result.error?.let { put("error", JsonPrimitive(it)) }
                put("summary", JsonPrimitive(if (result.ok) "Opened conversation." else "Failed to open conversation."))
            }
        }

        "ensure_logged_in_fast" -> {
            val customToken = args.stringValue("custom_token", "customToken")
                ?: error("run_macro.args.customToken is required")
            val route = args.stringValue("route") ?: "chats"
            val commandId = args.stringValue("command_id", "commandId")
                ?: "ensure-logged-in-fast-${System.currentTimeMillis()}"
            val result = if (!sessionHandle.appId.isNullOrBlank() && DaemonBridgeRegistry.isConnected(sessionHandle.appId)) {
                DaemonBridgeRegistry.sendCommand(
                    appId = sessionHandle.appId,
                    kind = "bootstrapAuth",
                    args = mapOf(
                        "customToken" to customToken,
                        "route" to route,
                        "commandId" to commandId,
                        "waitForNavigationMs" to (args.intValue("wait_for_navigation_ms", "waitForNavigationMs") ?: 15_000),
                    ),
                    timeoutMs = (args.longValue("timeout_ms", "timeoutMs") ?: 20_000L),
                )
            } else {
                val encodedToken = urlEncode(customToken)
                openDeepLink(
                    sessionHandle,
                    "thrivify://automation/bootstrap?customToken=$encodedToken&route=$route&commandId=$commandId",
                )
                awaitRouteMarker(sessionHandle, "automation-bridge-auth-ready")
                awaitRouteMarker(sessionHandle, "automation-bridge-route-${route.lowercase()}")
                DaemonBridgeRegistry.CommandResult(id = commandId, ok = true, route = route)
            }
            buildJsonObject {
                put("ok", JsonPrimitive(result.ok))
                put("macro", JsonPrimitive(macro))
                put("command_id", JsonPrimitive(commandId))
                put("route", JsonPrimitive(route))
                result.error?.let { put("error", JsonPrimitive(it)) }
                put("summary", JsonPrimitive(if (result.ok) "Bootstrapped authenticated session." else "Failed to bootstrap authenticated session."))
            }
        }

        else -> {
            val fallbackFlowPath = args.stringValue("flow_path", "flowPath")
                ?: error("Unknown macro: $macro")
            runCompiledFlow(
                sessionHandle,
                buildJsonObject {
                    put("session_id", JsonPrimitive(sessionHandle.sessionId))
                    put("flow_path", JsonPrimitive(fallbackFlowPath))
                    args["env"]?.let { put("env", it) }
                },
            )
        }
    }
}

private fun daemonOrchestra(
    maestro: maestro.Maestro,
    flowName: String,
    flowFile: File,
): Orchestra {
    fun commandLabel(command: MaestroCommand): String =
        command.asCommand()?.let { it::class.simpleName } ?: command.toString()

    return Orchestra(
        maestro = maestro,
        onFlowStart = { LiveTraceLogger.flowStarted(flowName, flowFile.absolutePath) },
        onCommandStart = { index, command -> LiveTraceLogger.commandStarted(flowName, commandLabel(command), index) },
        onCommandComplete = { _, command -> LiveTraceLogger.commandFinished(flowName, commandLabel(command), "COMPLETED") },
        onCommandWarned = { _, command -> LiveTraceLogger.commandFinished(flowName, commandLabel(command), "WARNED") },
        onCommandSkipped = { _, command -> LiveTraceLogger.commandFinished(flowName, commandLabel(command), "SKIPPED") },
        onCommandFailed = { _, command, error ->
            LiveTraceLogger.commandFinished(
                flowName,
                commandLabel(command),
                "FAILED",
                detail = error.message ?: error::class.simpleName ?: "unknown error",
            )
            throw error
        },
    )
}

private suspend fun awaitDaemonEvent(
    sessionHandle: McpSessionRegistry.SessionHandle,
    event: JsonObject,
    timeoutMs: Long,
    pollIntervalMs: Long,
): JsonObject {
    val startedAt = System.currentTimeMillis()
    val kind = event.stringValue("kind")?.lowercase() ?: error("event.kind is required")
    val appId = event.stringValue("app_id", "appId") ?: sessionHandle.appId

    val waitRequest = buildWaitRequest(
        buildJsonObject {
            event.forEach { (key, value) -> put(key, value) }
            put("timeout_ms", JsonPrimitive(timeoutMs))
            put("poll_interval_ms", JsonPrimitive(pollIntervalMs))
        },
    )

    if (waitRequest != null) {
        val result = McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
            session.maestro.driver.awaitAutomation(waitRequest)
        }
        return ToolSupport.automationWaitJson(
            deviceId = sessionHandle.deviceId,
            sessionId = sessionHandle.sessionId,
            result = result,
        )
    }

    var polls = 0
    while (System.currentTimeMillis() - startedAt <= timeoutMs) {
        polls += 1
        val state = DaemonBridgeRegistry.semanticState(appId)
        val matched = when (kind) {
            "bridge_ready" -> state != null
            "bridge_auth_ready" -> state?.userId?.isNotBlank() == true || state?.firebaseUid?.isNotBlank() == true
            "bridge_route" -> state?.route.equals(event.stringValue("route"), ignoreCase = true)
            "semantic_key_equals" -> semanticStateValue(state, event.stringValue("key")).equals(event.stringValue("value"))
            "conversation_ready" -> {
                val targetConversationId = event.stringValue("conversation_id", "conversationId")
                state?.visibleConversationId?.isNotBlank() == true &&
                    (targetConversationId == null || state.visibleConversationId == targetConversationId)
            }
            "voice_ready" -> state?.voiceUiReady == true
            "bridge_command_completed" -> {
                val expectedId = event.stringValue("command_id", "commandId")
                val expectedStatus = event.stringValue("status") ?: "succeeded"
                val expectedCommandName = event.stringValue("command_name", "commandName")
                val expectedRoute = event.stringValue("route")
                state != null &&
                    (expectedId == null || state.lastCommandId == expectedId) &&
                    state.lastCommandStatus.equals(expectedStatus, ignoreCase = true) &&
                    (expectedCommandName == null || state.lastCommand.equals(expectedCommandName, ignoreCase = true)) &&
                    (expectedRoute == null || state.route.equals(expectedRoute, ignoreCase = true))
            }
            else -> false
        }
        if (matched) {
            return buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("matched", JsonPrimitive(true))
                put("kind", JsonPrimitive(kind))
                put("polls", JsonPrimitive(polls))
                put("elapsed_ms", JsonPrimitive(System.currentTimeMillis() - startedAt))
                put("source", JsonPrimitive("semantic_bridge"))
                state?.let { put("details", semanticStateJson(it)) }
            }
        }
        delay(pollIntervalMs.coerceAtLeast(25L))
    }

    val finalState = DaemonBridgeRegistry.semanticState(appId)
    return buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("matched", JsonPrimitive(false))
        put("kind", JsonPrimitive(kind))
        put("polls", JsonPrimitive(polls))
        put("elapsed_ms", JsonPrimitive(System.currentTimeMillis() - startedAt))
        put("source", JsonPrimitive("semantic_bridge"))
        finalState?.let { put("details", semanticStateJson(it)) }
    }
}

private fun semanticStateValue(state: DaemonBridgeRegistry.SemanticState?, key: String?): String? {
    if (state == null || key == null) {
        return null
    }
    return when (key) {
        "route" -> state.route
        "user_id", "userId" -> state.userId
        "firebase_uid", "firebaseUid" -> state.firebaseUid
        "visible_conversation_id", "visibleConversationId" -> state.visibleConversationId
        "prompt_generation_state", "promptGenerationState" -> state.promptGenerationState
        "primary_action", "primaryAction" -> state.primaryAction
        "last_command", "lastCommand" -> state.lastCommand
        "last_command_id", "lastCommandId" -> state.lastCommandId
        "last_command_status", "lastCommandStatus" -> state.lastCommandStatus
        else -> null
    }
}

private fun semanticStateJson(state: DaemonBridgeRegistry.SemanticState): JsonObject = buildJsonObject {
    state.appId?.let { put("app_id", JsonPrimitive(it)) }
    state.route?.let { put("route", JsonPrimitive(it)) }
    state.userId?.let { put("user_id", JsonPrimitive(it)) }
    state.firebaseUid?.let { put("firebase_uid", JsonPrimitive(it)) }
    put("metro_connected", JsonPrimitive(state.metroConnected))
    put("debug_ui_visible", JsonPrimitive(state.debugUiVisible))
    put("conversation_list_ready", JsonPrimitive(state.conversationListReady))
    put("discover_ready", JsonPrimitive(state.discoverReady))
    put("voice_ui_ready", JsonPrimitive(state.voiceUiReady))
    state.promptGenerationState?.let { put("prompt_generation_state", JsonPrimitive(it)) }
    state.visibleConversationId?.let { put("visible_conversation_id", JsonPrimitive(it)) }
    state.primaryAction?.let { put("primary_action", JsonPrimitive(it)) }
    state.lastCommand?.let { put("last_command", JsonPrimitive(it)) }
    state.lastCommandId?.let { put("last_command_id", JsonPrimitive(it)) }
    state.lastCommandStatus?.let { put("last_command_status", JsonPrimitive(it)) }
    put("state_version", JsonPrimitive(state.stateVersion))
    put("updated_at_ms", JsonPrimitive(state.updatedAtMs))
    put(
        "conversation_ids",
        buildJsonArray { state.conversationIds.forEach { add(JsonPrimitive(it)) } },
    )
    put(
        "known_overlays",
        buildJsonArray { state.knownOverlays.forEach { add(JsonPrimitive(it)) } },
    )
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

private fun runBatchCommands(
    orchestra: Orchestra,
    jsInitialized: Boolean,
    commands: List<MaestroCommand>,
) {
    runBlocking {
        orchestra.executeCommands(
            commands = commands,
            shouldReinitJsEngine = !jsInitialized,
        )
    }
}

private fun openDeepLink(sessionHandle: McpSessionRegistry.SessionHandle, link: String) {
    McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
        session.maestro.openLink(link, sessionHandle.appId, autoVerify = false, browser = false)
    }
}

private suspend fun awaitRouteMarker(sessionHandle: McpSessionRegistry.SessionHandle, markerId: String) {
    val result = McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
        session.maestro.driver.awaitAutomation(
            AutomationWaitRequest(
                selectors = listOf(AutomationSelector(id = markerId, useFuzzyMatching = false)),
                timeoutMs = 5_000L,
                pollIntervalMs = 100L,
            ),
        )
    }
    if (!result.satisfied) {
        error("Timed out waiting for marker: $markerId")
    }
}

private fun installDebugOutputs(debugOutputDir: String?, testOutputDir: String?) {
    val debugDirPath = debugOutputDir?.let { Paths.get(it).toAbsolutePath().normalize() }
    val testDirPath = testOutputDir?.let { Paths.get(it).toAbsolutePath().normalize() }
    debugDirPath?.let { Files.createDirectories(it) }
    testDirPath?.let { Files.createDirectories(it) }
    TestDebugReporter.updateTestOutputDir(testDirPath)
    TestDebugReporter.install(
        debugOutputPathAsString = debugDirPath?.toString(),
        flattenDebugOutput = true,
        printToConsole = false,
    )
}

private fun initializeRunMetadata(owner: String?, label: String?) {
    MaestroRunMetadata.initialize(
        command = "maestro-daemon",
        runOwner = owner,
        runLabel = label,
    )
}

private fun resolveProjectRoot(body: JsonObject): String? =
    body.stringValue("project_root")?.let { resolvePath(null, it).toAbsolutePath().normalize().toString() }

private fun resolveFlowPath(projectRoot: String?, flowPath: String): Path =
    resolvePath(projectRoot, flowPath)

private fun resolvePath(projectRoot: String?, rawPath: String): Path {
    val path = Paths.get(rawPath)
    return if (path.isAbsolute) {
        path.normalize()
    } else if (!projectRoot.isNullOrBlank()) {
        Paths.get(projectRoot).resolve(path).normalize()
    } else {
        WorkingDirectory.baseDir.toPath().resolve(path).normalize()
    }
}

private fun projectIndexVersion(projectRoot: String?): String {
    if (projectRoot.isNullOrBlank()) {
        return "working-dir:${WorkingDirectory.baseDir.absolutePath}"
    }
    return "project:${resolvePath(null, projectRoot).toAbsolutePath().normalize()}"
}

private suspend fun ApplicationCall.receiveJsonObject(): JsonObject {
    val raw = receiveText().ifBlank { "{}" }
    return daemonJson.parseToJsonElement(raw).jsonObject
}

private suspend fun ApplicationCall.respondJson(
    payload: JsonObject,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(payload.toString(), ContentType.Application.Json, status)
}

private fun normalizeBatchActionType(raw: String): String =
    raw.trim().replace("-", "_").lowercase()

private fun selectorRegex(value: String, useFuzzyMatching: Boolean): String =
    if (useFuzzyMatching) ".*${Regex.escape(value)}.*" else value

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
            this[key]?.jsonArray
                ?.mapNotNull { item -> item.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
        }
        .firstOrNull()

private fun JsonObject.selectorObject(): JsonObject? =
    this["selector"]?.jsonObject ?: this

private fun JsonObject.objectValue(key: String): Map<String, String> =
    this[key]?.jsonObject?.entries?.associate { (childKey, childValue) ->
        childKey to childValue.jsonPrimitive.content
    } ?: emptyMap()

private fun buildElementSelector(action: JsonObject): ElementSelector? {
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

private fun buildElementSelectors(action: JsonObject): List<ElementSelector> {
    val selectors = action["selectors"]?.jsonArray
        ?.mapNotNull { item -> buildElementSelector(item.jsonObject) }
        ?.takeIf { it.isNotEmpty() }
    return selectors ?: buildElementSelector(action)?.let(::listOf).orEmpty()
}

private fun buildAutomationSelector(action: JsonObject): AutomationSelector? {
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

private fun buildAutomationSelectors(action: JsonObject): List<AutomationSelector> {
    val selectors = action["selectors"]?.jsonArray
        ?.mapNotNull { item -> buildAutomationSelector(item.jsonObject) }
        ?.takeIf { it.isNotEmpty() }
    return selectors ?: buildAutomationSelector(action)?.let(::listOf).orEmpty()
}

private fun parseKeyCode(value: String): KeyCode? =
    runCatching { KeyCode.valueOf(value.trim().uppercase()) }.getOrNull()

private fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)
