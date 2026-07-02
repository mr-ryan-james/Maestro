package maestro.orchestra

enum class TransitionClass { SCREEN, MINOR, NONE }

/**
 * Default transition class per command type. NONE commands skip post-action settling
 * entirely (budget 0); MINOR use the minor settle budget; SCREEN use the screen budget.
 * Explicit per-command waitToSettleTimeoutMs always wins over these defaults.
 */
object TransitionDefaults {

    fun forCommand(command: Command): TransitionClass = when (command) {
        is InputTextCommand,
        is InputRandomCommand,
        is ReplaceTextCommand,
        is PasteTextCommand,
        is EraseTextCommand,
        -> TransitionClass.NONE

        is LaunchAppCommand,
        is StopAppCommand,
        is KillAppCommand,
        is OpenLinkCommand,
        is BackPressCommand,
        -> TransitionClass.SCREEN

        else -> TransitionClass.MINOR
    }
}
