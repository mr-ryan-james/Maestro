package maestro.drivers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client for the in-app copilot's event-driven quiescence socket (see `copilot-ios`).
 *
 * When the copilot dylib is injected into the app under test (ferrari profile /
 * `MAESTRO_COPILOT=1`), it binds a localhost socket and answers `awaitQuiescence`, gating
 * on real render-readiness (run loop drained, layout/display epochs stable, network idle,
 * content actually on screen, N stable frames). That replaces the black-box screenshot/AX
 * settle for the case that matters most on RN/Expo: a blank-but-idle screen while a lazy
 * bundle is still mounting.
 *
 * Fails fast and returns null whenever the copilot is disabled or unreachable, so callers
 * transparently fall back to the existing settle path.
 */
class QuiescenceService(
    private val port: Int = resolvePort(),
    private val enabled: Boolean = resolveEnabled(),
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(QuiescenceService::class.java)

    /**
     * Blocks until the app reports quiescent or [timeoutMs] elapses.
     * @return true if quiescent, false on copilot-side timeout, or null when the copilot is
     *   disabled/unreachable (caller should fall back to the black-box settle).
     */
    fun awaitQuiescence(timeoutMs: Long, transitionClass: String = "screen"): Boolean? {
        if (!enabled) return null
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = (timeoutMs + SOCKET_READ_MARGIN_MS).toInt()
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                val request = mapper.writeValueAsBytes(
                    mapOf(
                        "cmd" to "awaitQuiescence",
                        "transitionClass" to transitionClass,
                        "timeoutMs" to timeoutMs.toInt(),
                    )
                )
                writeFrame(out, request)

                val responseBytes = readFrame(input) ?: return@use null
                val response = mapper.readValue(responseBytes, Map::class.java)
                val quiescent = response["quiescent"] as? Boolean ?: false
                logger.info(
                    "Copilot quiescence: quiescent={} phase={} frames={}",
                    quiescent, response["phase"], response["framesObserved"]
                )
                quiescent
            }
        }.getOrElse { e ->
            // Not injected / not reachable / protocol hiccup — fall back silently.
            logger.debug("Copilot unreachable on 127.0.0.1:$port ({}); falling back to black-box settle", e.message)
            null
        }
    }

    private fun writeFrame(out: DataOutputStream, body: ByteArray) {
        out.writeInt(body.size) // 4-byte big-endian length prefix
        out.write(body)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray? {
        val length = input.readInt()
        if (length <= 0 || length > MAX_FRAME_BYTES) return null
        val buffer = ByteArray(length)
        input.readFully(buffer)
        return buffer
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 300
        private const val SOCKET_READ_MARGIN_MS = 5_000L
        private const val MAX_FRAME_BYTES = 1_000_000
        private const val DEFAULT_COPILOT_PORT = 7113

        private fun resolvePort(): Int =
            System.getenv("MAESTRO_COPILOT_PORT")?.toIntOrNull() ?: DEFAULT_COPILOT_PORT

        private fun resolveEnabled(): Boolean {
            when (System.getenv("MAESTRO_COPILOT")?.lowercase()) {
                "0", "false", "off" -> return false
                "1", "true", "on" -> return true
            }
            return System.getenv("MAESTRO_SPEED_PROFILE").equals("ferrari", ignoreCase = true)
        }
    }
}
