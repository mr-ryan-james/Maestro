package maestro.cli.output

import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

internal data class OutputBudgetConfig(
    val maxBytes: Long,
)

internal object OutputBudget {

    internal const val ENV_VAR = "MAESTRO_OUTPUT_CAP_BYTES"
    internal const val DEFAULT_MAX_BYTES = 10_485_760L
    internal const val ABSOLUTE_MAX_BYTES = 104_857_600L

    @Volatile
    private var current: InstalledOutputBudget? = null

    internal fun configFromEnvironment(env: Map<String, String> = System.getenv()): OutputBudgetConfig {
        val configured = env[ENV_VAR]?.toLongOrNull()
        val maxBytes = when {
            configured == null -> DEFAULT_MAX_BYTES
            configured <= 0L -> DEFAULT_MAX_BYTES
            configured > ABSOLUTE_MAX_BYTES -> ABSOLUTE_MAX_BYTES
            else -> configured
        }

        return OutputBudgetConfig(maxBytes = maxBytes)
    }

    internal fun currentOrNull(): InstalledOutputBudget? = current

    internal fun installIfNeeded(
        protocolMode: Boolean,
        env: Map<String, String> = System.getenv(),
    ): InstalledOutputBudget? {
        if (protocolMode) {
            return null
        }

        current?.let { return it }

        val config = configFromEnvironment(env)
        val installed = installForStreams(
            maxBytes = config.maxBytes,
            stdout = System.out,
            stderr = System.err,
        )
        System.setOut(installed.stdout)
        System.setErr(installed.stderr)

        return installed
    }

    internal fun installForStreams(
        maxBytes: Long,
        stdout: PrintStream,
        stderr: PrintStream,
    ): InstalledOutputBudget {
        val sanitizedMaxBytes = when {
            maxBytes <= 0L -> DEFAULT_MAX_BYTES
            maxBytes > ABSOLUTE_MAX_BYTES -> ABSOLUTE_MAX_BYTES
            else -> maxBytes
        }

        return InstalledOutputBudget(
            maxBytes = sanitizedMaxBytes,
            originalStdout = stdout,
            originalStderr = stderr,
        ).also { current = it }
    }

    internal fun resetForTests() {
        current = null
    }
}

internal class InstalledOutputBudget internal constructor(
    internal val maxBytes: Long,
    private val originalStdout: PrintStream,
    originalStderr: PrintStream,
) {

    private val lock = ReentrantLock()
    private var remainingBytes = maxBytes

    internal val stdout: PrintStream = PrintStream(
        BudgetedOutputStream(originalStdout),
        true,
        StandardCharsets.UTF_8,
    )

    internal val stderr: PrintStream = PrintStream(
        BudgetedOutputStream(originalStderr),
        true,
        StandardCharsets.UTF_8,
    )

    internal fun remainingBytes(): Long = lock.withLock { remainingBytes }

    internal fun writeAllOrNothingToOriginalStdout(bytes: ByteArray): Boolean = lock.withLock {
        if (bytes.size.toLong() > remainingBytes) {
            return false
        }

        remainingBytes -= bytes.size.toLong()
        originalStdout.write(bytes)
        originalStdout.flush()

        true
    }

    private inner class BudgetedOutputStream(
        private val delegate: PrintStream,
    ) : OutputStream() {

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) {
                return
            }

            lock.withLock {
                if (remainingBytes <= 0) {
                    return
                }

                val allowedLength = min(remainingBytes, len.toLong()).toInt()
                remainingBytes -= allowedLength.toLong()
                delegate.write(b, off, allowedLength)
            }
        }

        override fun flush() {
            lock.withLock {
                delegate.flush()
            }
        }

        override fun close() {
            flush()
        }
    }
}
