package maestro.cli.daemon

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

object DaemonBridgeRegistry {

    private val mapper = jacksonObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

    data class SemanticState(
        val appId: String? = null,
        val route: String? = null,
        val userId: String? = null,
        val firebaseUid: String? = null,
        val metroConnected: Boolean = false,
        val debugUiVisible: Boolean = false,
        val featureFlags: Map<String, Any?> = emptyMap(),
        val conversationIds: List<String> = emptyList(),
        val visibleConversationId: String? = null,
        val conversationListReady: Boolean = false,
        val discoverReady: Boolean = false,
        val voiceUiReady: Boolean = false,
        val promptGenerationState: String? = null,
        val knownOverlays: List<String> = emptyList(),
        val primaryAction: String? = null,
        val lastCommand: String? = null,
        val stateVersion: Long = 0L,
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    data class CommandResult(
        val id: String,
        val ok: Boolean,
        val error: String? = null,
        val stateVersion: Long? = null,
        val route: String? = null,
        val userId: String? = null,
        val firebaseUid: String? = null,
    )

    private data class BridgeClient(
        val connectionId: String,
        val appId: String,
        val session: DefaultWebSocketServerSession,
        @Volatile var state: SemanticState,
        val pendingResults: ConcurrentHashMap<String, CompletableDeferred<CommandResult>> = ConcurrentHashMap(),
    )

    private data class IncomingFrame(
        val type: String? = null,
        val appId: String? = null,
        val state: Map<String, Any?>? = null,
        val id: String? = null,
        val ok: Boolean? = null,
        val error: String? = null,
        val stateVersion: Long? = null,
        val route: String? = null,
        val userId: String? = null,
        val firebaseUid: String? = null,
    )

    private data class OutgoingCommandFrame(
        val type: String = "command",
        val id: String,
        val kind: String,
        val args: Map<String, Any?> = emptyMap(),
    )

    private val clients = ConcurrentHashMap<String, BridgeClient>()

    fun connectionCount(): Int = clients.size

    fun isConnected(appId: String?): Boolean {
        if (appId.isNullOrBlank()) {
            return false
        }
        return clients.values.any { client -> client.appId == appId }
    }

    fun semanticState(appId: String?): SemanticState? {
        if (appId.isNullOrBlank()) {
            return null
        }
        return clients.values
            .filter { client -> client.appId == appId }
            .maxByOrNull { client -> client.state.updatedAtMs }
            ?.state
    }

    suspend fun register(session: DefaultWebSocketServerSession): String {
        val connectionId = UUID.randomUUID().toString()
        val placeholder = BridgeClient(
            connectionId = connectionId,
            appId = "unknown",
            session = session,
            state = SemanticState(appId = "unknown"),
        )
        clients[connectionId] = placeholder
        return connectionId
    }

    fun unregister(connectionId: String) {
        val client = clients.remove(connectionId) ?: return
        client.pendingResults.forEach { (_, deferred) ->
            deferred.complete(
                CommandResult(
                    id = "bridge-disconnected",
                    ok = false,
                    error = "Bridge disconnected",
                ),
            )
        }
        client.pendingResults.clear()
    }

    suspend fun handleFrame(connectionId: String, rawText: String) {
        val frame = mapper.readValue(rawText, IncomingFrame::class.java)
        when (frame.type?.lowercase()) {
            "hello" -> updateState(
                connectionId = connectionId,
                appId = frame.appId,
                statePatch = frame.state ?: emptyMap(),
            )

            "state" -> updateState(
                connectionId = connectionId,
                appId = frame.appId,
                statePatch = frame.state ?: emptyMap(),
            )

            "result" -> recordResult(
                connectionId = connectionId,
                result = CommandResult(
                    id = frame.id ?: "unknown",
                    ok = frame.ok == true,
                    error = frame.error,
                    stateVersion = frame.stateVersion,
                    route = frame.route,
                    userId = frame.userId,
                    firebaseUid = frame.firebaseUid,
                ),
                statePatch = frame.state,
            )
        }
    }

    suspend fun sendCommand(
        appId: String,
        kind: String,
        args: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 15_000L,
    ): CommandResult {
        val client = clients.values
            .filter { bridge -> bridge.appId == appId }
            .maxByOrNull { bridge -> bridge.state.updatedAtMs }
            ?: return CommandResult(
                id = "bridge-missing",
                ok = false,
                error = "No bridge connected for appId=$appId",
            )

        val commandId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<CommandResult>()
        client.pendingResults[commandId] = deferred

        return try {
            client.session.send(
                Frame.Text(
                    mapper.writeValueAsString(
                        OutgoingCommandFrame(
                            id = commandId,
                            kind = kind,
                            args = args,
                        ),
                    ),
                ),
            )
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (error: TimeoutCancellationException) {
            CommandResult(
                id = commandId,
                ok = false,
                error = "Bridge command timed out after ${timeoutMs}ms",
            )
        } finally {
            client.pendingResults.remove(commandId)
        }
    }

    private fun updateState(
        connectionId: String,
        appId: String?,
        statePatch: Map<String, Any?>,
    ) {
        val previous = clients[connectionId] ?: return
        val resolvedAppId = (appId ?: statePatch["appId"]?.toString() ?: previous.appId).ifBlank { previous.appId }
        val nextState = previous.state.copy(
            appId = resolvedAppId,
            route = statePatch["route"]?.toString() ?: previous.state.route,
            userId = statePatch["user_id"]?.toString()
                ?: statePatch["userId"]?.toString()
                ?: previous.state.userId,
            firebaseUid = statePatch["firebase_uid"]?.toString()
                ?: statePatch["firebaseUid"]?.toString()
                ?: previous.state.firebaseUid,
            metroConnected = (statePatch["metro_connected"] as? Boolean)
                ?: (statePatch["metroConnected"] as? Boolean)
                ?: previous.state.metroConnected,
            debugUiVisible = (statePatch["debug_ui_visible"] as? Boolean)
                ?: (statePatch["debugUiVisible"] as? Boolean)
                ?: previous.state.debugUiVisible,
            featureFlags = (statePatch["feature_flags"] as? Map<*, *>)?.entries?.associate { (key, value) ->
                key.toString() to value
            } ?: previous.state.featureFlags,
            conversationIds = (statePatch["conversation_ids"] as? List<*>)?.map { it.toString() }
                ?: previous.state.conversationIds,
            visibleConversationId = statePatch["visible_conversation_id"]?.toString()
                ?: statePatch["visibleConversationId"]?.toString()
                ?: previous.state.visibleConversationId,
            conversationListReady = (statePatch["conversation_list_ready"] as? Boolean)
                ?: (statePatch["conversationListReady"] as? Boolean)
                ?: previous.state.conversationListReady,
            discoverReady = (statePatch["discover_ready"] as? Boolean)
                ?: (statePatch["discoverReady"] as? Boolean)
                ?: previous.state.discoverReady,
            voiceUiReady = (statePatch["voice_ui_ready"] as? Boolean)
                ?: (statePatch["voiceUiReady"] as? Boolean)
                ?: previous.state.voiceUiReady,
            promptGenerationState = statePatch["prompt_generation_state"]?.toString()
                ?: statePatch["promptGenerationState"]?.toString()
                ?: previous.state.promptGenerationState,
            knownOverlays = (statePatch["known_overlays"] as? List<*>)?.map { it.toString() }
                ?: previous.state.knownOverlays,
            primaryAction = statePatch["primary_action"]?.toString()
                ?: statePatch["primaryAction"]?.toString()
                ?: previous.state.primaryAction,
            lastCommand = statePatch["last_command"]?.toString()
                ?: statePatch["lastCommand"]?.toString()
                ?: previous.state.lastCommand,
            stateVersion = (statePatch["state_version"] as? Number)?.toLong()
                ?: (statePatch["stateVersion"] as? Number)?.toLong()
                ?: previous.state.stateVersion,
            updatedAtMs = System.currentTimeMillis(),
        )

        clients[connectionId] = previous.copy(
            appId = resolvedAppId,
            state = nextState,
        )
    }

    private fun recordResult(
        connectionId: String,
        result: CommandResult,
        statePatch: Map<String, Any?>?,
    ) {
        if (statePatch != null) {
            updateState(connectionId, null, statePatch)
        }
        val client = clients[connectionId] ?: return
        client.pendingResults[result.id]?.complete(result)
    }
}
