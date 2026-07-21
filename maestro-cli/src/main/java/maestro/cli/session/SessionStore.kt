package maestro.cli.session

import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

class SessionStore(private val keyValueStore: KeyValueStore) {

    data class LeaseAcquisition(
        val acquired: Boolean,
        val conflictingDriverHostPort: Int? = null,
    )

    fun acquire(
        sessionId: String,
        platform: Platform,
        deviceId: String,
        driverHostPort: Int,
    ): LeaseAcquisition {
        val now = System.currentTimeMillis()
        val currentKey = key(sessionId, platform, deviceId, driverHostPort)
        val devicePrefix = devicePrefix(platform, deviceId)
        return keyValueStore.update { database ->
            pruneInactiveSessions(database, now)
            val conflict = database.keys.firstOrNull {
                (it.startsWith(devicePrefix) && it != currentKey) || isLegacyKey(it, platform)
            }
            if (conflict != null) {
                LeaseAcquisition(
                    acquired = false,
                    conflictingDriverHostPort = if (conflict.startsWith(devicePrefix)) {
                        driverHostPort(conflict, devicePrefix)
                    } else {
                        null
                    },
                )
            } else {
                database[currentKey] = now.toString()
                LeaseAcquisition(acquired = true)
            }
        }
    }

    fun heartbeat(
        sessionId: String,
        platform: Platform,
        deviceId: String,
        driverHostPort: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        val currentKey = key(sessionId, platform, deviceId, driverHostPort)
        val devicePrefix = devicePrefix(platform, deviceId)
        return keyValueStore.update { database ->
            pruneInactiveSessions(database, now)
            val conflictExists = database.keys.any {
                (it.startsWith(devicePrefix) && it != currentKey) || isLegacyKey(it, platform)
            }
            if (currentKey !in database || conflictExists) {
                false
            } else {
                database[currentKey] = now.toString()
                true
            }
        }
    }

    fun release(
        sessionId: String,
        platform: Platform,
        deviceId: String,
        driverHostPort: Int,
    ) {
        keyValueStore.update { database ->
            database.remove(key(sessionId, platform, deviceId, driverHostPort))
        }
    }

    fun activeSessions(): List<String> {
        val now = System.currentTimeMillis()
        return keyValueStore.entries()
            .filter { (_, value) ->
                val lastHeartbeat = value.toLongOrNull()
                lastHeartbeat != null && now - lastHeartbeat < SESSION_TIMEOUT_MS
            }
            .keys
            .toList()
    }

    fun activeSessionsForDevice(platform: Platform, deviceId: String): List<String> {
        val prefix = devicePrefix(platform, deviceId)
        return activeSessions().filter { it.startsWith(prefix) }
    }

    private fun pruneInactiveSessions(database: MutableMap<String, String>, now: Long) {
        database.entries.removeIf { (_, value) ->
            val lastHeartbeat = value.toLongOrNull()
            lastHeartbeat == null || now - lastHeartbeat >= SESSION_TIMEOUT_MS
        }
    }

    private fun driverHostPort(key: String, devicePrefix: String): Int? {
        return key.removePrefix(devicePrefix).substringBefore('_').toIntOrNull()
    }

    private fun devicePrefix(platform: Platform, deviceId: String): String {
        val encodedDeviceId = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(deviceId.toByteArray(StandardCharsets.UTF_8))
        return "${KEY_VERSION}_${platform}_${encodedDeviceId}_"
    }

    private fun isLegacyKey(key: String, platform: Platform): Boolean {
        return !key.startsWith("${KEY_VERSION}_") && key.startsWith("${platform}_")
    }

    private fun key(
        sessionId: String,
        platform: Platform,
        deviceId: String,
        driverHostPort: Int,
    ): String {
        return "${devicePrefix(platform, deviceId)}${driverHostPort}_$sessionId"
    }

    companion object {
        private const val KEY_VERSION = "v2"
        private val SESSION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(21)

        val default by lazy {
            SessionStore(
                KeyValueStore(
                    dbFile = Paths
                        .get(System.getProperty("user.home"), ".maestro", "sessions")
                        .toFile()
                        .also { it.parentFile.mkdirs() }
                )
            )
        }
    }
}
