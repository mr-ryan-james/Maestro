package maestro.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

class ScreenshotUtilsResizeTest {

    @Test
    fun `resizeIfNeeded downsamples wide PNG to fit within max dimension`(@TempDir tmp: Path) {
        val src = tmp.resolve("wide.png").toFile()
        writeSolidPng(src, width = 3000, height = 1500)

        ScreenshotUtils.resizeIfNeeded(src, maxDim = 2000)

        val resized = ImageIO.read(src)
        assertEquals(2000, resized.width)
        assertEquals(1000, resized.height)
    }

    @Test
    fun `resizeIfNeeded downsamples tall PNG to fit within max dimension`(@TempDir tmp: Path) {
        val src = tmp.resolve("tall.png").toFile()
        writeSolidPng(src, width = 1290, height = 2796)

        ScreenshotUtils.resizeIfNeeded(src, maxDim = 2000)

        val resized = ImageIO.read(src)
        assertEquals(2000, resized.height)
        assertTrue(resized.width in 920..930) // 1290 * 2000 / 2796 ≈ 923
    }

    @Test
    fun `resizeIfNeeded no-op when image is already within max dimension`(@TempDir tmp: Path) {
        val src = tmp.resolve("small.png").toFile()
        writeSolidPng(src, width = 1500, height = 1000)
        val originalBytes = src.readBytes()

        ScreenshotUtils.resizeIfNeeded(src, maxDim = 2000)

        assertTrue(src.readBytes().contentEquals(originalBytes))
    }

    @Test
    fun `resizeIfNeeded with maxDim zero is a no-op`(@TempDir tmp: Path) {
        val src = tmp.resolve("wide.png").toFile()
        writeSolidPng(src, width = 3000, height = 1500)
        val originalBytes = src.readBytes()

        ScreenshotUtils.resizeIfNeeded(src, maxDim = 0)

        assertTrue(src.readBytes().contentEquals(originalBytes))
    }

    @Test
    fun `resizeIfNeeded tolerates corrupt file without throwing`(@TempDir tmp: Path) {
        val src = tmp.resolve("corrupt.png").toFile()
        src.writeText("not a png")

        // Must not throw; log + pass through.
        ScreenshotUtils.resizeIfNeeded(src, maxDim = 2000)
    }

    @Test
    fun `resizeBytesIfNeeded downsamples wide PNG bytes`() {
        val original = solidPngBytes(width = 3000, height = 1500)

        val resizedBytes = ScreenshotUtils.resizeBytesIfNeeded(original, maxDim = 2000)

        val resized = ImageIO.read(resizedBytes.inputStream())
        assertEquals(2000, resized.width)
        assertEquals(1000, resized.height)
    }

    @Test
    fun `resizeBytesIfNeeded is a no-op when within max dimension`() {
        val original = solidPngBytes(width = 1500, height = 1000)

        val resizedBytes = ScreenshotUtils.resizeBytesIfNeeded(original, maxDim = 2000)

        assertTrue(resizedBytes.contentEquals(original))
    }

    @Test
    fun `resizeBytesIfNeeded returns original bytes on corrupt input`() {
        val original = "not a png".toByteArray()

        val resizedBytes = ScreenshotUtils.resizeBytesIfNeeded(original, maxDim = 2000)

        assertTrue(resizedBytes.contentEquals(original))
    }

    private fun writeSolidPng(target: File, width: Int, height: Int) {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color.GRAY
        g.fillRect(0, 0, width, height)
        g.dispose()
        ImageIO.write(img, "PNG", target)
    }

    private fun solidPngBytes(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color.GRAY
        g.fillRect(0, 0, width, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }
}
