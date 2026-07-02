package maestro.utils

object MaestroTimer {

    var sleep: (Reason, Long) -> Unit = { _, ms -> Thread.sleep(ms) }
        private set

    fun setTimerFunc(sleep: (Reason, Long) -> Unit) {
        this.sleep = sleep
    }

    val DEFAULT_BACKOFF_MS: List<Long> = listOf(16L, 50L, 100L)

    fun <T> withTimeout(
        timeoutMs: Long,
        backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
        block: () -> T?,
    ): T? {
        val endTime = System.currentTimeMillis() + timeoutMs
        var attempt = 0

        do {
            val result = block()

            if (result != null) {
                return result
            }
            val delay = backoffMs.getOrElse(attempt) { backoffMs.last() }
            attempt++
            if (System.currentTimeMillis() + delay < endTime) {
                sleep(Reason.BUFFER, delay)
            }
        } while (System.currentTimeMillis() < endTime)

        return null
    }

    fun retryUntilTrue(
        timeoutMs: Long,
        delayMs: Long? = null,
        onException: (Exception) -> Unit = {},
        block: () -> Boolean,
    ): Boolean {
        val endTime = System.currentTimeMillis() + timeoutMs
        var attempt = 0

        do {
            try {
                if (block()) {
                    return true
                }
            } catch (ignored: Exception) {
                onException(ignored)
            }
            val delay = delayMs ?: DEFAULT_BACKOFF_MS.getOrElse(attempt) { DEFAULT_BACKOFF_MS.last() }
            attempt++
            if (System.currentTimeMillis() + delay < endTime) {
                sleep(Reason.BUFFER, delay)
            }
        } while (System.currentTimeMillis() < endTime)

        return false
    }

    enum class Reason {
        WAIT_UNTIL_VISIBLE,
        WAIT_TO_SETTLE,
        BUFFER,
    }

}
