package maestro.utils

import com.google.common.truth.Truth.assertThat
import maestro.FakeDriver
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

private fun solidPng(color: Color): ByteArray {
    val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = color
    g.fillRect(0, 0, 20, 20)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "PNG", out)
    return out.toByteArray()
}

/** Serves the queued frames in order; repeats the last frame when the queue empties. */
private class FakeScreenshotDriver(frames: List<ByteArray>) : FakeDriver() {
    private val queue = frames.toMutableList()
    var captures = 0
        private set

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        captures++
        val bytes = if (queue.size > 1) queue.removeAt(0) else queue.first()
        val buffer = Buffer().write(bytes)
        out.write(buffer, buffer.size)
    }
}

/** Alternates between two frames forever — a screen that never settles. */
private class AlternatingScreenshotDriver : FakeDriver() {
    private var flip = false

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        flip = !flip
        val bytes = solidPng(if (flip) Color.RED else Color.BLUE)
        val buffer = Buffer().write(bytes)
        out.write(buffer, buffer.size)
    }
}

class ScreenSettleTest {

    private lateinit var originalSleep: (MaestroTimer.Reason, Long) -> Unit

    @BeforeEach
    fun setUp() {
        originalSleep = MaestroTimer.sleep
        MaestroTimer.setTimerFunc { _, _ -> } // no real sleeping in tests
    }

    @AfterEach
    fun tearDown() {
        MaestroTimer.setTimerFunc(originalSleep)
    }

    @Test
    fun `static screen with empty cache needs two captures`() {
        val red = solidPng(Color.RED)
        val driver = FakeScreenshotDriver(listOf(red, red))

        val isStatic = ScreenshotUtils.waitUntilScreenIsStatic(1000L, 0.005, driver, FrameCache())

        assertThat(isStatic).isTrue()
        assertThat(driver.captures).isEqualTo(2)
    }

    @Test
    fun `static screen with primed cache needs one capture`() {
        val red = solidPng(Color.RED)
        val cache = FrameCache()
        cache.update(ImageIO.read(red.inputStream()))
        val driver = FakeScreenshotDriver(listOf(red))

        val isStatic = ScreenshotUtils.waitUntilScreenIsStatic(1000L, 0.005, driver, cache)

        assertThat(isStatic).isTrue()
        assertThat(driver.captures).isEqualTo(1)
    }

    @Test
    fun `animating screen settles once frames repeat`() {
        val red = solidPng(Color.RED)
        val blue = solidPng(Color.BLUE)
        val green = solidPng(Color.GREEN)
        // red -> blue -> green -> green : settles on the 4th capture
        val driver = FakeScreenshotDriver(listOf(red, blue, green, green))

        val isStatic = ScreenshotUtils.waitUntilScreenIsStatic(5000L, 0.005, driver, FrameCache())

        assertThat(isStatic).isTrue()
        assertThat(driver.captures).isEqualTo(4)
    }

    @Test
    fun `screen that never settles returns false at timeout`() {
        val driver = AlternatingScreenshotDriver()

        val isStatic = ScreenshotUtils.waitUntilScreenIsStatic(100L, 0.005, driver, FrameCache())

        assertThat(isStatic).isFalse()
    }
}
