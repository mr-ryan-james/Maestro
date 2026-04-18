package maestro.utils

import com.github.romankh3.image.comparison.ImageComparison
import maestro.Driver
import maestro.ViewHierarchy
import okio.Buffer
import okio.Sink
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class ScreenshotUtils {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(ScreenshotUtils::class.java)

        const val DEFAULT_SCREENSHOT_MAX_DIM = 2000

        /**
         * Resampling cap applied after every PNG screenshot write. Default is
         * 2000px longest edge (harness-safe for multi-image AI consumers).
         * Override with env `MAESTRO_SCREENSHOT_MAX_DIM`:
         *   - positive integer raises the cap
         *   - `0` disables resizing entirely
         */
        fun effectiveMaxDim(override: Int? = null): Int {
            if (override != null) return override
            val env = System.getenv("MAESTRO_SCREENSHOT_MAX_DIM")?.toIntOrNull()
            return env ?: DEFAULT_SCREENSHOT_MAX_DIM
        }

        fun resizeIfNeeded(file: File, maxDim: Int = effectiveMaxDim()) {
            if (maxDim <= 0) return
            try {
                val image = ImageIO.read(file) ?: return
                val scaled = resizeImage(image, maxDim) ?: return
                ImageIO.write(scaled, "PNG", file)
            } catch (e: Exception) {
                LOGGER.warn("resizeIfNeeded failed for ${file.absolutePath}", e)
            }
        }

        fun resizeBytesIfNeeded(bytes: ByteArray, maxDim: Int = effectiveMaxDim()): ByteArray {
            if (maxDim <= 0) return bytes
            return try {
                val image = ImageIO.read(bytes.inputStream()) ?: return bytes
                val scaled = resizeImage(image, maxDim) ?: return bytes
                val out = java.io.ByteArrayOutputStream()
                ImageIO.write(scaled, "PNG", out)
                out.toByteArray()
            } catch (e: Exception) {
                LOGGER.warn("resizeBytesIfNeeded failed", e)
                bytes
            }
        }

        /**
         * Returns a downscaled copy of [image] whose longest edge fits [maxDim],
         * or `null` when the image is already within the cap (caller should treat
         * that as a no-op).
         */
        private fun resizeImage(image: BufferedImage, maxDim: Int): BufferedImage? {
            val longest = maxOf(image.width, image.height)
            if (longest <= maxDim) return null
            val scale = maxDim.toDouble() / longest.toDouble()
            val targetW = (image.width * scale).toInt().coerceAtLeast(1)
            val targetH = (image.height * scale).toInt().coerceAtLeast(1)
            // TYPE_INT_RGB is intentional: screenshot PNGs are opaque in practice, and
            // dropping any incidental alpha keeps the rewritten PNG smaller.
            val scaled = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
            val g = scaled.createGraphics()
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(image, 0, 0, targetW, targetH, null)
            g.dispose()
            return scaled
        }

        fun takeScreenshot(out: Sink, compressed: Boolean, driver: Driver) {
            LOGGER.trace("Taking screenshot to output sink")

            driver.takeScreenshot(out, compressed)
        }

        fun takeScreenshot(compressed: Boolean, driver: Driver): ByteArray {
            LOGGER.trace("Taking screenshot to byte array")

            val buffer = Buffer()
            takeScreenshot(buffer, compressed, driver)

            return buffer.readByteArray()
        }

        fun tryTakingScreenshot(driver: Driver) = try {
            ImageIO.read(takeScreenshot(true, driver).inputStream())
        } catch (e: Exception) {
            LOGGER.warn("Failed to take screenshot", e)
            null
        }

        fun waitForAppToSettle(
            initialHierarchy: ViewHierarchy?,
            driver: Driver,
            timeoutMs: Int? = null
        ): ViewHierarchy {
            var latestHierarchy: ViewHierarchy
            if (timeoutMs != null) {
                val endTime = System.currentTimeMillis() + timeoutMs
                latestHierarchy = initialHierarchy ?: viewHierarchy(driver)
                do {
                    val hierarchyAfter = viewHierarchy(driver)
                    if (latestHierarchy == hierarchyAfter) {
                        val isLoading = latestHierarchy.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                        if (!isLoading) {
                            return hierarchyAfter
                        }
                    }
                    latestHierarchy = hierarchyAfter
                } while (System.currentTimeMillis() < endTime)
            } else {
                latestHierarchy = initialHierarchy ?: viewHierarchy(driver)
                repeat(10) {
                    val hierarchyAfter = viewHierarchy(driver)
                    if (latestHierarchy == hierarchyAfter) {
                        val isLoading = latestHierarchy.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                        if (!isLoading) {
                            return hierarchyAfter
                        }
                    }
                    latestHierarchy = hierarchyAfter

                    MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
                }
            }

            return latestHierarchy
        }

        fun waitUntilScreenIsStatic(timeoutMs: Long, threshold: Double, driver: Driver): Boolean {
            return MaestroTimer.retryUntilTrue(timeoutMs) {
                val startScreenshot: BufferedImage? = tryTakingScreenshot(driver)
                val endScreenshot: BufferedImage? = tryTakingScreenshot(driver)

                if (startScreenshot != null &&
                    endScreenshot != null &&
                    startScreenshot.width == endScreenshot.width &&
                    startScreenshot.height == endScreenshot.height
                ) {
                    val imageDiff = ImageComparison(
                        startScreenshot,
                        endScreenshot
                    ).compareImages().differencePercent

                    return@retryUntilTrue imageDiff <= threshold
                }

                return@retryUntilTrue false
            }
        }

        private fun viewHierarchy(driver: Driver): ViewHierarchy {
            return ViewHierarchy.from(driver, false)
        }
    }
}
