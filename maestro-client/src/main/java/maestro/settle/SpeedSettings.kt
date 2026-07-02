package maestro.settle

/**
 * Timing knobs for the execution engine. Null settle budgets mean "driver default"
 * (preserves upstream behavior). Policy (which profile maps to which values) lives
 * in maestro-orchestra-models SpeedProfile; this class is pure mechanism.
 */
data class SpeedSettings(
    /** Settle budget after minor-transition actions (taps, swipes). Null = driver default. */
    val minorSettleBudgetMs: Int? = null,
    /** Settle budget after screen-transition actions (launch, back, openLink). Null = driver default. */
    val screenSettleBudgetMs: Int? = null,
    val lookupTimeoutMs: Long = 17000L,
    val optionalLookupTimeoutMs: Long = 7000L,
    /** Skip the pre-tap settle when no action has occurred since the last settled hierarchy. */
    val skipPreTapSettle: Boolean = false,
    /** Disable OS/system animations for the session (Android system scales; iOS via copilot in Phase 2). */
    val disableAnimations: Boolean = false,
) {
    companion object {
        val DEFAULT = SpeedSettings()
    }
}
