package maestro.cli.output

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

internal data class OutputBudgetConfig(
    val maxBytes: Long,
)

private data class StdoutProbe(
    val keepAliveHandle: FileOutputStream,
    val seekableChannel: FileChannel?,
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
        val stdoutProbe = probeStdout()
        val installed = installForStreams(
            maxBytes = config.maxBytes,
            stdout = System.out,
            stderr = System.err,
            stdoutSeekableChannel = stdoutProbe?.seekableChannel,
            stdoutHandleToKeepAlive = stdoutProbe?.keepAliveHandle,
        )
        System.setOut(installed.stdout)
        System.setErr(installed.stderr)

        return installed
    }

    internal fun installForStreams(
        maxBytes: Long,
        stdout: PrintStream,
        stderr: PrintStream,
        stdoutSeekableChannel: FileChannel? = null,
        stdoutHandleToKeepAlive: Any? = null,
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
            stdoutSeekableChannel = stdoutSeekableChannel,
            stdoutHandleToKeepAlive = stdoutHandleToKeepAlive,
        ).also { current = it }
    }

    internal fun resetForTests() {
        current = null
    }

    private fun probeStdout(): StdoutProbe? {
        val keepAliveHandle = runCatching { FileOutputStream(FileDescriptor.out) }
            .getOrNull()
            ?: return null

        val seekableChannel = try {
            keepAliveHandle.channel.also { channel ->
                val position = channel.position()
                channel.position(position)
            }
        } catch (_: IOException) {
            null
        }

        return StdoutProbe(
            keepAliveHandle = keepAliveHandle,
            seekableChannel = seekableChannel,
        )
    }
}

