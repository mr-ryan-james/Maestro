package maestro

import com.google.common.truth.Truth.assertThat
import maestro.settle.SpeedSettings
import org.junit.jupiter.api.Test

private class FakeSettleDriver : FakeDriver() {
    var settleCalls = 0
        private set

    private fun tree(): TreeNode = TreeNode(
        attributes = mutableMapOf(
            "text" to "OK",
            "bounds" to "[0,0][100,100]",
        ),
    )

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode = tree()

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy {
        settleCalls++
        return ViewHierarchy(tree())
    }

    override fun tap(point: Point) {
        // no-op
    }

    // Route performTap through the hierarchy-based path (no screenshots needed).
    override fun capabilities(): List<Capability> = listOf(Capability.FAST_HIERARCHY)
}

class QuiescenceEpochTest {

    private fun maestroWith(driver: FakeSettleDriver): Maestro =
        Maestro.ios(driver, openDriver = false)

    private fun element(): UiElement =
        UiElement(
            treeNode = TreeNode(attributes = mutableMapOf("text" to "OK", "bounds" to "[0,0][100,100]")),
            bounds = Bounds(0, 0, 100, 100),
        )

    @Test
    fun `waitForAppToSettle with zero timeout does not hit the driver`() {
        val driver = FakeSettleDriver()
        val maestro = maestroWith(driver)

        maestro.waitForAppToSettle(initialHierarchy = null, appId = null, waitToSettleTimeoutMs = 0)

        assertThat(driver.settleCalls).isEqualTo(0)
    }

    @Test
    fun `pre-tap settle is skipped when no action occurred since last settle`() {
        val driver = FakeSettleDriver()
        val maestro = maestroWith(driver)
        maestro.speedSettings = SpeedSettings.DEFAULT.copy(skipPreTapSettle = true)

        val primed = maestro.waitForAppToSettle()!! // primes settledEpoch/settledHierarchy
        assertThat(driver.settleCalls).isEqualTo(1)

        maestro.tap(
            element = element(),
            initialHierarchy = primed,
            retryIfNoChange = false,
            waitUntilVisible = false,
        )

        // Pre-tap settle skipped (fresh epoch) — only the post-tap settle runs.
        assertThat(driver.settleCalls).isEqualTo(2)
    }

    @Test
    fun `pre-tap settle runs when skip is disabled`() {
        val driver = FakeSettleDriver()
        val maestro = maestroWith(driver)
        // speedSettings stays DEFAULT: skipPreTapSettle = false

        val primed = maestro.waitForAppToSettle()!!
        assertThat(driver.settleCalls).isEqualTo(1)

        maestro.tap(
            element = element(),
            initialHierarchy = primed,
            retryIfNoChange = false,
            waitUntilVisible = false,
        )

        // Pre-tap settle AND post-tap settle both run.
        assertThat(driver.settleCalls).isEqualTo(3)
    }
}
