package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import maestro.AutomationSnapshotMode
import maestro.AutomationSnapshotRequest
import maestro.DEFAULT_AUTOMATION_FIELDS
import maestro.cli.daemon.DaemonProjectCatalog
import maestro.cli.mcp.McpSessionRegistry
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.WorkingDirectory
import maestro.debuglog.LiveTraceLogger
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.Platform
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.utils.MaestroRunMetadata
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.measureTimeMillis

internal object ProjectToolSupport {

    data class SelectorSuggestion(
        val strategy: String,
        val selectorValue: String,
        val confidence: Double,
        val rationale: String,
        val bounds: String?,
    )

    fun optionalString(request: CallToolRequest, vararg names: String): String? =
        names.asSequence()
            .mapNotNull { name -> request.arguments[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
            .firstOrNull()

    fun optionalBoolean(request: CallToolRequest, vararg names: String): Boolean? =
        names.asSequence()
            .mapNotNull { name -> request.arguments[name]?.jsonPrimitive?.booleanOrNull }
            .firstOrNull()

    fun optionalInt(request: CallToolRequest, vararg names: String): Int? =
        names.asSequence()
            .mapNotNull { name -> request.arguments[name]?.jsonPrimitive?.intOrNull }
            .firstOrNull()

    fun optionalLong(request: CallToolRequest, vararg names: String): Long? =
        names.asSequence()
            .mapNotNull { name -> request.arguments[name]?.jsonPrimitive?.longOrNull }
            .firstOrNull()

    fun resolveProjectRoot(request: CallToolRequest): String? =
        optionalString(request, "project_root", "projectRoot")
            ?.let { resolvePath(null, it).toAbsolutePath().normalize().toString() }

    fun listFlows(request: CallToolRequest): JsonObject {
        val root = resolveProjectRoot(request)?.let(Paths::get) ?: WorkingDirectory.baseDir.toPath()
        val normalized = root.toAbsolutePath().normalize()
        val flows = DaemonProjectCatalog.discoverFlows(normalized)

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("project_root", JsonPrimitive(normalized.toString()))
            put("total_flows", JsonPrimitive(flows.size))
            put(
                "flows",
                buildJsonArray {
                    flows.forEach { flow ->
                        add(
                            buildJsonObject {
                                put("path", JsonPrimitive(flow.relativePath))
                                flow.appId?.let { put("app_id", JsonPrimitive(it)) }
                                put("name", JsonPrimitive(flow.name))
                                put("command_count", JsonPrimitive(flow.commandCount))
                                put("selector_count", JsonPrimitive(flow.selectorCount))
                                put(
                                    "tags",
                                    buildJsonArray {
                                        flow.tags.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                                put(
                                    "warnings",
                                    buildJsonArray {
                                        flow.warnings.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                                put(
                                    "first_commands",
                                    buildJsonArray {
                                        flow.firstCommands.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    fun analyzeFlow(request: CallToolRequest): JsonObject {
        val root = resolveProjectRoot(request)?.let(Paths::get) ?: WorkingDirectory.baseDir.toPath()
        val flowPath = optionalString(request, "flow_path", "flowPath")
            ?: error("flow_path is required")
        val normalized = root.toAbsolutePath().normalize()
        val analysis = DaemonProjectCatalog.analyzeFlow(normalized, flowPath)

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("project_root", JsonPrimitive(normalized.toString()))
            put("flow_path", JsonPrimitive(analysis.relativePath))
            analysis.appId?.let { put("app_id", JsonPrimitive(it)) }
            put("name", JsonPrimitive(analysis.name))
            put("command_count", JsonPrimitive(analysis.commandCount))
            put(
                "selectors",
                buildJsonArray {
                    analysis.selectors.forEach { selector ->
                        add(
                            buildJsonObject {
                                put("command_index", JsonPrimitive(selector.commandIndex))
                                put("command_name", JsonPrimitive(selector.commandName))
                                put("strategy", JsonPrimitive(selector.strategy))
                                put("value", JsonPrimitive(selector.value))
                                put("location", JsonPrimitive(selector.location))
                            },
                        )
                    }
                },
            )
            put(
                "warnings",
                buildJsonArray {
                    analysis.warnings.forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "referenced_files",
                buildJsonArray {
                    analysis.referencedFiles.forEach { reference ->
                        add(
                            buildJsonObject {
                                put("command_index", JsonPrimitive(reference.commandIndex))
                                put("command_name", JsonPrimitive(reference.commandName))
                                put("key", JsonPrimitive(reference.key))
                                put("value", JsonPrimitive(reference.value))
                                put("location", JsonPrimitive(reference.location))
                                put("exists_on_disk", JsonPrimitive(reference.existsOnDisk))
                            },
                        )
                    }
                },
            )
        }
    }

    fun validateTestIds(request: CallToolRequest): JsonObject {
        val root = resolveProjectRoot(request)?.let(Paths::get) ?: WorkingDirectory.baseDir.toPath()
        val flowPath = optionalString(request, "flow_path", "flowPath")
        val requestedIds = request.arguments["test_ids"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
        val normalized = root.toAbsolutePath().normalize()
        val (dedupedRequestedIds, results) = DaemonProjectCatalog.validateTestIds(
            projectRoot = normalized,
            requestedIds = requestedIds,
            flowPath = flowPath,
        )
        val discoveredCount = DaemonProjectCatalog.scanTestIds(normalized).size

        return buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("project_root", JsonPrimitive(normalized.toString()))
            flowPath?.let { put("flow_path", JsonPrimitive(it)) }
            put("requested_count", JsonPrimitive(dedupedRequestedIds.size))
            put("discovered_count", JsonPrimitive(discoveredCount))
            put(
                "requested_ids",
                buildJsonArray {
                    dedupedRequestedIds.forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "results",
                buildJsonArray {
                    results.forEach { result ->
                        add(
                            buildJsonObject {
                                put("value", JsonPrimitive(result.value))
                                put("status", JsonPrimitive(result.status))
                                put(
                                    "matches",
                                    buildJsonArray {
                                        result.matches.forEach { match ->
                                            add(
                                                buildJsonObject {
                                                    put("value", JsonPrimitive(match.value))
                                                    put(
                                                        "files",
                                                        buildJsonArray {
                                                            match.files.forEach { add(JsonPrimitive(it)) }
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    fun resolvePath(projectRoot: String?, rawPath: String): Path {
        val path = Paths.get(rawPath)
        return if (path.isAbsolute) {
            path.normalize()
        } else if (!projectRoot.isNullOrBlank()) {
            Paths.get(projectRoot).resolve(path).normalize()
        } else {
            WorkingDirectory.baseDir.toPath().resolve(path).normalize()
        }
    }

    fun projectIndexVersion(
        projectRoot: String?,
        forceRefresh: Boolean = false,
    ): String {
        val root = if (projectRoot.isNullOrBlank()) {
            WorkingDirectory.baseDir.toPath()
        } else {
            resolvePath(null, projectRoot)
        }.toAbsolutePath().normalize()
        return DaemonProjectCatalog.projectIndexVersion(root, forceRefresh = forceRefresh)
    }

    fun <T> withDiagnosticSession(
        sessionManager: MaestroSessionManager,
        request: CallToolRequest,
        block: (McpSessionRegistry.SessionHandle) -> T,
    ): T {
        val existingSessionId = optionalString(request, "session_id")
        if (!existingSessionId.isNullOrBlank()) {
            val handle = McpSessionRegistry.resumeSession(
                sessionId = existingSessionId,
                sessionManager = sessionManager,
                ttlMsOverride = optionalLong(request, "ttl_ms", "ttlMs"),
            ) ?: error("Unknown, expired, or unhealthy session_id: $existingSessionId")
            return block(handle)
        }

        val deviceId = resolveDeviceId(request)
        val owner = optionalString(request, "owner")
        val label = optionalString(request, "label") ?: "mcp-project-api:$deviceId"
        initializeRunMetadata(owner, label)
        val handle = McpSessionRegistry.openSession(
            sessionManager = sessionManager,
            deviceId = deviceId,
            appId = optionalString(request, "app_id", "appId"),
            projectRoot = resolveProjectRoot(request),
            owner = owner,
            label = label,
            driverHostPort = optionalInt(request, "driver_host_port", "driverHostPort"),
            ttlMsOverride = optionalLong(request, "ttl_ms", "ttlMs") ?: 60_000L,
        )

        return try {
            block(handle)
        } finally {
            McpSessionRegistry.closeSession(handle.sessionId, source = "mcp_project_tool")
        }
    }

    fun resolveDeviceId(request: CallToolRequest): String {
        optionalString(request, "device_id", "deviceId")?.let { return it }

        val requestedPlatform = optionalString(request, "platform")
            ?.let { Platform.fromString(it) }
        val deviceNameContains = optionalString(request, "device_name_contains", "deviceNameContains")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.lowercase()

        fun matches(device: Device, needle: String?): Boolean {
            if (needle.isNullOrBlank()) {
                return true
            }
            val identifier = when (device) {
                is Device.Connected -> device.instanceId
                is Device.AvailableForLaunch -> device.modelId
            }
            return device.description.lowercase().contains(needle) ||
                identifier.lowercase().contains(needle)
        }

        val connected = DeviceService.listConnectedDevices()
            .filter { requestedPlatform == null || it.platform == requestedPlatform }
        connected.firstOrNull { matches(it, deviceNameContains) }?.let { return it.instanceId }
        connected.firstOrNull()?.let { return it.instanceId }

        val available = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
            .filter { requestedPlatform == null || it.platform == requestedPlatform }
        val availableMatch = available.firstOrNull { matches(it, deviceNameContains) } ?: available.firstOrNull()
        if (availableMatch != null) {
            return DeviceService.startDevice(availableMatch, null).instanceId
        }

        throw error("session_id or device_id is required, or provide a matching platform/device_name_contains")
    }

    fun suggestSelectors(
        sessionManager: MaestroSessionManager,
        request: CallToolRequest,
    ): JsonObject {
        return withDiagnosticSession(sessionManager, request) { sessionHandle ->
            val query = optionalString(request, "query") ?: error("query is required")
            val maxSuggestions = (optionalInt(request, "max_suggestions", "maxSuggestions") ?: 8).coerceIn(1, 20)
            val projectRoot = sessionHandle.projectRoot ?: resolveProjectRoot(request)
            val normalizedProjectRoot = projectRoot?.let(Paths::get)?.toAbsolutePath()?.normalize()
            val snapshot = McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
                session.maestro.driver.automationSnapshot(
                    AutomationSnapshotRequest(
                        mode = AutomationSnapshotMode.MINIMAL,
                        flat = true,
                        interactiveOnly = false,
                        fields = DEFAULT_AUTOMATION_FIELDS,
                        maxDepth = optionalInt(request, "max_depth", "maxDepth"),
                        includeStatusBars = optionalBoolean(request, "include_status_bars", "includeStatusBars") ?: false,
                        includeSafariWebViews = optionalBoolean(request, "include_safari_web_views", "includeSafariWebViews") ?: false,
                        excludeKeyboardElements = optionalBoolean(request, "exclude_keyboard_elements", "excludeKeyboardElements") ?: true,
                    ),
                )
            }

            val hierarchySuggestions = snapshot.nodes
                .flatMap { node ->
                    buildList {
                        node.id?.takeIf { it.isNotBlank() }?.let {
                            add(selectorSuggestion(query, "id", it, node.bounds, isProjectCatalog = false))
                        }
                        node.text?.takeIf { it.isNotBlank() }?.let {
                            add(selectorSuggestion(query, "text", it, node.bounds, isProjectCatalog = false))
                        }
                    }
                }
                .filter { it.confidence >= 0.3 }
                .sortedWith(compareByDescending<SelectorSuggestion> { it.confidence }.thenBy { it.selectorValue })

            val repoSuggestions = normalizedProjectRoot?.let { root ->
                DaemonProjectCatalog.scanTestIds(root)
                    .map { entry -> selectorSuggestion(query, "id", entry.value, null, isProjectCatalog = true) }
                    .filter { it.confidence >= 0.3 }
                    .sortedWith(compareByDescending<SelectorSuggestion> { it.confidence }.thenBy { it.selectorValue })
            }.orEmpty()

            val mergedSuggestions = linkedMapOf<String, SelectorSuggestion>()
            for (suggestion in hierarchySuggestions + repoSuggestions) {
                val key = "${suggestion.strategy}:${suggestion.selectorValue}"
                mergedSuggestions.putIfAbsent(key, suggestion)
                if (mergedSuggestions.size >= maxSuggestions) {
                    break
                }
            }

            buildJsonObject {
                put("ok", JsonPrimitive(true))
                normalizedProjectRoot?.let { put("project_root", JsonPrimitive(it.toString())) }
                put("device_id", JsonPrimitive(sessionHandle.deviceId))
                put("session_id", JsonPrimitive(sessionHandle.sessionId))
                put(
                    "suggestions",
                    buildJsonArray {
                        mergedSuggestions.values.forEach { suggestion ->
                            add(
                                buildJsonObject {
                                    put("strategy", JsonPrimitive(suggestion.strategy))
                                    put(
                                        "selector",
                                        buildJsonObject {
                                            put(suggestion.strategy, JsonPrimitive(suggestion.selectorValue))
                                        },
                                    )
                                    put("confidence", JsonPrimitive(suggestion.confidence))
                                    put("rationale", JsonPrimitive(suggestion.rationale))
                                    suggestion.bounds?.let { put("bounds", JsonPrimitive(it)) }
                                },
                            )
                        }
                    },
                )
                put(
                    "hierarchy_preview",
                    buildJsonArray {
                        snapshot.nodes.take(15).forEach { node ->
                            add(
                                JsonPrimitive(
                                    buildString {
                                        append(node.id ?: "-")
                                        append(" | ")
                                        append(node.text ?: "")
                                        node.bounds?.let {
                                            append(" | ")
                                            append(it)
                                        }
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    fun runFlowWithDiagnostics(
        sessionManager: MaestroSessionManager,
        request: CallToolRequest,
    ): JsonObject {
        return withDiagnosticSession(sessionManager, request) { sessionHandle ->
            val captureDiagnosticsOnSuccess =
                optionalBoolean(request, "capture_diagnostics_on_success", "captureDiagnosticsOnSuccess") ?: false
            val flowYaml = optionalString(request, "flow_yaml", "flowYaml")
            val flowPath = optionalString(request, "flow_path", "flowPath")
            if (flowYaml.isNullOrBlank() && flowPath.isNullOrBlank()) {
                error("flow_path or flow_yaml is required")
            }

            val runResult = if (!flowYaml.isNullOrBlank()) {
                runInlineCompiledFlow(sessionHandle, request, flowYaml)
            } else {
                runCompiledFlow(sessionHandle, request)
            }

            val success = runResult["ok"]?.jsonPrimitive?.booleanOrNull == true
            val shouldCaptureDiagnostics = captureDiagnosticsOnSuccess || !success
            val hierarchyText = if (shouldCaptureDiagnostics) {
                McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
                    session.maestro.driver.automationSnapshot(
                        AutomationSnapshotRequest(
                            mode = AutomationSnapshotMode.MINIMAL,
                            flat = true,
                            interactiveOnly = false,
                            fields = DEFAULT_AUTOMATION_FIELDS,
                            maxDepth = 8,
                            includeStatusBars = false,
                            includeSafariWebViews = false,
                            excludeKeyboardElements = true,
                        ),
                    )
                }.nodes.joinToString(separator = "\n") { node ->
                    buildString {
                        append(node.id ?: "-")
                        append(",")
                        append(node.text ?: "")
                        node.bounds?.let {
                            append(",")
                            append(it)
                        }
                    }
                }
            } else {
                null
            }

            val screenshotPath = if (shouldCaptureDiagnostics) {
                val target = File.createTempFile("maestro-mcp-diagnostic-", ".png")
                McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
                    session.maestro.takeScreenshot(target, true)
                }
                target.absolutePath
            } else {
                null
            }

            buildJsonObject {
                runResult.forEach { (key, value) -> put(key, value) }
                put(
                    "run_summary",
                    JsonPrimitive(
                        runResult["error"]?.jsonPrimitive?.contentOrNull
                            ?: if (success) "Run completed." else "Run failed.",
                    ),
                )
                put("hierarchy_captured", JsonPrimitive(!hierarchyText.isNullOrBlank()))
                put("screenshot_captured", JsonPrimitive(!screenshotPath.isNullOrBlank()))
                hierarchyText?.let { put("hierarchy_text", JsonPrimitive(it)) }
                screenshotPath?.let { put("screenshot_path", JsonPrimitive(it)) }
            }
        }
    }

    internal fun runCompiledFlowDirect(
        sessionHandle: McpSessionRegistry.SessionHandle,
        flowPathRaw: String,
        env: Map<String, String> = emptyMap(),
        debugOutputDir: String? = null,
        testOutputDir: String? = null,
    ): JsonObject {
        val flowPath = resolvePath(sessionHandle.projectRoot, flowPathRaw)
        val flowFile = flowPath.toFile()
        val flowName = flowFile.nameWithoutExtension

        initializeRunMetadata(sessionHandle.owner, sessionHandle.label)
        installDebugOutputs(debugOutputDir, testOutputDir)

        val compiled = CompiledFlowCache.compileFlowFile(
            flowPath = flowPath,
            env = env,
            projectIndexVersion = projectIndexVersion(sessionHandle.projectRoot, forceRefresh = true),
        )
        val finalEnv = env
            .withInjectedShellEnvVars()
            .withDefaultEnvVars(flowFile, sessionHandle.deviceId)
        val commands = compiled.commands.withEnv(finalEnv)
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
            }
        }
        LiveTraceLogger.flowCompleted(
            flowName,
            flowSucceeded,
            buildString {
                append("compiledFlowMs=")
                append(elapsedMs)
                append(" sourceCommands=")
                append(compiled.sourceCommandCount)
                append(" optimizedCommands=")
                append(compiled.optimizedCommandCount)
                if (compiled.optimizationSummary.isNotEmpty()) {
                    append(" optimizationSummary=")
                    append(compiled.optimizationSummary.entries.joinToString(",") { (key, value) -> "$key=$value" })
                }
                flowError?.message?.let {
                    append(" error=")
                    append(it)
                }
            },
        )

        return buildFlowRunJson(
            sessionHandle = sessionHandle,
            flowPath = flowPath,
            flowSource = "flow_file",
            commandsExecuted = compiled.commands.size,
            elapsedMs = elapsedMs,
            compiled = compiled,
            flowSucceeded = flowSucceeded,
            flowError = flowError,
        )
    }

    private fun runCompiledFlow(
        sessionHandle: McpSessionRegistry.SessionHandle,
        request: CallToolRequest,
    ): JsonObject {
        val flowPathRaw = optionalString(request, "flow_path", "flowPath")
            ?: error("flow_path is required")
        val env = jsonStringMap(request.arguments["env"]?.jsonObject)
        val debugOutputDir = optionalString(request, "debug_output_dir", "debugOutputDir")
        val testOutputDir = optionalString(request, "test_output_dir", "testOutputDir")
        return runCompiledFlowDirect(
            sessionHandle = sessionHandle,
            flowPathRaw = flowPathRaw,
            env = env,
            debugOutputDir = debugOutputDir,
            testOutputDir = testOutputDir,
        )
    }

    internal fun runInlineCompiledFlowDirect(
        sessionHandle: McpSessionRegistry.SessionHandle,
        flowYaml: String,
        flowPathRaw: String? = null,
        env: Map<String, String> = emptyMap(),
        debugOutputDir: String? = null,
        testOutputDir: String? = null,
    ): JsonObject {
        val inlinePath = resolvePath(
            sessionHandle.projectRoot,
            flowPathRaw ?: ".maestro/inline-flow.yaml",
        )

        initializeRunMetadata(sessionHandle.owner, sessionHandle.label)
        installDebugOutputs(debugOutputDir, testOutputDir)

        val compiled = CompiledFlowCache.compileInlineFlow(
            flowPath = inlinePath,
            flowYaml = flowYaml,
            env = env,
            projectIndexVersion = projectIndexVersion(sessionHandle.projectRoot, forceRefresh = true),
        )
        val finalEnv = env
            .withInjectedShellEnvVars()
            .withDefaultEnvVars(inlinePath.toFile(), sessionHandle.deviceId)
        val commands = compiled.commands.withEnv(finalEnv)
        val flowName = inlinePath.fileName.toString().substringBeforeLast('.')
        var flowSucceeded = false
        var flowError: Throwable? = null
        val elapsedMs = measureTimeMillis {
            try {
                McpSessionRegistry.withSession(sessionHandle.sessionId) { session ->
                    val orchestra = daemonOrchestra(session.maestro, flowName, inlinePath.toFile())
                    flowSucceeded = runBlocking {
                        orchestra.runFlow(commands)
                    }
                }
            } catch (error: Throwable) {
                flowError = error
                flowSucceeded = false
            }
        }
        LiveTraceLogger.flowCompleted(
            flowName,
            flowSucceeded,
            buildString {
                append("compiledFlowMs=")
                append(elapsedMs)
                append(" sourceCommands=")
                append(compiled.sourceCommandCount)
                append(" optimizedCommands=")
                append(compiled.optimizedCommandCount)
                if (compiled.optimizationSummary.isNotEmpty()) {
                    append(" optimizationSummary=")
                    append(compiled.optimizationSummary.entries.joinToString(",") { (key, value) -> "$key=$value" })
                }
                flowError?.message?.let {
                    append(" error=")
                    append(it)
                }
            },
        )

        return buildFlowRunJson(
            sessionHandle = sessionHandle,
            flowPath = inlinePath,
            flowSource = "inline_yaml",
            commandsExecuted = compiled.commands.size,
            elapsedMs = elapsedMs,
            compiled = compiled,
            flowSucceeded = flowSucceeded,
            flowError = flowError,
        )
    }

    private fun runInlineCompiledFlow(
        sessionHandle: McpSessionRegistry.SessionHandle,
        request: CallToolRequest,
        flowYaml: String,
    ): JsonObject {
        val env = jsonStringMap(request.arguments["env"]?.jsonObject)
        val debugOutputDir = optionalString(request, "debug_output_dir", "debugOutputDir")
        val testOutputDir = optionalString(request, "test_output_dir", "testOutputDir")
        val flowPathRaw = optionalString(request, "flow_path", "flowPath")
        return runInlineCompiledFlowDirect(
            sessionHandle = sessionHandle,
            flowYaml = flowYaml,
            flowPathRaw = flowPathRaw,
            env = env,
            debugOutputDir = debugOutputDir,
            testOutputDir = testOutputDir,
        )
    }

    private fun buildFlowRunJson(
        sessionHandle: McpSessionRegistry.SessionHandle,
        flowPath: Path,
        flowSource: String,
        commandsExecuted: Int,
        elapsedMs: Long,
        compiled: CompiledFlowCache.CompiledFlow,
        flowSucceeded: Boolean,
        flowError: Throwable?,
    ): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(flowSucceeded))
        put("success", JsonPrimitive(flowSucceeded))
        put("status", JsonPrimitive(if (flowSucceeded) "passed" else "failed"))
        put("exitCode", JsonPrimitive(if (flowSucceeded) 0 else 1))
        put("session_id", JsonPrimitive(sessionHandle.sessionId))
        put("device_id", JsonPrimitive(sessionHandle.deviceId))
        put("flow_path", JsonPrimitive(flowPath.toAbsolutePath().toString()))
        put("flow_source", JsonPrimitive(flowSource))
        put("commands_executed", JsonPrimitive(commandsExecuted))
        put("source_command_count", JsonPrimitive(compiled.sourceCommandCount))
        put("optimized_command_count", JsonPrimitive(compiled.optimizedCommandCount))
        put("compiled_flow_ms", JsonPrimitive(elapsedMs))
        put("flow_hash", JsonPrimitive(compiled.flowHash))
        put("env_hash", JsonPrimitive(compiled.envHash))
        put("project_index_version", JsonPrimitive(compiled.projectIndexVersion))
        put("compiled_at_ms", JsonPrimitive(compiled.compiledAtMs))
        put(
            "optimization_summary",
            buildJsonObject {
                compiled.optimizationSummary.forEach { (key, value) ->
                    put(key, JsonPrimitive(value))
                }
            },
        )
        TestDebugReporter.getLiveTracePath()?.let { put("live_trace_path", JsonPrimitive(it.toAbsolutePath().toString())) }
        TestDebugReporter.getLiveStatusPath()?.let { put("live_status_path", JsonPrimitive(it.toAbsolutePath().toString())) }
        flowError?.let { put("error", JsonPrimitive(it.message ?: it::class.simpleName ?: "unknown error")) }
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
            command = "maestro-mcp",
            runOwner = owner,
            runLabel = label,
        )
    }

    private fun selectorSuggestion(
        query: String,
        strategy: String,
        selectorValue: String,
        bounds: String?,
        isProjectCatalog: Boolean,
    ): SelectorSuggestion {
        val confidence = scoreSelectorSuggestion(query, selectorValue, strategy == "id")
        val rounded = kotlin.math.round(confidence * 100.0) / 100.0
        val rationale = if (isProjectCatalog) {
            "Matched project testID $selectorValue"
        } else if (strategy == "id") {
            "Matched current automation snapshot id $selectorValue"
        } else {
            "Matched current automation snapshot text $selectorValue"
        }
        return SelectorSuggestion(
            strategy = strategy,
            selectorValue = selectorValue,
            confidence = rounded,
            rationale = rationale,
            bounds = bounds,
        )
    }

    private fun scoreSelectorSuggestion(query: String, candidate: String, preferId: Boolean): Double {
        val normalizedQuery = normalizeSuggestionText(query)
        val normalizedCandidate = normalizeSuggestionText(candidate)
        if (normalizedQuery.isBlank() || normalizedCandidate.isBlank()) {
            return 0.0
        }
        if (normalizedQuery == normalizedCandidate) {
            return if (preferId) 1.0 else 0.95
        }
        if (normalizedCandidate.contains(normalizedQuery)) {
            return if (preferId) 0.92 else 0.85
        }
        val queryTerms = normalizedQuery.split(' ').filter(String::isNotBlank)
        val candidateTerms = normalizedCandidate.split(' ').filter(String::isNotBlank)
        val overlap = queryTerms.count(candidateTerms::contains)
        if (overlap == 0) {
            return 0.0
        }
        val baseScore = overlap.toDouble() / maxOf(queryTerms.size, candidateTerms.size).toDouble()
        return if (preferId) baseScore + 0.1 else baseScore
    }

    private fun normalizeSuggestionText(value: String): String =
        value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun jsonStringMap(source: JsonObject?): Map<String, String> =
        source?.mapValues { (_, value) ->
            value.jsonPrimitive.contentOrNull ?: value.toString()
        }.orEmpty()

    private val json = Json { ignoreUnknownKeys = true }
}
