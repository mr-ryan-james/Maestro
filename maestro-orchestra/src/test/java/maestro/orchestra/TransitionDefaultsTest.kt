package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.SwipeDirection
import org.junit.jupiter.api.Test

class TransitionDefaultsTest {

    @Test
    fun `text entry commands are NONE`() {
        assertThat(TransitionDefaults.forCommand(InputTextCommand(text = "hi")))
            .isEqualTo(TransitionClass.NONE)
        assertThat(TransitionDefaults.forCommand(EraseTextCommand(charactersToErase = 5)))
            .isEqualTo(TransitionClass.NONE)
    }

    @Test
    fun `app lifecycle and navigation commands are SCREEN`() {
        assertThat(TransitionDefaults.forCommand(LaunchAppCommand(appId = "com.x")))
            .isEqualTo(TransitionClass.SCREEN)
        assertThat(TransitionDefaults.forCommand(BackPressCommand()))
            .isEqualTo(TransitionClass.SCREEN)
    }

    @Test
    fun `taps and swipes are MINOR`() {
        assertThat(TransitionDefaults.forCommand(TapOnElementCommand(selector = ElementSelector(textRegex = "OK"))))
            .isEqualTo(TransitionClass.MINOR)
        assertThat(TransitionDefaults.forCommand(SwipeCommand(direction = SwipeDirection.UP, duration = 400L)))
            .isEqualTo(TransitionClass.MINOR)
    }
}
