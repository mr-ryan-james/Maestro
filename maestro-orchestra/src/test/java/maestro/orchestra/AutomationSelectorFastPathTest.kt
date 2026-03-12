package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class AutomationSelectorFastPathTest {

    @Test
    fun `fast path uses exact matching for plain literal selectors`() {
        val selector = ElementSelector(textRegex = "OK").toAutomationSelectorFastPath()

        assertThat(selector).isNotNull()
        assertThat(selector?.text).isEqualTo("OK")
        assertThat(selector?.useFuzzyMatching).isFalse()
    }

    @Test
    fun `fast path unescapes exact literal regex selectors`() {
        val selector = ElementSelector(textRegex = "\\[runtime not ready\\]").toAutomationSelectorFastPath()

        assertThat(selector).isNotNull()
        assertThat(selector?.text).isEqualTo("[runtime not ready]")
        assertThat(selector?.useFuzzyMatching).isFalse()
    }

    @Test
    fun `fast path keeps wrapped wildcard literals as fuzzy matches`() {
        val selector = ElementSelector(textRegex = ".*Open in.*").toAutomationSelectorFastPath()

        assertThat(selector).isNotNull()
        assertThat(selector?.text).isEqualTo("Open in")
        assertThat(selector?.useFuzzyMatching).isTrue()
    }

    @Test
    fun `fast path rejects alternation regex selectors`() {
        val selector = ElementSelector(
            textRegex = "Would Like to Send You Notifications|\"thrivify\" Would Like to Send You Notifications",
        ).toAutomationSelectorFastPath()

        assertThat(selector).isNull()
    }

    @Test
    fun `fast path strips anchors for exact id selectors`() {
        val selector = ElementSelector(idRegex = "^chat-list-item-123$").toAutomationSelectorFastPath()

        assertThat(selector).isNotNull()
        assertThat(selector?.id).isEqualTo("chat-list-item-123")
        assertThat(selector?.useFuzzyMatching).isFalse()
    }
}
