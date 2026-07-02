package maestro.settle

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SpeedSettingsTest {

    @Test
    fun `defaults match current upstream timing behavior`() {
        val s = SpeedSettings.DEFAULT
        assertThat(s.minorSettleBudgetMs).isNull()
        assertThat(s.screenSettleBudgetMs).isNull()
        assertThat(s.lookupTimeoutMs).isEqualTo(17000L)
        assertThat(s.optionalLookupTimeoutMs).isEqualTo(7000L)
        assertThat(s.skipPreTapSettle).isFalse()
        assertThat(s.disableAnimations).isFalse()
    }
}
