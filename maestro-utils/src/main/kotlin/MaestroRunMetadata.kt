package maestro.utils

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class MaestroRunMetadataSnapshot(
    val runId: String,
    val runLabel: String,
    val runOwner: String,
    val repoRoot: String,
    val repoName: String,
    val command: String,
    val startedAt: String,
    val pid: String,
    val parentPid: String?,
    val forceStaleKill: Boolean,
) {
    fun asEnvironmentVariables(): Map<String, String> {
        val base = mutableMapOf(
            MaestroRunMetadata.ENV_RUN_ID to runId,
            MaestroRunMetadata.ENV_RUN_LABEL to runLabel,
            MaestroRunMetadata.ENV_RUN_OWNER to runOwner,
            MaestroRunMetadata.ENV_REPO_ROOT to repoRoot,
            MaestroRunMetadata.ENV_REPO_NAME to repoName,
            MaestroRunMetadata.ENV_COMMAND to command,
            MaestroRunMetadata.ENV_STARTED_AT to startedAt,
            MaestroRunMetadata.ENV_PID to pid,
            MaestroRunMetadata.ENV_FORCE_STALE_KILL to if (forceStaleKill) "1" else "0",
        )

        parentPid?.takeIf { it.isNotBlank() }?.let {
            base[MaestroRunMetadata.ENV_PARENT_PID] = it
        }

        return base
    }

    fun asLogContext(): Map<String, String> {
        return mapOf(
            "runId" to runId,
            "runLabel" to runLabel,
            "runOwner" to runOwner,
            "repoName" to repoName,
            "runCommand" to command,
        )
    }
}

object MaestroRunMetadata {
    const val ENV_RUN_ID = "MAESTRO_RUN_ID"
    const val ENV_RUN_LABEL = "MAESTRO_RUN_LABEL"
    const val ENV_RUN_OWNER = "MAESTRO_RUN_OWNER"
    const val ENV_REPO_ROOT = "MAESTRO_REPO_ROOT"
    const val ENV_REPO_NAME = "MAESTRO_REPO_NAME"
    const val ENV_COMMAND = "MAESTRO_COMMAND"
    const val ENV_STARTED_AT = "MAESTRO_STARTED_AT"
    const val ENV_PID = "MAESTRO_PID"
    const val ENV_PARENT_PID = "MAESTRO_PARENT_PID"
    const val ENV_FORCE_STALE_KILL = "MAESTRO_FORCE_STALE_KILL"

    private const val PROP_PREFIX = "maestro.run."
    private const val PROP_RUN_ID = "${PROP_PREFIX}id"
    private const val PROP_RUN_LABEL = "${PROP_PREFIX}label"
    private const val PROP_RUN_OWNER = "${PROP_PREFIX}owner"
    private const val PROP_REPO_ROOT = "${PROP_PREFIX}repoRoot"
    private const val PROP_REPO_NAME = "${PROP_PREFIX}repoName"
    private const val PROP_COMMAND = "${PROP_PREFIX}command"
    private const val PROP_STARTED_AT = "${PROP_PREFIX}startedAt"
    private const val PROP_PID = "${PROP_PREFIX}pid"
    private const val PROP_PARENT_PID = "${PROP_PREFIX}parentPid"
    private const val PROP_FORCE_STALE_KILL = "${PROP_PREFIX}forceStaleKill"

    private val labelTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    @Volatile
    private var snapshot: MaestroRunMetadataSnapshot? = null

    @Synchronized
    fun initialize(
        command: String,
        runLabel: String? = null,
        runOwner: String? = null,
        forceStaleKill: Boolean? = null,
    ): MaestroRunMetadataSnapshot {
        val resolved = resolveSnapshot(
            command = command,
            runLabel = runLabel,
            runOwner = runOwner,
            forceStaleKill = forceStaleKill,
        )
        persist(resolved)
        snapshot = resolved
        return resolved
    }

    fun current(commandHint: String = "unknown"): MaestroRunMetadataSnapshot {
        snapshot?.let { return it }
        return synchronized(this) {
            snapshot ?: resolveSnapshot(command = commandHint).also {
                persist(it)
                snapshot = it
            }
        }
    }

    fun environmentVariables(commandHint: String = "unknown"): Map<String, String> {
        return current(commandHint).asEnvironmentVariables()
    }

    fun prefixedEnvironmentVariables(prefix: String, commandHint: String = "unknown"): Map<String, String> {
        return environmentVariables(commandHint).mapKeys { (key, _) -> "$prefix$key" }
    }

    fun shouldForceStaleKill(commandHint: String = "unknown"): Boolean {
        return current(commandHint).forceStaleKill
    }

