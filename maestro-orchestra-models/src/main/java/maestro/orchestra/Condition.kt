package maestro.orchestra

import maestro.device.Platform
import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts

data class Condition(
    val platform: Platform? = null,
    val visible: ElementSelector? = null,
    val visibleNow: ElementSelector? = null,
    val notVisible: ElementSelector? = null,
    val notVisibleNow: ElementSelector? = null,
    val scriptCondition: String? = null,
    val conditionTimeoutMs: Long? = null,
    val label: String? = null,
) {

    fun evaluateScripts(jsEngine: JsEngine): Condition {
        return copy(
            visible = visible?.evaluateScripts(jsEngine),
            visibleNow = visibleNow?.evaluateScripts(jsEngine),
            notVisible = notVisible?.evaluateScripts(jsEngine),
            notVisibleNow = notVisibleNow?.evaluateScripts(jsEngine),
            scriptCondition = scriptCondition?.evaluateScripts(jsEngine),
        )
    }

    fun description(): String {
        if(label != null){
            return label
        }

        val descriptions = mutableListOf<String>()

        platform?.let {
            descriptions.add("Platform is $it")
        }

        visible?.let {
            descriptions.add("${it.description()} is visible")
        }

        visibleNow?.let {
            descriptions.add("${it.description()} is visible now")
        }

        notVisible?.let {
            descriptions.add("${it.description()} is not visible")
        }

        notVisibleNow?.let {
            descriptions.add("${it.description()} is not visible now")
        }

        scriptCondition?.let {
            descriptions.add("$it is true")
        }

        return if (descriptions.isEmpty()) {
            "true"
        } else {
            descriptions.joinToString(" and ")
        }
    }

}
