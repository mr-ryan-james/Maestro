package maestro.cli.daemon

import java.security.MessageDigest
import maestro.AutomationNode
import maestro.AutomationQueryMatch
import maestro.AutomationQueryRequest
import maestro.AutomationQueryResult
import maestro.AutomationSelector
import maestro.AutomationSnapshot
import maestro.AutomationSnapshotMode
import maestro.AutomationSnapshotRequest

internal object DaemonSemanticAutomation {

    const val SOURCE_SEMANTIC = "semantic_bridge"

    data class SnapshotResolution(
        val snapshot: AutomationSnapshot?,
        val fallbackReason: String? = null,
        val requestedSourcePreference: String = "auto",
    )

    data class QueryResolution(
        val result: AutomationQueryResult?,
        val fallbackReason: String? = null,
        val requestedSourcePreference: String = "auto",
    )

    fun normalizeSourcePreference(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "semantic", "semantic_bridge" -> "semantic"
            "driver", "driver_minimal", "minimal" -> "driver_minimal"
            "driver_full", "full" -> "driver_full"
            else -> "auto"
        }
    }

    fun resolveSnapshot(
        state: DaemonBridgeRegistry.SemanticState?,
        request: AutomationSnapshotRequest,
        sourcePreference: String?,
    ): SnapshotResolution {
        val preference = normalizeSourcePreference(sourcePreference)
        if (preference == "driver_minimal" || preference == "driver_full") {
            return SnapshotResolution(
                snapshot = null,
                fallbackReason = "source_preference_driver",
                requestedSourcePreference = preference,
            )
        }
        if (state == null) {
            return SnapshotResolution(
                snapshot = null,
                fallbackReason = "bridge_unavailable",
                requestedSourcePreference = preference,
            )
        }
        return SnapshotResolution(
            snapshot = buildSnapshot(state, request),
            requestedSourcePreference = preference,
        )
    }

    fun resolveQuery(
        state: DaemonBridgeRegistry.SemanticState?,
        request: AutomationQueryRequest,
        sourcePreference: String?,
    ): QueryResolution {
        val preference = normalizeSourcePreference(sourcePreference)
        if (preference == "driver_minimal" || preference == "driver_full") {
            return QueryResolution(
                result = null,
                fallbackReason = "source_preference_driver",
                requestedSourcePreference = preference,
            )
        }
        if (state == null) {
            return QueryResolution(
                result = null,
                fallbackReason = "bridge_unavailable",
                requestedSourcePreference = preference,
            )
        }

        if (preference == "semantic" || supportsSemanticQuery(request.selectors)) {
            return QueryResolution(
                result = buildQuery(state, request),
                requestedSourcePreference = preference,
            )
        }

        return QueryResolution(
            result = null,
            fallbackReason = "semantic_unsupported_selector",
            requestedSourcePreference = preference,
        )
    }

    private fun buildSnapshot(
        state: DaemonBridgeRegistry.SemanticState,
        request: AutomationSnapshotRequest,
    ): AutomationSnapshot {
        val maxDepth = request.maxDepth
        val nodes = semanticNodes(state)
            .filter { node -> !request.interactiveOnly || node.clickable == true || !node.id.isNullOrBlank() || !node.text.isNullOrBlank() }
            .filter { node -> maxDepth == null || node.depth <= maxDepth }
            .map { node -> filterNodeFields(node, request.fields) }
        val token = semanticToken(nodes)
        val changed = request.sinceToken == null || request.sinceToken != token
        return AutomationSnapshot(
            source = SOURCE_SEMANTIC,
            mode = request.mode,
            changed = changed,
            token = token,
            nodeCount = nodes.size,
            nodes = if (changed) nodes else emptyList(),
        )
    }

    private fun buildQuery(
        state: DaemonBridgeRegistry.SemanticState,
        request: AutomationQueryRequest,
    ): AutomationQueryResult {
        val snapshot = buildSnapshot(
            state,
            request.asSnapshotRequest().copy(
                fields = request.fields,
                maxDepth = request.maxDepth,
            ),
        )
        val matches = request.selectors.mapIndexed { selectorIndex, selector ->
            val filtered = snapshot.nodes.filter(selector::matches).let { results ->
                selector.index?.let { index ->
                    results.getOrNull(index)?.let(::listOf) ?: emptyList()
                } ?: results
            }
            AutomationQueryMatch(
                selectorIndex = selectorIndex,
                matchCount = filtered.size,
                nodes = filtered,
            )
        }
        return AutomationQueryResult(
            source = snapshot.source,
            token = snapshot.token,
            matches = matches,
        )
    }

    private fun supportsSemanticQuery(selectors: List<AutomationSelector>): Boolean {
        if (selectors.isEmpty()) {
            return false
        }
        return selectors.all { selector ->
            val id = selector.id?.trim().orEmpty()
            id.isNotBlank() && SEMANTIC_ID_PREFIXES.any(id::startsWith)
        }
    }

    private fun semanticNodes(
        state: DaemonBridgeRegistry.SemanticState,
    ): List<AutomationNode> {
        val nodes = mutableListOf<AutomationNode>()

        fun addNode(
            id: String,
            text: String? = null,
            clickable: Boolean? = false,
            enabled: Boolean? = true,
            selected: Boolean? = null,
        ) {
            nodes += AutomationNode(
                id = id,
                text = text,
                enabled = enabled,
                selected = selected,
                clickable = clickable,
                depth = 0,
            )
        }

        addNode("automation-bridge-enabled", "true")
        addNode("automation-bridge-ready", "true")
        if (state.userId != null || state.firebaseUid != null) {
            addNode("automation-bridge-auth-ready", "true")
        }
        if (state.metroConnected) {
            addNode("automation-bridge-metro-connected", "true")
        }
        if (state.conversationListReady) {
            addNode("automation-bridge-realtime-conversations-ready", "true")
        }
        if (state.discoverReady) {
            addNode("automation-bridge-discover-ready", "true")
        }
        if (state.voiceUiReady) {
            addNode("automation-bridge-voice-ui-ready", "true")
        }

        val routeSlug = toMarkerSlug(state.route)
        if (!routeSlug.isNullOrBlank()) {
            addNode("automation-bridge-route-$routeSlug", state.route)
        }
        val commandSlug = toMarkerSlug(state.lastCommand)
        if (!commandSlug.isNullOrBlank()) {
            addNode("automation-bridge-command-$commandSlug", state.lastCommand)
        }
        val statusSlug = toMarkerSlug(state.lastCommandStatus)
        if (!statusSlug.isNullOrBlank()) {
            addNode("automation-bridge-status-$statusSlug", state.lastCommandStatus)
        }

        when (state.promptGenerationState?.trim()?.lowercase()) {
            "ready" -> {
                addNode("automation-bridge-prompt-ready", "ready")
                addNode("automation-bridge-prompt-generation-ready", "ready")
            }
            "running" -> {
                addNode("automation-bridge-prompt-ready", "running")
                addNode("automation-bridge-prompt-generation-open", "running")
            }
            else -> Unit
        }

        if (!state.route.isNullOrBlank()) {
            addNode("automation-bridge-route-value", state.route)
        }
        if (!state.userId.isNullOrBlank()) {
            addNode("automation-bridge-user-id", state.userId)
        }
        if (!state.firebaseUid.isNullOrBlank()) {
            addNode("automation-bridge-firebase-uid", state.firebaseUid)
        }
        if (!state.visibleConversationId.isNullOrBlank()) {
            addNode(
                id = "automation-visible-conversation-${state.visibleConversationId}",
                text = state.visibleConversationId,
                selected = true,
            )
        }
        state.conversationIds.forEach { conversationId ->
            addNode(
                id = "automation-conversation-$conversationId",
                text = conversationId,
                clickable = true,
            )
        }
        state.knownOverlays.forEach { overlay ->
            addNode(
                id = "automation-bridge-overlay-${toMarkerSlug(overlay) ?: overlay}",
                text = overlay,
            )
        }
        state.featureFlags.forEach { (key, value) ->
            addNode(
                id = "automation-bridge-feature-flag-${toMarkerSlug(key) ?: key}",
                text = value?.toString() ?: "null",
            )
        }
        if (!state.primaryAction.isNullOrBlank()) {
            addNode("automation-bridge-primary-action", state.primaryAction, clickable = true)
            addNode(
                "automation-bridge-primary-action-${toMarkerSlug(state.primaryAction) ?: state.primaryAction}",
                state.primaryAction,
                clickable = true,
            )
        }
        if (!state.lastCommandId.isNullOrBlank()) {
            addNode("automation-bridge-last-command-id", state.lastCommandId)
        }

        return nodes
    }

    private fun filterNodeFields(
        node: AutomationNode,
        fields: Set<String>,
    ): AutomationNode {
        return AutomationNode(
            id = if ("id" in fields) node.id else null,
            text = if ("text" in fields) node.text else null,
            bounds = if ("bounds" in fields) node.bounds else null,
            enabled = if ("enabled" in fields) node.enabled else null,
            checked = if ("checked" in fields) node.checked else null,
            focused = if ("focused" in fields) node.focused else null,
            selected = if ("selected" in fields) node.selected else null,
            clickable = if ("clickable" in fields) node.clickable else null,
            depth = if ("depth" in fields) node.depth else 0,
        )
    }

    private fun semanticToken(nodes: List<AutomationNode>): String {
        val normalized = nodes.joinToString(separator = "\n") { node ->
            listOf(
                node.id ?: "",
                node.text ?: "",
                node.bounds ?: "",
                node.enabled?.toString() ?: "",
                node.checked?.toString() ?: "",
                node.focused?.toString() ?: "",
                node.selected?.toString() ?: "",
                node.clickable?.toString() ?: "",
                node.depth.toString(),
            ).joinToString(separator = "\u001f")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun toMarkerSlug(value: String?): String? {
        if (value.isNullOrBlank()) {
            return null
        }
        return value.trim()
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .replace(Regex("[^a-zA-Z0-9]+"), "-")
            .trim('-')
            .lowercase()
            .ifBlank { null }
    }

    private val SEMANTIC_ID_PREFIXES = listOf(
        "automation-bridge-",
        "automation-conversation-",
        "automation-visible-conversation-",
    )
}
