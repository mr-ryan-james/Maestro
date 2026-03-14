package maestro.cli.mcp

import maestro.cli.CliError
import maestro.cli.session.MaestroSessionManager
import maestro.device.DeviceService
import maestro.ViewHierarchy
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object McpSessionRegistry {

    private enum class SessionHealthState {
        HEALTHY,
        RECOVERED,
        UNHEALTHY,
    }

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
        val healthState: String,
        val lastHealthCheckAt: Long?,
        val lastFailureReason: String? = null,
        val repairedFromSessionId: String? = null,
    )

    private data class HealthCheckResult(
        val healthy: Boolean,
        val reason: String? = null,
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
        @Volatile var healthState: SessionHealthState = SessionHealthState.HEALTHY,
        @Volatile var lastHealthCheckAt: Long? = null,
        @Volatile var lastFailureReason: String? = null,
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
        val connectedDeviceId = ensureDeviceConnected(deviceId, driverHostPort)

        sessions.entries
            .sortedByDescending { it.value.lastUsedAt }
            .firstOrNull { (_, entry) ->
                entry.deviceId == connectedDeviceId &&
                    entry.driverHostPort == driverHostPort &&
                    entry.appId == appId &&
                    entry.projectRoot == projectRoot &&
                    entry.owner == owner &&
                    entry.label == label
            }
            ?.let { (sessionId, entry) ->
                val health = probeSessionHealth(entry)
                if (health.healthy) {
                    touch(entry, ttlMsOverride)
                    logger.info(
                        "Reusing MCP hot session sessionId={} deviceId={} driverHostPort={}",
                        sessionId,
                        connectedDeviceId,
                        driverHostPort ?: "default",
                    )
                    return snapshot(sessionId, entry, reused = true)
                }
                logger.warn(
                    "Discarding unhealthy MCP hot session sessionId={} deviceId={} reason={}",
                    sessionId,
                    connectedDeviceId,
                    health.reason ?: "unknown",
                )
                closeSession(sessionId, source = "reuse_unhealthy")
            }

        sessions.entries
            .filter { (_, entry) -> entry.deviceId == connectedDeviceId && entry.driverHostPort != driverHostPort }
            .map { it.key }
            .forEach { sessionId ->
                closeSession(sessionId, source = "superseded")
            }

        val managedSession = openManagedSessionWithReconnect(
            sessionManager = sessionManager,
            requestedDeviceId = deviceId,
            connectedDeviceId = connectedDeviceId,
            driverHostPort = driverHostPort,
        )
        return register(
            managedSession = managedSession,
            deviceId = managedSession.deviceId ?: connectedDeviceId,
            appId = appId,
            projectRoot = projectRoot,
            owner = owner,
            label = label,
            driverHostPort = driverHostPort,
            ttlMsOverride = ttlMsOverride,
        )
    }

    fun resumeSession(
        sessionId: String,
        sessionManager: MaestroSessionManager? = null,
        ttlMsOverride: Long? = null,
    ): SessionHandle? {
        val entry = sessions[sessionId] ?: return null
        if (entry.closing) {
            return null
        }
        val health = probeSessionHealth(entry)
        if (!health.healthy) {
            logger.warn(
                "Refusing to resume unhealthy MCP session sessionId={} deviceId={} reason={}",
                sessionId,
                entry.deviceId,
                health.reason ?: "unknown",
            )
            if (sessionManager == null) {
                return null
            }
            closeSession(sessionId, source = "resume_unhealthy")
            val reopened = openSession(
                sessionManager = sessionManager,
                deviceId = entry.deviceId,
                appId = entry.appId,
                projectRoot = entry.projectRoot,
                owner = entry.owner,
                label = entry.label,
                driverHostPort = entry.driverHostPort,
                ttlMsOverride = ttlMsOverride ?: entry.ttlMs,
            )
            return reopened.copy(
                reused = true,
                healthState = SessionHealthState.RECOVERED.name.lowercase(),
                repairedFromSessionId = sessionId,
            )
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
            try {
                block(entry.managedSession.session)
            } catch (throwable: Throwable) {
                if (shouldMarkSessionUnhealthy(throwable)) {
                    markUnhealthy(entry, throwable.message ?: throwable.javaClass.simpleName)
                }
                throw throwable
            }
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

    private fun probeSessionHealth(entry: Entry): HealthCheckResult {
        if (entry.closing) {
            return HealthCheckResult(healthy = false, reason = "session_closing")
        }
        return synchronized(entry.lock) {
            if (entry.closing) {
                return@synchronized HealthCheckResult(healthy = false, reason = "session_closing")
            }
            val checkedAt = System.currentTimeMillis()
            val driver = entry.managedSession.session.maestro.driver
            if (driver.isShutdown()) {
                markUnhealthy(
                    entry = entry,
                    reason = "driver_channel_closed",
                    checkedAt = checkedAt,
                )
                return@synchronized HealthCheckResult(healthy = false, reason = entry.lastFailureReason)
            }
            runCatching {
                entry.managedSession.session.maestro.deviceInfo()
            }.fold(
                onSuccess = {
                    entry.healthState = if (entry.healthState == SessionHealthState.RECOVERED) {
                        SessionHealthState.RECOVERED
                    } else {
                        SessionHealthState.HEALTHY
                    }
                    entry.lastHealthCheckAt = checkedAt
                    entry.lastFailureReason = null
                    HealthCheckResult(healthy = true)
                },
                onFailure = { throwable ->
                    markUnhealthy(
                        entry = entry,
                        reason = throwable.message ?: throwable.javaClass.simpleName,
                        checkedAt = checkedAt,
                    )
                    HealthCheckResult(healthy = false, reason = entry.lastFailureReason)
                },
            )
        }
    }

    private fun markUnhealthy(entry: Entry, reason: String, checkedAt: Long = System.currentTimeMillis()) {
        entry.healthState = SessionHealthState.UNHEALTHY
        entry.lastHealthCheckAt = checkedAt
        entry.lastFailureReason = reason
        entry.cachedHierarchy = null
    }

    private fun ensureDeviceConnected(deviceId: String, driverHostPort: Int?): String {
        DeviceService.listConnectedDevices()
            .firstOrNull { it.instanceId.equals(deviceId, ignoreCase = true) }
            ?.let { return it.instanceId }

        val launchable = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
            .firstOrNull { it.modelId.equals(deviceId, ignoreCase = true) }
            ?: return deviceId

        val started = runCatching {
            DeviceService.startDevice(launchable, driverHostPort)
        }.getOrElse { throwable ->
            if (throwable is CliError) {
                throw throwable
            }
            throw IllegalStateException(
                "Failed to start requested device $deviceId before opening session: ${throwable.message}",
                throwable,
            )
        }

        logger.info(
            "Started launchable device before opening MCP session requestedDeviceId={} connectedDeviceId={} driverHostPort={}",
            deviceId,
            started.instanceId,
            driverHostPort ?: "default",
        )
        return started.instanceId
    }

    private fun openManagedSessionWithReconnect(
        sessionManager: MaestroSessionManager,
        requestedDeviceId: String,
        connectedDeviceId: String,
        driverHostPort: Int?,
    ): MaestroSessionManager.ManagedSession {
        var currentDeviceId = connectedDeviceId
        repeat(2) { attempt ->
            try {
                return sessionManager.openSession(
                    host = null,
                    port = null,
                    driverHostPort = driverHostPort,
                    deviceId = currentDeviceId,
                    platform = null,
                    reinstallDriver = false,
                )
            } catch (throwable: Throwable) {
                if (!shouldRetryDeviceOpen(throwable) || attempt == 1) {
                    throw throwable
                }
                logger.warn(
                    "Retrying MCP session open after device connectivity race requestedDeviceId={} connectedDeviceId={} driverHostPort={} reason={}",
                    requestedDeviceId,
                    currentDeviceId,
                    driverHostPort ?: "default",
                    throwable.message ?: throwable.javaClass.simpleName,
                )
                currentDeviceId = reconnectDevice(requestedDeviceId, driverHostPort)
            }
        }
        error("unreachable")
    }

    private fun reconnectDevice(deviceId: String, driverHostPort: Int?): String {
        repeat(3) { attempt ->
            runCatching { ensureDeviceConnected(deviceId, driverHostPort) }
                .onSuccess { return it }
                .onFailure { throwable ->
                    logger.warn(
                        "Failed to reconnect requested device attempt={} deviceId={} driverHostPort={} reason={}",
                        attempt + 1,
                        deviceId,
                        driverHostPort ?: "default",
                        throwable.message ?: throwable.javaClass.simpleName,
                    )
                }
            Thread.sleep(500L)
        }
        return ensureDeviceConnected(deviceId, driverHostPort)
    }

    private fun shouldRetryDeviceOpen(throwable: Throwable): Boolean {
        return generateSequence(throwable) { it.cause }
            .any { current ->
                val message = current.message?.lowercase().orEmpty()
                current is CliError && message.contains("is not connected")
            }
    }

    private fun shouldMarkSessionUnhealthy(throwable: Throwable): Boolean {
        return generateSequence(throwable) { it.cause }
            .map { current ->
                val message = current.message?.lowercase().orEmpty()
                current.javaClass.name.lowercase() to message
            }
            .any { (className, message) ->
                className.contains("connectexception") ||
                    className.contains("socketexception") ||
                    className.contains("xcutestservererror") ||
                    message.contains("failed to connect") ||
                    message.contains("connection refused") ||
                    message.contains("unexpected end of stream") ||
                    message.contains("stream was reset")
            }
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

    private fun snapshot(
        sessionId: String,
        entry: Entry,
        reused: Boolean = false,
        repairedFromSessionId: String? = null,
    ): SessionHandle {
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
            healthState = entry.healthState.name.lowercase(),
            lastHealthCheckAt = entry.lastHealthCheckAt,
            lastFailureReason = entry.lastFailureReason,
            repairedFromSessionId = repairedFromSessionId,
        )
    }
}
