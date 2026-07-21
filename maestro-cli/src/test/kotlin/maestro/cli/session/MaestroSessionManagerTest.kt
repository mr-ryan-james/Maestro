package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MaestroSessionManagerTest {

    @Test
    fun `blocked lease cleanup does not run on or block the dispatching thread`() {
        val dispatchingThread = Thread.currentThread()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val cleanupThread = AtomicReference<Thread>()

        MaestroSessionManager.dispatchLeaseLoss {
            cleanupThread.set(Thread.currentThread())
            firstStarted.countDown()
            releaseFirst.await()
        }

        try {
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue()

            MaestroSessionManager.dispatchLeaseLoss {
                secondFinished.countDown()
            }

            assertThat(secondFinished.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(cleanupThread.get()).isNotSameInstanceAs(dispatchingThread)
        } finally {
            releaseFirst.countDown()
        }
    }
}