    private fun resolveSnapshot(
        command: String,
        runLabel: String? = null,
        runOwner: String? = null,
        forceStaleKill: Boolean? = null,
    ): MaestroRunMetadataSnapshot {
        val startedAt = firstNonBlank(
            System.getProperty(PROP_STARTED_AT),
            System.getenv(ENV_STARTED_AT),
        ) ?: Instant.now().toString()

        val repoRoot = firstNonBlank(
            System.getProperty(PROP_REPO_ROOT),
            System.getenv(ENV_REPO_ROOT),
        ) ?: detectRepoRoot(System.getProperty("user.dir"))

        val repoName = normalizeToken(
            firstNonBlank(
                System.getProperty(PROP_REPO_NAME),
                System.getenv(ENV_REPO_NAME),
            ) ?: File(repoRoot).name,
            fallback = "unknown-repo"
        )

        val resolvedCommand = normalizeToken(
            firstNonBlank(
                command,
                System.getProperty(PROP_COMMAND),
                System.getenv(ENV_COMMAND),
            ),
            fallback = "unknown-command"
        )

        val owner = normalizeToken(
            firstNonBlank(
                runOwner,
                System.getProperty(PROP_RUN_OWNER),
                System.getenv(ENV_RUN_OWNER),
                System.getProperty("user.name"),
                System.getenv("USER"),
                System.getenv("LOGNAME"),
            ),
            fallback = "unknown-owner"
        )

        val label = normalizeToken(
            firstNonBlank(
                runLabel,
                System.getProperty(PROP_RUN_LABEL),
                System.getenv(ENV_RUN_LABEL),
            ) ?: buildDefaultLabel(
                repoName = repoName,
                command = resolvedCommand,
                startedAt = startedAt,
            ),
            fallback = "$repoName:$resolvedCommand:unknown"
        )

        val runId = normalizeToken(
            firstNonBlank(
                System.getProperty(PROP_RUN_ID),
                System.getenv(ENV_RUN_ID),
            ) ?: UUID.randomUUID().toString(),
            fallback = UUID.randomUUID().toString()
        )

        val pid = firstNonBlank(
            System.getProperty(PROP_PID),
            System.getenv(ENV_PID),
        ) ?: ProcessHandle.current().pid().toString()

        val parentPid = firstNonBlank(
            System.getProperty(PROP_PARENT_PID),
            System.getenv(ENV_PARENT_PID),
        ) ?: ProcessHandle.current().parent().map { it.pid().toString() }.orElse(null)

        val forceKill = forceStaleKill
            ?: parseBoolean(
                firstNonBlank(
                    System.getProperty(PROP_FORCE_STALE_KILL),
                    System.getenv(ENV_FORCE_STALE_KILL),
                )
            )

        return MaestroRunMetadataSnapshot(
            runId = runId,
            runLabel = label,
            runOwner = owner,
            repoRoot = repoRoot,
            repoName = repoName,
            command = resolvedCommand,
            startedAt = startedAt,
            pid = pid,
            parentPid = parentPid,
            forceStaleKill = forceKill,
        )
    }

    private fun persist(snapshot: MaestroRunMetadataSnapshot) {
        System.setProperty(PROP_RUN_ID, snapshot.runId)
        System.setProperty(PROP_RUN_LABEL, snapshot.runLabel)
        System.setProperty(PROP_RUN_OWNER, snapshot.runOwner)
        System.setProperty(PROP_REPO_ROOT, snapshot.repoRoot)
        System.setProperty(PROP_REPO_NAME, snapshot.repoName)
        System.setProperty(PROP_COMMAND, snapshot.command)
        System.setProperty(PROP_STARTED_AT, snapshot.startedAt)
        System.setProperty(PROP_PID, snapshot.pid)
        snapshot.parentPid?.let { System.setProperty(PROP_PARENT_PID, it) }
        System.setProperty(PROP_FORCE_STALE_KILL, if (snapshot.forceStaleKill) "1" else "0")
    }

    private fun buildDefaultLabel(repoName: String, command: String, startedAt: String): String {
        val timestamp = runCatching {
            labelTimestampFormatter.format(Instant.parse(startedAt))
        }.getOrElse {
            labelTimestampFormatter.format(Instant.now())
        }

        return "$repoName:$command:$timestamp"
    }

    private fun detectRepoRoot(userDir: String): String {
        var current = File(userDir).absoluteFile
        while (true) {
            if (File(current, ".git").exists()) {
                return current.absolutePath
            }
            val parent = current.parentFile ?: return File(userDir).absoluteFile.absolutePath
            current = parent
        }
    }

    private fun normalizeToken(value: String?, fallback: String): String {
        val normalized = value
            ?.trim()
            ?.replace(Regex("\\s+"), "_")
            ?.takeIf { it.isNotEmpty() }
        return normalized ?: fallback
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun parseBoolean(value: String?): Boolean {
        return when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "on" -> true
            else -> false
        }
    }
}
