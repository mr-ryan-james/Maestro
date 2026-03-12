package maestro

import java.security.MessageDigest

enum class AutomationSnapshotMode {
    MINIMAL,
    FULL,
}

data class AutomationSelector(
    val id: String? = null,
    val text: String? = null,
    val index: Int? = null,
    val useFuzzyMatching: Boolean = true,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val selected: Boolean? = null,
) {
    fun matches(node: AutomationNode): Boolean {
        val matchesId = when {
            id == null -> true
            useFuzzyMatching -> (node.id ?: "").contains(id, ignoreCase = true)
            else -> (node.id ?: "") == id
        }
        val matchesText = when {
            text == null -> true
            useFuzzyMatching -> (node.text ?: "").contains(text, ignoreCase = true)
            else -> (node.text ?: "") == text
        }

        return matchesId
            && matchesText
            && (enabled?.let { node.enabled == it } ?: true)
            && (checked?.let { node.checked == it } ?: true)
            && (focused?.let { node.focused == it } ?: true)
            && (selected?.let { node.selected == it } ?: true)
    }
}

data class AutomationSnapshotRequest(
    val mode: AutomationSnapshotMode = AutomationSnapshotMode.MINIMAL,
    val flat: Boolean = true,
    val interactiveOnly: Boolean = false,
    val fields: Set<String> = DEFAULT_AUTOMATION_FIELDS,
    val maxDepth: Int? = null,
    val includeStatusBars: Boolean = false,
    val includeSafariWebViews: Boolean = false,
    val excludeKeyboardElements: Boolean = false,
    val sinceToken: String? = null,
)

data class AutomationQueryRequest(
    val selectors: List<AutomationSelector>,
    val interactiveOnly: Boolean = false,
    val fields: Set<String> = DEFAULT_AUTOMATION_FIELDS,
    val maxDepth: Int? = null,
    val includeStatusBars: Boolean = false,
    val includeSafariWebViews: Boolean = false,
    val excludeKeyboardElements: Boolean = false,
) {
    fun asSnapshotRequest(): AutomationSnapshotRequest {
        return AutomationSnapshotRequest(
            mode = AutomationSnapshotMode.MINIMAL,
            flat = true,
            interactiveOnly = interactiveOnly,
            fields = fields,
            maxDepth = maxDepth,
            includeStatusBars = includeStatusBars,
            includeSafariWebViews = includeSafariWebViews,
            excludeKeyboardElements = excludeKeyboardElements,
        )
    }
}

data class AutomationWaitRequest(
    val selectors: List<AutomationSelector>,
    val notVisible: Boolean = false,
    val timeoutMs: Long = 5_000L,
    val pollIntervalMs: Long = 200L,
    val interactiveOnly: Boolean = false,
    val fields: Set<String> = DEFAULT_AUTOMATION_FIELDS,
    val maxDepth: Int? = null,
    val includeStatusBars: Boolean = false,
    val includeSafariWebViews: Boolean = false,
    val excludeKeyboardElements: Boolean = false,
)

data class AutomationNode(
    val id: String? = null,
    val text: String? = null,
    val bounds: String? = null,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val selected: Boolean? = null,
    val clickable: Boolean? = null,
    val depth: Int = 0,
)

data class AutomationSnapshot(
    val source: String,
    val mode: AutomationSnapshotMode,
    val changed: Boolean,
    val token: String?,
    val nodeCount: Int,
    val nodes: List<AutomationNode>,
)

data class AutomationQueryMatch(
    val selectorIndex: Int,
    val matchCount: Int,
    val nodes: List<AutomationNode>,
)

data class AutomationQueryResult(
    val source: String,
    val token: String?,
    val matches: List<AutomationQueryMatch>,
)

data class AutomationWaitResult(
    val satisfied: Boolean,
    val source: String,
    val elapsedMs: Long,
    val token: String?,
    val snapshot: AutomationSnapshot?,
)

val DEFAULT_AUTOMATION_FIELDS = linkedSetOf(
    "id",
    "text",
    "bounds",
    "enabled",
    "checked",
    "focused",
    "selected",
    "clickable",
    "depth",
)

fun TreeNode.toAutomationSnapshot(
    request: AutomationSnapshotRequest = AutomationSnapshotRequest(),
    source: String = "driver_fallback",
): AutomationSnapshot {
    val nodes = flattenAutomationNodes(
        fields = request.fields,
        interactiveOnly = request.interactiveOnly,
        maxDepth = request.maxDepth,
    )
    val token = automationToken(nodes)
    val changed = request.sinceToken == null || request.sinceToken != token
    val returnedNodes = if (changed) nodes else emptyList()
    return AutomationSnapshot(
        source = source,
        mode = request.mode,
        changed = changed,
        token = token,
        nodeCount = nodes.size,
        nodes = returnedNodes,
    )
}

fun TreeNode.queryAutomationElements(
    request: AutomationQueryRequest,
    source: String = "driver_fallback",
): AutomationQueryResult {
    val snapshot = toAutomationSnapshot(request.asSnapshotRequest(), source)
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

private fun TreeNode.flattenAutomationNodes(
    fields: Set<String>,
    interactiveOnly: Boolean,
    maxDepth: Int?,
    depth: Int = 0,
): List<AutomationNode> {
    if (maxDepth != null && depth > maxDepth) {
        return emptyList()
    }

    val node = AutomationNode(
        id = if ("id" in fields) attributes["resource-id"] ?: attributes["identifier"] ?: attributes["id"] else null,
        text = if ("text" in fields) {
            attributes["text"]
                ?: attributes["hintText"]
                ?: attributes["accessibilityText"]
        } else {
            null
        },
        bounds = if ("bounds" in fields) attributes["bounds"] else null,
        enabled = if ("enabled" in fields) enabled else null,
        checked = if ("checked" in fields) checked else null,
        focused = if ("focused" in fields) focused else null,
        selected = if ("selected" in fields) selected else null,
        clickable = if ("clickable" in fields) clickable else null,
        depth = if ("depth" in fields) depth else 0,
    )

    val includeNode = !interactiveOnly || node.clickable == true || !node.id.isNullOrBlank() || !node.text.isNullOrBlank()
    val children = children.flatMap {
        it.flattenAutomationNodes(
            fields = fields,
            interactiveOnly = interactiveOnly,
            maxDepth = maxDepth,
            depth = depth + 1,
        )
    }

    return if (includeNode) {
        listOf(node) + children
    } else {
        children
    }
}

private fun automationToken(nodes: List<AutomationNode>): String {
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
