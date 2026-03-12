package xcuitest.api

data class AutomationQuerySelector(
    val id: String? = null,
    val text: String? = null,
    val index: Int? = null,
    val useFuzzyMatching: Boolean = true,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val selected: Boolean? = null,
)

data class AutomationQueryRequest(
    val appIds: List<String> = emptyList(),
    val selectors: List<AutomationQuerySelector>,
    val interactiveOnly: Boolean = false,
    val fields: List<String> = DEFAULT_AUTOMATION_FIELDS,
    val maxDepth: Int? = null,
    val includeStatusBars: Boolean = false,
    val includeSafariWebViews: Boolean = false,
    val excludeKeyboardElements: Boolean = false,
)
