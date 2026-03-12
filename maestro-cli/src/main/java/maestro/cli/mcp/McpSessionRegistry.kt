package maestro.cli.mcp

import maestro.cli.session.MaestroSessionManager
import maestro.ViewHierarchy
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object McpSessionRegistry {

    data class SessionHandle(
        val sessionId: String,
        val deviceId: String,
        val appId: String?,
        val projectRoot: String?,
        val owner: String?,
        val label: String?,
        val platform: String,
        val driverHostPort: Int?,
        val createdAt: Long,
        val lastUsedAt: Long,
        val ttlMs: Long,
        val reused: Boolean = false,
    )

    private data class Entry(
        val managedSession: MaestroSessionManager.ManagedSession,
        val deviceId: String,
        val appId: String?,
        val projectRoot: String?,
        val owner: String?,
        val label: String?,
        val driverHostPort: Int?,
        val createdAt: Long,
        @Volatile var lastUsedAt: Long,
        @Volatile var ttlMs: Long,
        @Volatile var cachedHierarchy: ViewHierarchy? = null,
        @Volatile var closing: Boolean = false,
        val lock: Any = Any(),
    )

    private val logger = LoggerFactory.getLogger(McpSessionRegistry::class.java)
    private val sessions = ConcurrentHashMap<String, Entry>()
    private val cleanupExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "maestro-mcp-session-cleaner").apply {
            isDaemon = true
        }
    }
    private val ttlMs = System.getenv("MAESTRO_MCP_SESSION_TTL_MS")
        ?.toLongOrNull()
        ?.coerceAtLeast(1_000L)
        ?: TimeUnit.MINUTES.toMillis(15)

    init {
        cleanupExecutor.scheduleAtFixedRate(
            { pruneExpiredSessions() },
            1L,
            1L,
            TimeUnit.MINUTES,
        )
    }

    fun register(
        managedSession: MaestroSessionManager.ManagedSession,
        deviceId: String,
        appId: String? = null,
        projectRoot: String? = null,
        owner: String? = null,
        label: String? = null,
        driverHostPort: Int? = null,
        ttlMsOverride: Long? = null,
    ): SessionHandle {
        val now = System.currentTimeMillis()
        val entry = Entry(
            managedSession = managedSession,
            deviceId = deviceId,
            appId = appId,
            projectRoot = projectRoot,
            owner = owner,
            label = label,
            driverHostPort = driverHostPort,
            createdAt = now,
            lastUsedAt = now,
            ttlMs = ttlMsOverride ?: ttlMs,
        )
        sessions[managedSession.sessionId] = entry
        logger.info(
            "Registered MCP session sessionId={} deviceId={} ttlMs={}",
            managedSession.sessionId,
            deviceId,
            ttlMs,
        )
        return snapshot(managedSession.sessionId, entry)
    }

    fun openSession(
        sessionManager: MaestroSessionManager,
        deviceId: String,
        appId: String? = null,
        projectRoot: String? = null,
        owner: String? = null,
        label: String? = null,
        driverHostPort: Int? = null,
        ttlMsOverride: Long? = null,
    ): SessionHandle {
        pruneExpiredSessions()

        sessions.entries.firstOrNull { (_, entry) ->
            entry.deviceId == deviceId
                && entry.driverHostPort == driverHostPort
                && entry.appId == appId
                && entry.projectRoot == projectRoot
                && entry.owner == owner
                && entry.label == label
        }?.let { (sessionId, entry) ->
            touch(entry, ttlMsOverride)
            logger.info(
                "Reusing MCP hot session sessionId={} deviceId={} driverHostPort={}",
                sessionId,
                deviceId,
                driverHostPort ?: "default",
            )
            return snapshot(sessionId, entry, reused = true)
        }

        sessions.entries
            .filter { (_, entry) -> entry.deviceId == deviceId && entry.driverHostPort != driverHostPort }
            .map { it.key }
            .forEach { sessionId ->
                closeSession(sessionId, source = "superseded")
            }

        val managedSession = sessionManager.openSession(
            host = null,
            port = null,
            driverHostPort = driverHostPort,
            deviceId = deviceId,
            platform = null,
            reinstallDriver = false,
        )
        return register(
            managedSession = managedSession,
            deviceId = deviceId,
            appId = appId,
            projectRoot = projectRoot,
            owner = owner,
            label = label,
            driverHostPort = driverHostPort,
            ttlMsOverride = ttlMsOverride,
        )
    }

    fun resumeSession(sessionId: String, ttlMsOverride: Long? = null): SessionHandle? {
        val entry = sessions[sessionId] ?: return null
        if (entry.closing) {
            return null
        }
        touch(entry, ttlMsOverride)
        logger.info(
            "Resumed MCP hot session sessionId={} deviceId={} ttlMs={}",
            sessionId,
            entry.deviceId,
            entry.ttlMs,
        )
        return snapshot(sessionId, entry)
    }

    fun closeSession(sessionId: String, source: String = "manual"): Boolean {
        val entry = sessions[sessionId] ?: return false
        synchronized(entry.lock) {
            if (entry.closing) {
                return false
            }
            entry.closing = true
            sessions.remove(sessionId, entry)
            logger.info("Closing MCP session sessionId={} source={}", sessionId, source)
            entry.cachedHierarchy = null
            entry.managedSession.close(source)
        }
        return true
    }

    fun close(sessionId: String, source: String = "manual"): Boolean = closeSession(sessionId, source)

    fun sessionHandle(sessionId: String): SessionHandle? {
        val entry = sessions[sessionId] ?: return null
        touch(entry)
        return snapshot(sessionId, entry)
    }

    fun listSessions(): List<SessionHandle> {
        pruneExpiredSessions()
        return sessions.entries
            .sortedBy { it.value.lastUsedAt }
            .map { (sessionId, entry) -> snapshot(sessionId, entry) }
    }

    fun deviceId(sessionId: String): String? {
        val entry = sessions[sessionId] ?: return null
        touch(entry)
        return entry.deviceId
    }

    fun <T> withSession(
        sessionId: String,
        expectedDeviceId: String? = null,
        block: (MaestroSessionManager.MaestroSession) -> T,
    ): T {
        val entry = sessions[sessionId]
            ?: error("Unknown or expired session_id: $sessionId")
        if (expectedDeviceId != null && expectedDeviceId != entry.deviceId) {
            error("session_id $sessionId is bound to device ${entry.deviceId}, not $expectedDeviceId")
        }
        touch(entry)
        return synchronized(entry.lock) {
            if (entry.closing) {
                error("session_id $sessionId is closing")
            }
            block(entry.managedSession.session)
        }
    }

    fun sessionCount(): Int = sessions.size

    fun closeAll(source: String = "server_shutdown") {
        sessions.keys().toList().forEach { sessionId ->
            closeSession(sessionId, source)
        }
    }

    fun cachedHierarchy(sessionId: String): ViewHierarchy? {
        val entry = sessions[sessionId] ?: return null
        touch(entry)
        if (entry.closing) {
            return null
        }
        return entry.cachedHierarchy
    }

    fun cacheHierarchy(sessionId: String, hierarchy: ViewHierarchy) {
        val entry = sessions[sessionId] ?: return
        if (entry.closing) {
            return
        }
        touch(entry)
        entry.cachedHierarchy = hierarchy
    }

    fun invalidateHierarchy(sessionId: String?) {
        if (sessionId == null) {
            return
        }
        sessions[sessionId]?.cachedHierarchy = null
    }

    private fun touch(entry: Entry, ttlMsOverride: Long? = null) {
        entry.lastUsedAt = System.currentTimeMillis()
        ttlMsOverride?.let { entry.ttlMs = it.coerceAtLeast(1_000L) }
    }

    private fun pruneExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries
            .filter { (_, entry) -> now - entry.lastUsedAt >= entry.ttlMs }
            .forEach { (sessionId, entry) ->
                logger.info("Pruning expired MCP session sessionId={} idleMs={}", sessionId, now - entry.lastUsedAt)
                runCatching {
                    closeSession(sessionId, source = "ttl_expired")
                }.onFailure {
                    logger.warn("Failed to close expired MCP session sessionId={}", sessionId, it)
                }
            }
    }

    private fun snapshot(sessionId: String, entry: Entry, reused: Boolean = false): SessionHandle {
        return SessionHandle(
            sessionId = sessionId,
            deviceId = entry.deviceId,
            appId = entry.appId,
            projectRoot = entry.projectRoot,
            owner = entry.owner,
            label = entry.label,
            platform = entry.managedSession.platform.name.lowercase(),
            driverHostPort = entry.driverHostPort,
            createdAt = entry.createdAt,
            lastUsedAt = entry.lastUsedAt,
            ttlMs = entry.ttlMs,
            reused = reused,
        )
    }
}
