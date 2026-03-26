package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import java.lang.UnsupportedOperationException

data class YamlReplaceText(
    val text: String,
    val waitToSettleTimeoutMs: Int? = null,
    val label: String? = null,
    val optional: Boolean = false,
) {

    companion object {

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(text: Any): YamlReplaceText {
            val replaceText = when (text) {
                is String -> text
                is Map<*, *> -> {
                    val input = text.getOrDefault("text", "") as String
                    val waitToSettleTimeoutMs = (text["waitToSettleTimeoutMs"] as? Number)?.toInt()
                    val label = text.getOrDefault("label", null) as String?
                    val optional = text.getOrDefault("optional", false) as? Boolean ?: false
                    return YamlReplaceText(
                        text = input,
                        waitToSettleTimeoutMs = waitToSettleTimeoutMs,
                        label = label,
                        optional = optional,
                    )
                }
                is Int, is Long, is Char, is Boolean, is Float, is Double -> text.toString()
                else -> throw UnsupportedOperationException("Cannot deserialize replace text with data type ${text.javaClass}")
            }
            return YamlReplaceText(text = replaceText)
        }
    }
}
