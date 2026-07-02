package maestro.utils

import com.github.romankh3.image.comparison.ImageComparison
import maestro.Driver
import maestro.MaestroException
import maestro.TreeNode
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
            // A view-hierarchy snapshot can time out here when the app's main thread is
            // blocked (Metro lazily bundling a heavy screen, a long synchronous task). During
            // settle that must read as "not settled yet — keep waiting", never as a hard
            // failure that aborts the flow. safeViewHierarchy returns null on such a timeout;
            // we then continue polling within the budget and hand back the best hierarchy we
            // have when the budget expires.
            var latestHierarchy: ViewHierarchy? = initialHierarchy
            if (timeoutMs != null) {
                val endTime = System.currentTimeMillis() + timeoutMs
                do {
                    val hierarchyAfter = safeViewHierarchy(driver)
                    if (hierarchyAfter == null) {
                        MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
                        continue
                    }
                    if (latestHierarchy == hierarchyAfter) {
                        val isLoading = hierarchyAfter.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                        if (!isLoading) {
                            return hierarchyAfter
                        }
                    }
                    latestHierarchy = hierarchyAfter
                } while (System.currentTimeMillis() < endTime)
            } else {
                repeat(10) {
                    val hierarchyAfter = safeViewHierarchy(driver)
                    if (hierarchyAfter != null) {
                        if (latestHierarchy == hierarchyAfter) {
                            val isLoading = hierarchyAfter.root.attributes.getOrDefault("is-loading", "false").toBoolean()
                            if (!isLoading) {
                                return hierarchyAfter
                            }
                        }
                        latestHierarchy = hierarchyAfter
                    }

                    MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
                }
            }

            return latestHierarchy ?: safeViewHierarchy(driver) ?: ViewHierarchy(TreeNode())
        }

        fun waitUntilScreenIsStatic(
            timeoutMs: Long,
            threshold: Double,
            driver: Driver,
            frameCache: FrameCache? = null,
        ): Boolean {
            val endTime = System.currentTimeMillis() + timeoutMs
            var previous: BufferedImage? = frameCache?.lastFrame
            var attempt = 0

            do {
                val current = tryTakingScreenshot(driver)
                    ?: return false

                val prev = previous
                if (prev != null && prev.width == current.width && prev.height == current.height) {
                    val diff = ImageComparison(prev, current).compareImages().differencePercent
                    if (diff <= threshold) {
                        frameCache?.update(current)
                        return true
                    }
                }
                previous = current
                frameCache?.update(current)

                val delay = MaestroTimer.DEFAULT_BACKOFF_MS.getOrElse(attempt) { MaestroTimer.DEFAULT_BACKOFF_MS.last() }
                attempt++
                if (System.currentTimeMillis() + delay < endTime) {
                    MaestroTimer.sleep(MaestroTimer.Reason.BUFFER, delay)
                }
            } while (System.currentTimeMillis() < endTime)

            return false
        }

        private fun viewHierarchy(driver: Driver): ViewHierarchy {
            return ViewHierarchy.from(driver, false)
        }

        /**
         * A settle-time hierarchy fetch that tolerates a blocked app main thread: returns
         * null (instead of throwing) when the underlying AX snapshot times out, so the
         * settle loop can treat it as "not settled yet" and keep waiting. All other failures
         * propagate unchanged.
         */
        private fun safeViewHierarchy(driver: Driver): ViewHierarchy? {
            return try {
                viewHierarchy(driver)
            } catch (timeout: MaestroException.DriverTimeout) {
                LOGGER.info("View hierarchy snapshot timed out during settle (app main thread busy); treating as not-settled", timeout)
                null
            }
        }
    }
}
