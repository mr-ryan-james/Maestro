package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SpeedProfileTest {

    @Test
    fun `resolves from flow config ext key`() {
        val profile = SpeedProfile.resolve(mapOf("speedProfile" to "fast")) { null }
        assertThat(profile).isEqualTo(SpeedProfile.FAST)
    }

    @Test
    fun `flow config wins over env var`() {
        val profile = SpeedProfile.resolve(mapOf("speedProfile" to "ferrari")) { "fast" }
        assertThat(profile).isEqualTo(SpeedProfile.FERRARI)
    }

    @Test
    fun `falls back to env var when config has no key`() {
        val profile = SpeedProfile.resolve(emptyMap()) { name ->
            if (name == "MAESTRO_SPEED_PROFILE") "FAST" else null
        }
        assertThat(profile).isEqualTo(SpeedProfile.FAST)
    }

    @Test
    fun `resolution is case-insensitive`() {
        assertThat(SpeedProfile.resolve(mapOf("speedProfile" to "FeRrArI")) { null })
            .isEqualTo(SpeedProfile.FERRARI)
    }

    @Test
    fun `unknown value and null config resolve to DEFAULT`() {
        assertThat(SpeedProfile.resolve(mapOf("speedProfile" to "warp9")) { null })
            .isEqualTo(SpeedProfile.DEFAULT)
        assertThat(SpeedProfile.resolve(null) { null }).isEqualTo(SpeedProfile.DEFAULT)
    }

    @Test
    fun `fast profile carries speed settings`() {
        val s = SpeedProfile.FAST.settings
        assertThat(s.minorSettleBudgetMs).isEqualTo(500)
        assertThat(s.screenSettleBudgetMs).isEqualTo(1500)
        assertThat(s.lookupTimeoutMs).isEqualTo(3000L)
        assertThat(s.optionalLookupTimeoutMs).isEqualTo(2000L)
        assertThat(s.skipPreTapSettle).isTrue()
        assertThat(s.disableAnimations).isTrue()
    }

    @Test
    fun `default profile carries untouched settings`() {
        assertThat(SpeedProfile.DEFAULT.settings).isEqualTo(maestro.settle.SpeedSettings.DEFAULT)
    }
}
