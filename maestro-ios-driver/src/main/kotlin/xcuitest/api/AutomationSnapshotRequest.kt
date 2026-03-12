package xcuitest.api

data class AutomationSnapshotRequest(
    val appIds: List<String> = emptyList(),
    val mode: String = "minimal",
    val flat: Boolean = true,
    val interactiveOnly: Boolean = false,
    val fields: List<String> = DEFAULT_AUTOMATION_FIELDS,
    val maxDepth: Int? = null,
    val includeStatusBars: Boolean = false,
    val includeSafariWebViews: Boolean = false,
    val excludeKeyboardElements: Boolean = false,
    val sinceToken: String? = null,
)

val DEFAULT_AUTOMATION_FIELDS = listOf(
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
