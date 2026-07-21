package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import maestro.cli.db.KeyValueStore
import maestro.device.Platform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var sessionStore: SessionStore

    @BeforeEach
    fun setUp() {
        sessionStore = SessionStore(KeyValueStore(tempDir.resolve("sessions").toFile()))
    }

    @Test
    fun `heartbeat creates an active device session`() {
        sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A")

        assertThat(sessionStore.activeSessions()).containsExactly("ANDROID_device-A_session-1")
    }

    @Test
    fun `delete removes an active device session`() {
        sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A")

        sessionStore.delete("session-1", Platform.ANDROID, "device-A")

        assertThat(sessionStore.activeSessions()).isEmpty()
    }

    @Test
    fun `legacy platform session does not suppress device startup`() {
        val keyValueStore = KeyValueStore(tempDir.resolve("legacy-sessions").toFile())
        keyValueStore.set("ANDROID_old-session", System.currentTimeMillis().toString())
        val store = SessionStore(keyValueStore)

        val hasOtherSession = store.hasActiveSessionForDevice(
            "new-session",
            Platform.ANDROID,
            "device-A",
        )

        assertThat(hasOtherSession).isFalse()
    }

    @Test
    fun `current session does not suppress its own device startup`() {
        sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A")

        val hasOtherSession = sessionStore.hasActiveSessionForDevice(
            "session-1",
            Platform.ANDROID,
            "device-A",
        )

        assertThat(hasOtherSession).isFalse()
    }

    @Test
    fun `another session on the same device suppresses duplicate startup`() {
        sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A")
        sessionStore.heartbeat("session-2", Platform.ANDROID, "device-A")

        val hasOtherSession = sessionStore.hasActiveSessionForDevice(
            "session-1",
            Platform.ANDROID,
            "device-A",
        )

        assertThat(hasOtherSession).isTrue()
    }

    @Test
    fun `session on a different device does not suppress startup`() {
        sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A")
        sessionStore.heartbeat("session-2", Platform.ANDROID, "device-B")

        val hasOtherSession = sessionStore.hasActiveSessionForDevice(
            "session-1",
            Platform.ANDROID,
            "device-A",
        )

        assertThat(hasOtherSession).isFalse()
    }

    @Test
    fun `device cleanup ignores sessions on other devices`() {
        sessionStore.heartbeat("session-1", Platform.ANDROID, "device-A")
        sessionStore.heartbeat("session-2", Platform.ANDROID, "device-B")

        sessionStore.delete("session-1", Platform.ANDROID, "device-A")

        assertThat(sessionStore.shouldCloseSession(Platform.ANDROID, "device-A")).isTrue()
        assertThat(sessionStore.shouldCloseSession(Platform.ANDROID, "device-B")).isFalse()
    }

    @Test
    fun `stale sessions are pruned on heartbeat`() {
        val keyValueStore = KeyValueStore(tempDir.resolve("stale-sessions").toFile())
        keyValueStore.set(
            "ANDROID_device-A_stale-session",
            (System.currentTimeMillis() - 22_000L).toString(),
        )
        val store = SessionStore(keyValueStore)

        store.heartbeat("fresh-session", Platform.ANDROID, "device-A")

        assertThat(store.activeSessions()).containsExactly("ANDROID_device-A_fresh-session")
    }
}