internal class InstalledOutputBudget internal constructor(
    internal val maxBytes: Long,
    private val originalStdout: PrintStream,
    originalStderr: PrintStream,
    stdoutSeekableChannel: FileChannel? = null,
    @Suppress("unused")
    private val stdoutHandleToKeepAlive: Any? = null,
) {

    private val lock = ReentrantLock()
    private val stdoutState: StdoutState = if (stdoutSeekableChannel != null) {
        SeekableFileStdoutState(
            delegate = originalStdout,
            channel = stdoutSeekableChannel,
        )
    } else {
        CappedStdoutState(delegate = originalStdout)
    }
    private val stderrState = CappedStreamState(delegate = originalStderr)

    internal val stdout: PrintStream = PrintStream(
        BudgetedOutputStream(
            onWrite = { bytes, off, len -> stdoutState.write(bytes, off, len) },
            onFlush = { stdoutState.flush() },
        ),
        true,
        StandardCharsets.UTF_8,
    )

    internal val stderr: PrintStream = PrintStream(
        BudgetedOutputStream(
            onWrite = { bytes, off, len -> stderrState.write(bytes, off, len) },
            onFlush = { stderrState.flush() },
        ),
        true,
        StandardCharsets.UTF_8,
    )

    internal fun remainingBytes(): Long = lock.withLock { stdoutState.remainingBytes() }

    internal fun writeAllOrNothingToOriginalStdout(bytes: ByteArray): Boolean = lock.withLock {
        stdoutState.writeAllOrNothing(bytes)
    }

    private inner class BudgetedOutputStream(
        private val onWrite: (ByteArray, Int, Int) -> Unit,
        private val onFlush: () -> Unit,
    ) : OutputStream() {

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) {
                return
            }

            lock.withLock {
                onWrite(b, off, len)
            }
        }

        override fun flush() {
            lock.withLock {
                onFlush()
            }
        }

        override fun close() {
            flush()
        }
    }

    private interface StdoutState {
        fun write(bytes: ByteArray, off: Int, len: Int)
        fun writeAllOrNothing(bytes: ByteArray): Boolean
        fun remainingBytes(): Long
        fun flush()
    }

    private open inner class CappedStreamState(
        private val delegate: PrintStream,
    ) {

        protected var forwardedBytes: Long = 0L

        open fun write(bytes: ByteArray, off: Int, len: Int) {
            if (len <= 0 || forwardedBytes >= maxBytes) {
                return
            }

            val allowedLength = min(maxBytes - forwardedBytes, len.toLong()).toInt()
            forwardedBytes += allowedLength.toLong()
            delegate.write(bytes, off, allowedLength)
        }

        open fun remainingBytes(): Long {
            return maxBytes - forwardedBytes
        }

        open fun flush() {
            delegate.flush()
        }
    }

    private inner class CappedStdoutState(
        delegate: PrintStream,
    ) : CappedStreamState(delegate), StdoutState {

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            super.write(bytes, off, len)
        }

        override fun writeAllOrNothing(bytes: ByteArray): Boolean {
            if (bytes.size.toLong() > remainingBytes()) {
                return false
            }

            super.write(bytes, 0, bytes.size)
            super.flush()
            return true
        }

        override fun remainingBytes(): Long {
            return super.remainingBytes()
        }

        override fun flush() {
            super.flush()
        }
    }

    private inner class SeekableFileStdoutState(
        private val delegate: PrintStream,
        private val channel: FileChannel,
    ) : StdoutState {

        private val tailBuffer = TailBuffer(capacity = maxBytes.toInt())
        private var retainedBytes = 0L
        private var tailModeActive = false

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            if (len <= 0) {
                return
            }

            tailBuffer.append(bytes, off, len)

            if (!tailModeActive && retainedBytes + len.toLong() <= maxBytes) {
                delegate.write(bytes, off, len)
                retainedBytes += len.toLong()
                return
            }

            tailModeActive = true
            rewriteFileFromTail()
        }

        override fun writeAllOrNothing(bytes: ByteArray): Boolean {
            if (bytes.size.toLong() > remainingBytes()) {
                return false
            }

            tailBuffer.append(bytes, 0, bytes.size)
            delegate.write(bytes)
            delegate.flush()
            retainedBytes += bytes.size.toLong()
            return true
        }

        override fun remainingBytes(): Long {
            return maxBytes - retainedBytes
        }

        override fun flush() {
            delegate.flush()
        }

        private fun rewriteFileFromTail() {
            val tailBytes = tailBuffer.toByteArray()
            delegate.flush()
            channel.position(0L)

            var offset = 0
            while (offset < tailBytes.size) {
                offset += channel.write(
                    ByteBuffer.wrap(tailBytes, offset, tailBytes.size - offset)
                )
            }

            channel.truncate(tailBytes.size.toLong())
            channel.position(tailBytes.size.toLong())
            retainedBytes = tailBytes.size.toLong()
        }
    }
}

private class TailBuffer(
    private val capacity: Int,
) {

    private val bytes = ByteArray(capacity)
    private var size = 0
    private var writeIndex = 0

    fun append(source: ByteArray, off: Int, len: Int) {
        if (len <= 0) {
            return
        }

        if (len >= capacity) {
            val start = off + len - capacity
            System.arraycopy(source, start, bytes, 0, capacity)
            size = capacity
            writeIndex = 0
            return
        }

        val firstCopy = min(len, capacity - writeIndex)
        System.arraycopy(source, off, bytes, writeIndex, firstCopy)

        val remaining = len - firstCopy
        if (remaining > 0) {
            System.arraycopy(source, off + firstCopy, bytes, 0, remaining)
        }

        writeIndex = (writeIndex + len) % capacity
        size = min(capacity, size + len)
    }

    fun toByteArray(): ByteArray {
        if (size == 0) {
            return byteArrayOf()
        }

        if (size < capacity) {
            return bytes.copyOf(size)
        }

        val ordered = ByteArray(capacity)
        val tailLength = capacity - writeIndex
        System.arraycopy(bytes, writeIndex, ordered, 0, tailLength)
        if (writeIndex > 0) {
            System.arraycopy(bytes, 0, ordered, tailLength, writeIndex)
        }
        return ordered
    }
}
