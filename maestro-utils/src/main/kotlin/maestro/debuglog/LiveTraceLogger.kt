package maestro.debuglog

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object LiveTraceLogger {

    private val lock = Any()
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
        .withZone(ZoneOffset.UTC)

    @Volatile
    private var tracePath: Path? = null

    @Volatile
    private var statusPath: Path? = null

    @Volatile
    private var currentFlow: String? = null

    @Volatile
    private var currentCommand: String? = null

    @Volatile
    private var currentStatus: String = "IDLE"

    @Volatile
    private var currentDetail: String = "Live trace logger not installed"

    @Volatile
    private var currentSinceMs: Long = System.currentTimeMillis()

    fun install(traceFile: Path, statusFile: Path) {
        synchronized(lock) {
            traceFile.parent?.let { Files.createDirectories(it) }
            statusFile.parent?.let { Files.createDirectories(it) }

            tracePath = traceFile
            statusPath = statusFile
            currentFlow = null
            currentCommand = null
            currentStatus = "IDLE"
            currentDetail = "Live trace logger installed"
            currentSinceMs = System.currentTimeMillis()

            Files.writeString(traceFile, "", CREATE, TRUNCATE_EXISTING, WRITE)
            writeStatusLocked()
            appendLocked(
                event = "LOGGER_READY",
                detail = "trace=${traceFile.toAbsolutePath()} status=${statusFile.toAbsolutePath()}",
            )
        }
    }

    fun getTracePath(): Path? = tracePath

    fun getStatusPath(): Path? = statusPath

    fun flowStarted(flowName: String, flowFile: String? = null) {
        synchronized(lock) {
            currentFlow = flowName
            currentCommand = null
            setStatusLocked(
                status = "FLOW_RUNNING",
                detail = "Flow started${flowFile?.let { " file=$it" }.orEmpty()}",
            )
            appendLocked(
                event = "FLOW_START",
                flow = flowName,
                detail = flowFile?.let { "file=$it" } ?: "Flow started",
            )
        }
    }

    fun flowCompleted(flowName: String, success: Boolean, detail: String? = null) {
        synchronized(lock) {
            currentFlow = flowName
            currentCommand = null
            setStatusLocked(
                status = if (success) "FLOW_COMPLETED" else "FLOW_FAILED",
                detail = detail ?: if (success) "Flow completed" else "Flow failed",
            )
            appendLocked(
                event = if (success) "FLOW_COMPLETE" else "FLOW_FAILED",
                flow = flowName,
                detail = detail,
            )
        }
    }

    fun commandStarted(flowName: String?, command: String, index: Int? = null) {
        synchronized(lock) {
            currentFlow = flowName ?: currentFlow
            currentCommand = command
            setStatusLocked(
                status = "COMMAND_RUNNING",
                detail = "Command running${index?.let { " index=$it" }.orEmpty()}",
            )
            appendLocked(
                event = "COMMAND_START",
                flow = currentFlow,
                command = command,
                detail = index?.let { "index=$it" } ?: "Command started",
            )
        }
    }

    fun commandFinished(
        flowName: String?,
        command: String,
        outcome: String,
        durationMs: Long? = null,
        detail: String? = null,
    ) {
        synchronized(lock) {
            currentFlow = flowName ?: currentFlow
            currentCommand = command
            setStatusLocked(
                status = "COMMAND_$outcome",
                detail = detail ?: buildString {
                    append("Command $outcome")
                    durationMs?.let { append(" durationMs=$it") }
                },
            )
            appendLocked(
                event = "COMMAND_$outcome",
                flow = currentFlow,
                command = command,
                detail = buildString {
                    detail?.let { append(it) }
                    durationMs?.let {
                        if (isNotEmpty()) append(' ')
                        append("durationMs=$it")
                    }
                }.ifBlank { null },
            )
        }
    }

    fun conditionStarted(kind: String, description: String, timeoutMs: Long) {
        synchronized(lock) {
            setStatusLocked(
                status = "WAITING_FOR_CONDITION",
                detail = "kind=$kind timeoutMs=$timeoutMs selector=$description",
            )
            appendLocked(
                event = "CONDITION_START",
                detail = "kind=$kind timeoutMs=$timeoutMs selector=$description",
            )
        }
    }

    fun conditionFinished(
        kind: String,
        description: String,
        timeoutMs: Long,
        matched: Boolean,
        durationMs: Long,
        detail: String? = null,
    ) {
        synchronized(lock) {
            setStatusLocked(
                status = "COMMAND_RUNNING",
                detail = detail ?: "Condition completed kind=$kind matched=$matched durationMs=$durationMs",
            )
            appendLocked(
                event = "CONDITION_END",
                detail = buildString {
                    append("kind=$kind timeoutMs=$timeoutMs matched=$matched durationMs=$durationMs selector=$description")
                    detail?.let { append(" detail=$it") }
                },
            )
        }
    }

    fun waitStarted(kind: String, detail: String? = null) {
        synchronized(lock) {
            setStatusLocked(
                status = "WAITING",
                detail = detail ?: kind,
            )
            appendLocked(
                event = "WAIT_START",
                detail = buildString {
                    append("kind=$kind")
                    detail?.let { append(" detail=$it") }
                },
            )
        }
    }

    fun waitFinished(kind: String, durationMs: Long, detail: String? = null) {
        synchronized(lock) {
            setStatusLocked(
                status = "COMMAND_RUNNING",
                detail = detail ?: "Wait finished kind=$kind durationMs=$durationMs",
            )
            appendLocked(
                event = "WAIT_END",
                detail = buildString {
                    append("kind=$kind durationMs=$durationMs")
                    detail?.let { append(" detail=$it") }
                },
            )
        }
    }

    fun note(event: String, detail: String? = null) {
        synchronized(lock) {
            appendLocked(
                event = event,
                detail = detail,
            )
        }
    }

    fun selectorLookupStarted(description: String, timeoutMs: Long, optional: Boolean) {
        synchronized(lock) {
            setStatusLocked(
                status = "WAITING_FOR_SELECTOR",
                detail = "selector=$description timeoutMs=$timeoutMs optional=$optional",
            )
            appendLocked(
                event = "SELECTOR_LOOKUP_START",
                detail = "selector=$description timeoutMs=$timeoutMs optional=$optional",
            )
        }
    }

    fun selectorLookupFinished(
        description: String,
        timeoutMs: Long,
        optional: Boolean,
        found: Boolean,
        durationMs: Long,
    ) {
        synchronized(lock) {
            setStatusLocked(
                status = "COMMAND_RUNNING",
                detail = "Selector lookup completed found=$found durationMs=$durationMs selector=$description",
            )
            appendLocked(
                event = "SELECTOR_LOOKUP_END",
                detail = "selector=$description timeoutMs=$timeoutMs optional=$optional found=$found durationMs=$durationMs",
            )
        }
    }

    private fun setStatusLocked(status: String, detail: String) {
        currentStatus = status
        currentDetail = detail
        currentSinceMs = System.currentTimeMillis()
        writeStatusLocked()
    }

    private fun appendLocked(
        event: String,
        flow: String? = currentFlow,
        command: String? = currentCommand,
        detail: String? = null,
    ) {
        val traceFile = tracePath ?: return
        val line = buildString {
            append(timestampFormatter.format(Instant.now()))
            append(" event=")
            append(event)
            flow?.let {
                append(" flow=\"")
                append(sanitize(it))
                append('"')
            }
            command?.let {
                append(" command=\"")
                append(sanitize(it))
                append('"')
            }
            detail?.let {
                append(" detail=\"")
                append(sanitize(it))
                append('"')
            }
            append('\n')
        }
        Files.writeString(traceFile, line, CREATE, APPEND, WRITE)
    }

    private fun writeStatusLocked() {
        val statusFile = statusPath ?: return
        val now = System.currentTimeMillis()
        val content = buildString {
            appendLine("updated_at=${timestampFormatter.format(Instant.ofEpochMilli(now))}")
            appendLine("status=$currentStatus")
            currentFlow?.let { appendLine("flow=${sanitize(it)}") }
            currentCommand?.let { appendLine("command=${sanitize(it)}") }
            appendLine("detail=${sanitize(currentDetail)}")
            appendLine("since=${timestampFormatter.format(Instant.ofEpochMilli(currentSinceMs))}")
            appendLine("elapsed_ms=${now - currentSinceMs}")
        }
        Files.writeString(statusFile, content, CREATE, TRUNCATE_EXISTING, WRITE)
    }

    private fun sanitize(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\"", "\\\"")
    }
}
