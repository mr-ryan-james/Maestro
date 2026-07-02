package maestro.utils

import java.awt.image.BufferedImage

/**
 * Holds the most recent screenshot frame for a driver session so settle checks
 * can diff one fresh capture against the previous frame instead of always
 * capturing two fresh screenshots.
 */
class FrameCache {
    var lastFrame: BufferedImage? = null
        private set

    fun update(frame: BufferedImage) {
        lastFrame = frame
    }

    fun clear() {
        lastFrame = null
    }
}
