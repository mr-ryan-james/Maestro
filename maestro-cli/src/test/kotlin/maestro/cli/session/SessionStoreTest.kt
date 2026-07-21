package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class SessionStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sessionStore: SessionStore

    @BeforeEach
    fun setUp() {
        sessionStore = SessionStore(KeyValueStore(tempDir.resolve("sessions").toFile()))
    }

    @Test
    fun `acquire creates an active device and port lease`() {
        val acquisition = sessionStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)

        assertThat(acquisition.acquired).isTrue()
        assertThat(sessionStore.activeSessions()).containsExactly(leaseKey("session-1", Platform.ANDROID, "device-A", 7001))
    }

    @Test
    fun `release removes an active lease`() {
        sessionStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)

        sessionStore.release("session-1", Platform.ANDROID, "device-A", 7001)

        assertThat(sessionStore.activeSessions()).isEmpty()
    }

    @Test
    fun `fresh legacy platform lease blocks a device during upgrade`() {
        val keyValueStore = KeyValueStore(tempDir.resolve("legacy-sessions").toFile())
        keyValueStore.set("ANDROID_old-session", System.currentTimeMillis().toString())
        val store = SessionStore(keyValueStore)

        val acquisition = store.acquire("new-session", Platform.ANDROID, "device-A", 7001)

        assertThat(acquisition.acquired).isFalse()
        assertThat(acquisition.conflictingDriverHostPort).isNull()
    }

    @Test
    fun `stale legacy platform lease is pruned during upgrade`() {
        val keyValueStore = KeyValueStore(tempDir.resolve("stale-legacy-sessions").toFile())
        keyValueStore.set("ANDROID_old-session", (System.currentTimeMillis() - 22_000L).toString())
        val store = SessionStore(keyValueStore)

        val acquisition = store.acquire("new-session", Platform.ANDROID, "device-A", 7001)

        assertThat(acquisition.acquired).isTrue()
        assertThat(store.activeSessions()).containsExactly(leaseKey("new-session", Platform.ANDROID, "device-A", 7001))
    }

    @Test
    fun `same device and port rejects a second lease`() {
        sessionStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)

        val acquisition = sessionStore.acquire("session-2", Platform.ANDROID, "device-A", 7001)

        assertThat(acquisition.acquired).isFalse()
        assertThat(acquisition.conflictingDriverHostPort).isEqualTo(7001)
    }

    @Test
    fun `same device and different port rejects a second lease`() {
        sessionStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)

        val acquisition = sessionStore.acquire("session-2", Platform.ANDROID, "device-A", 7021)

        assertThat(acquisition.acquired).isFalse()
        assertThat(acquisition.conflictingDriverHostPort).isEqualTo(7001)
    }

    @Test
    fun `different device acquires independently`() {
        val first = sessionStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)
        val second = sessionStore.acquire("session-2", Platform.ANDROID, "device-B", 7001)

        assertThat(first.acquired).isTrue()
        assertThat(second.acquired).isTrue()
    }

    @Test
    fun `heartbeat renews an acquired lease`() {
        sessionStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)

        val renewed = sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A", 7001)

        assertThat(renewed).isTrue()
        assertThat(sessionStore.activeSessions()).containsExactly(leaseKey("session-1", Platform.ANDROID, "device-A", 7001))
    }

    @Test
    fun `heartbeat cannot revive a released lease without another owner`() {
        sessionStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)
        sessionStore.release("session-1", Platform.ANDROID, "device-A", 7001)

        val renewed = sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A", 7001)

        assertThat(renewed).isFalse()
        assertThat(sessionStore.activeSessions()).isEmpty()
    }

    @Test
    fun `heartbeat cannot revive a lease after another owner acquires`() {
        sessionStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)
        sessionStore.release("session-1", Platform.ANDROID, "device-A", 7001)
        sessionStore.acquire("session-2", Platform.ANDROID, "device-A", 7021)

        val renewed = sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A", 7001)

        assertThat(renewed).isFalse()
        assertThat(sessionStore.activeSessions()).containsExactly(leaseKey("session-2", Platform.ANDROID, "device-A", 7021))
    }

    @Test
    fun `legacy heartbeat appearing after acquisition invalidates the new lease`() {
        val keyValueStore = KeyValueStore(tempDir.resolve("upgrade-race-sessions").toFile())
        val store = SessionStore(keyValueStore)
        store.acquire("session-1", Platform.ANDROID, "device-A", 7001)
        keyValueStore.set("ANDROID_old-session", System.currentTimeMillis().toString())

        val renewed = store.heartbeat("session-1", Platform.ANDROID, "device-A", 7001)

        assertThat(renewed).isFalse()
    }

    @Test
    fun `stale lease is pruned during acquisition`() {
        val keyValueStore = KeyValueStore(tempDir.resolve("stale-sessions").toFile())
        keyValueStore.set(
            "ANDROID_device-A_7001_stale-session",
            (System.currentTimeMillis() - 22_000L).toString(),
        )
        val store = SessionStore(keyValueStore)

        val acquisition = store.acquire("fresh-session", Platform.ANDROID, "device-A", 7021)

        assertThat(acquisition.acquired).isTrue()
        assertThat(store.activeSessions()).containsExactly(leaseKey("fresh-session", Platform.ANDROID, "device-A", 7021))
    }

    @Test
    fun `device identifiers with shared prefixes acquire independently`() {
        val first = sessionStore.acquire("session-1", Platform.ANDROID, "device", 7001)
        val second = sessionStore.acquire("session-2", Platform.ANDROID, "device_suffix", 7001)

        assertThat(first.acquired).isTrue()
        assertThat(second.acquired).isTrue()
    }

    @Test
    fun `concurrent acquisition elects exactly one owner`() {
        val sessionsFile = tempDir.resolve("concurrent-sessions").toFile()
        val firstStore = SessionStore(KeyValueStore(sessionsFile))
        val secondStore = SessionStore(KeyValueStore(sessionsFile))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val first = executor.submit<SessionStore.LeaseAcquisition> {
            ready.countDown()
            start.await()
            firstStore.acquire("session-1", Platform.ANDROID, "device-A", 7001)
        }
        val second = executor.submit<SessionStore.LeaseAcquisition> {
            ready.countDown()
            start.await()
            secondStore.acquire("session-2", Platform.ANDROID, "device-A", 7021)
        }
        ready.await()
        start.countDown()

        val acquisitions = listOf(first.get(), second.get())
        executor.shutdownNow()

        assertThat(acquisitions.count { it.acquired }).isEqualTo(1)
        assertThat(acquisitions.count { !it.acquired }).isEqualTo(1)
    }

    private fun leaseKey(
        sessionId: String,
        platform: Platform,
        deviceId: String,
        driverHostPort: Int,
    ): String {
        val encodedDeviceId = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(deviceId.toByteArray(StandardCharsets.UTF_8))
        return "v2_${platform}_${encodedDeviceId}_${driverHostPort}_$sessionId"
    }
}
