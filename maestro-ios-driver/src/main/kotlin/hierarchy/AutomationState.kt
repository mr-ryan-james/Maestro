package hierarchy

import java.security.MessageDigest
import xcuitest.api.AutomationQueryRequest
import xcuitest.api.AutomationQuerySelector
import xcuitest.api.AutomationSnapshotRequest

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

data class AutomationSnapshotResult(
    val source: String,
    val mode: String,
    val changed: Boolean,
    val token: String?,
    val nodeCount: Int,
    val nodes: List<AutomationNode>,
)

data class AutomationQueryMatchResult(
    val selectorIndex: Int,
    val matchCount: Int,
    val nodes: List<AutomationNode>,
)

data class AutomationQueryResult(
    val source: String,
    val token: String?,
    val matches: List<AutomationQueryMatchResult>,
)

fun ViewHierarchy.toAutomationSnapshotResult(
    request: AutomationSnapshotRequest,
    source: String = "ios_fallback",
): AutomationSnapshotResult {
    val nodes = axElement.flattenAutomationNodes(
        fields = request.fields.toSet(),
        interactiveOnly = request.interactiveOnly,
        maxDepth = request.maxDepth,
    )
    val token = automationToken(nodes)
    val changed = request.sinceToken == null || request.sinceToken != token
    return AutomationSnapshotResult(
        source = source,
        mode = request.mode,
        changed = changed,
        token = token,
        nodeCount = nodes.size,
        nodes = if (changed) nodes else emptyList(),
    )
}

fun AutomationSnapshotResult.query(
    request: AutomationQueryRequest,
    source: String = this.source,
): AutomationQueryResult {
    val matches = request.selectors.mapIndexed { selectorIndex, selector ->
        val filtered = nodes.filter { selector.matches(it) }.let { results ->
            selector.index?.let { index ->
                results.getOrNull(index)?.let(::listOf) ?: emptyList()
            } ?: results
        }
        AutomationQueryMatchResult(
            selectorIndex = selectorIndex,
            matchCount = filtered.size,
            nodes = filtered,
        )
    }
    return AutomationQueryResult(
        source = source,
        token = token,
        matches = matches,
    )
}

private fun AXElement.flattenAutomationNodes(
    fields: Set<String>,
    interactiveOnly: Boolean,
    maxDepth: Int?,
    depth: Int = 0,
): List<AutomationNode> {
    if (maxDepth != null && depth > maxDepth) {
        return emptyList()
    }

    val checked = elementType in CHECKABLE_ELEMENT_TYPES && value == "1"
    val node = AutomationNode(
        id = if ("id" in fields) identifier.takeIf { it.isNotBlank() } else null,
        text = if ("text" in fields) title?.ifEmpty { value } ?: label.ifEmpty { value ?: "" }.ifBlank { null } else null,
        bounds = if ("bounds" in fields) frame.boundsString else null,
        enabled = if ("enabled" in fields) enabled else null,
        checked = if ("checked" in fields) checked else null,
        focused = if ("focused" in fields) hasFocus else null,
        selected = if ("selected" in fields) selected else null,
        clickable = if ("clickable" in fields) isInteractiveElement() else null,
        depth = if ("depth" in fields) depth else 0,
    )

    val childrenNodes = children.flatMap {
        it.flattenAutomationNodes(
            fields = fields,
            interactiveOnly = interactiveOnly,
            maxDepth = maxDepth,
            depth = depth + 1,
        )
    }

    val includeNode = !interactiveOnly || node.clickable == true || !node.id.isNullOrBlank() || !node.text.isNullOrBlank()
    return if (includeNode) listOf(node) + childrenNodes else childrenNodes
}

private fun AutomationQuerySelector.matches(node: AutomationNode): Boolean {
    val matchesId = when {
        id.isNullOrBlank() -> true
        useFuzzyMatching -> (node.id ?: "").contains(id, ignoreCase = true)
        else -> (node.id ?: "") == id
    }
    val matchesText = when {
        text.isNullOrBlank() -> true
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

private fun AXElement.isInteractiveElement(): Boolean {
    return elementType in INTERACTIVE_ELEMENT_TYPES || identifier.isNotBlank()
}

private val CHECKABLE_ELEMENT_TYPES = setOf(
    14, // switch
    15, // toggle/button variant
)

private val INTERACTIVE_ELEMENT_TYPES = setOf(
    1, // button
    3, // text field
    4, // secure text field
    9, // cell
    10, // link
    11, // image
    14, // switch
    15, // radio/checkbox-like
    40, // other tappable element
)
