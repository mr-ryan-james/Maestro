import maestro.utils.MaestroTimer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MaestroTimerTest {

    private val recordedSleeps = mutableListOf<Long>()

    @BeforeEach
    fun setUp() {
        MaestroTimer.setTimerFunc { _, ms -> Thread.sleep(ms) }
    }

    @AfterEach
    fun tearDown() {
        MaestroTimer.setTimerFunc { _, ms -> Thread.sleep(ms) }
    }

    private fun recordSleepsWithoutSleeping() {
        recordedSleeps.clear()
        MaestroTimer.setTimerFunc { _, ms -> recordedSleeps += ms }
    }

    @Test
    fun `withTimeout should return result within timeout`() {
        val result = MaestroTimer.withTimeout(1000) {
            "Success"
        }

        assertEquals("Success", result)
    }

    @Test
    fun `withTimeout should return null if body is null`() {
        val result = MaestroTimer.withTimeout(1000) {
            null
        }

        assertNull(result)
    }

    @Test
    fun `retryUntilTrue should return true if block succeeds within timeout`() {
        val result = MaestroTimer.retryUntilTrue(1000) {
            true
        }

        assertTrue(result)
    }

    @Test
    fun `retryUntilTrue should return false if block fails within timeout`() {
        val result = MaestroTimer.retryUntilTrue(100) {
            false
        }

        assertFalse(result)
    }

    @Test
    fun `retryUntilTrue should handle exceptions and continue retrying`() {
        var attempts = 0
        val result = MaestroTimer.retryUntilTrue(1000, 100, { }) {
            attempts++
            if (attempts < 3) throw Exception("Test exception")
            true
        }

        assertTrue(result)
        assertEquals(3, attempts)
    }

    @Test
    fun `withTimeout sleeps with adaptive backoff between failed attempts`() {
        recordSleepsWithoutSleeping()
        var calls = 0
        val result = MaestroTimer.withTimeout(200L) {
            calls++
            if (calls == 4) "found" else null
        }
        assertEquals("found", result)
        // 3 failed attempts -> 3 sleeps: 16, 50, 100
        assertEquals(listOf(16L, 50L, 100L), recordedSleeps)
    }

    @Test
    fun `withTimeout does not sleep after a successful attempt`() {
        recordSleepsWithoutSleeping()
        val result = MaestroTimer.withTimeout(200L) { "immediate" }
        assertEquals("immediate", result)
        assertTrue(recordedSleeps.isEmpty())
    }

    @Test
    fun `withTimeout backoff plateaus at the last delay`() {
        recordSleepsWithoutSleeping()
        var calls = 0
        // Generous deadline: the impl skips sleeps that would overshoot the deadline,
        // so plateau behavior is only observable when the budget comfortably covers it.
        MaestroTimer.withTimeout(1000L) {
            calls++
            if (calls >= 6) "done" else null
        }
        assertEquals(listOf(16L, 50L, 100L, 100L, 100L), recordedSleeps)
    }

    @Test
    fun `retryUntilTrue does not sleep before the first attempt`() {
        recordSleepsWithoutSleeping()
        val ok = MaestroTimer.retryUntilTrue(100L) { true }
        assertTrue(ok)
        assertTrue(recordedSleeps.isEmpty())
    }

    @Test
    fun `retryUntilTrue with explicit delayMs keeps fixed delay after failures`() {
        recordSleepsWithoutSleeping()
        var calls = 0
        MaestroTimer.retryUntilTrue(100L, delayMs = 30L) {
            calls++
            calls >= 3
        }
        assertEquals(listOf(30L, 30L), recordedSleeps)
    }

    @Test
    fun `setTimerFunc should change the sleep function`() {
        var sleepCalled = false
        MaestroTimer.setTimerFunc { _, _ -> sleepCalled = true }
        MaestroTimer.sleep(MaestroTimer.Reason.BUFFER, 100)

        assertTrue(sleepCalled)
    }
}