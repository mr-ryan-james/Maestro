package maestro.orchestra

import maestro.settle.SpeedSettings

private val FAST_SETTINGS = SpeedSettings(
    minorSettleBudgetMs = 500,
    screenSettleBudgetMs = 1500,
    lookupTimeoutMs = 3000L,
    optionalLookupTimeoutMs = 2000L,
    skipPreTapSettle = true,
    disableAnimations = true,
)

/**
 * Speed profiles bundle SpeedSettings. Resolution precedence:
 * flow config `speedProfile:` key (lands in MaestroConfig.ext via @JsonAnySetter,
 * same pattern as the `jsEngine` ext key) > MAESTRO_SPEED_PROFILE env > DEFAULT.
 *
 * FERRARI equals FAST until the Phase 2 copilot provides event-driven quiescence.
 */
enum class SpeedProfile(val settings: SpeedSettings) {
    DEFAULT(SpeedSettings.DEFAULT),
    FAST(FAST_SETTINGS),
    FERRARI(FAST_SETTINGS);

    companion object {
        const val ENV_VAR = "MAESTRO_SPEED_PROFILE"
        const val CONFIG_KEY = "speedProfile"

        fun resolve(
            configExt: Map<String, Any?>?,
            env: (String) -> String? = System::getenv,
        ): SpeedProfile {
            val raw = (configExt?.get(CONFIG_KEY) as? String) ?: env(ENV_VAR)
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
        }
    }
}
