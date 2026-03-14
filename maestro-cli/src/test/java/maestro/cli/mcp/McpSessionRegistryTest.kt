package maestro.cli.mcp

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Maestro
import maestro.OnDeviceElementQuery
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.cli.session.MaestroSessionManager
import maestro.device.DeviceOrientation
import maestro.device.Platform
import okio.Sink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.ConnectException

class McpSessionRegistryTest {

    @AfterEach
    fun tearDown() {
        runCatching { unmockkObject(MaestroSessionManager) }
        McpSessionRegistry.closeAll("test_cleanup")
    }

    @Test
    fun resumeSessionReturnsNullForDeadSessionWithoutSessionManager() {
        val managedSession = managedSession(
            sessionId = "dead-session",
            driver = StubDriver(
                deviceInfoFailure = IllegalStateException("Failed to connect to /127.0.0.1:7106"),
            ),
        )

        val handle = McpSessionRegistry.register(
            managedSession = managedSession,
            deviceId = "sim-1",
            appId = "technology.ryans.soundlikeus",
        )

        val resumed = McpSessionRegistry.resumeSession(handle.sessionId)

        assertThat(resumed).isNull()
        val updated = McpSessionRegistry.sessionHandle(handle.sessionId)
        assertThat(updated).isNotNull()
        assertThat(updated?.healthState).isEqualTo("unhealthy")
        assertThat(updated?.lastFailureReason).contains("Failed to connect")
    }

    @Test
    fun resumeSessionReturnsNullImmediatelyForShutdownDriverWithoutSessionManager() {
        val managedSession = managedSession(
            sessionId = "shutdown-driver-session",
            driver = StubDriver(
                shutdown = true,
                deviceInfoFailure = IllegalStateException("deviceInfo should not be called for a shutdown driver"),
            ),
        )

        val handle = McpSessionRegistry.register(
            managedSession = managedSession,
            deviceId = "sim-shutdown",
            appId = "technology.ryans.soundlikeus",
        )

        val resumed = McpSessionRegistry.resumeSession(handle.sessionId)

        assertThat(resumed).isNull()
        val updated = McpSessionRegistry.sessionHandle(handle.sessionId)
        assertThat(updated).isNotNull()
        assertThat(updated?.healthState).isEqualTo("unhealthy")
        assertThat(updated?.lastFailureReason).isEqualTo("driver_channel_closed")
    }

    @Test
    fun resumeSessionRepairsDeadSessionWhenSessionManagerIsAvailable() {
        mockkObject(MaestroSessionManager)

        val deadSession = managedSession(
            sessionId = "dead-session-to-repair",
            driver = StubDriver(
                deviceInfoFailure = IllegalStateException("Failed to connect to /127.0.0.1:7106"),
            ),
        )

        val handle = McpSessionRegistry.register(
            managedSession = deadSession,
            deviceId = "sim-repair",
            appId = "technology.ryans.soundlikeus",
            owner = "ryanpfister",
            label = "soundlikeus:repair",
            driverHostPort = 7106,
        )

        every {
            MaestroSessionManager.openSession(
                host = null,
                port = null,
                driverHostPort = 7106,
                deviceId = "sim-repair",
                teamId = null,
                platform = null,
                isStudio = false,
                isHeadless = false,
                screenSize = null,
                reinstallDriver = false,
                deviceIndex = null,
                executionPlan = null,
            )
        } returns managedSession(
            sessionId = "repaired-session",
            driver = StubDriver(),
        )

        val resumed = McpSessionRegistry.resumeSession(
            sessionId = handle.sessionId,
            sessionManager = MaestroSessionManager,
        )

        assertThat(resumed).isNotNull()
        assertThat(resumed?.sessionId).isEqualTo("repaired-session")
        assertThat(resumed?.deviceId).isEqualTo("sim-device")
        assertThat(resumed?.reused).isTrue()
        assertThat(resumed?.healthState).isEqualTo("recovered")
        assertThat(resumed?.repairedFromSessionId).isEqualTo("dead-session-to-repair")
        assertThat(McpSessionRegistry.sessionHandle("dead-session-to-repair")).isNull()
        assertThat(McpSessionRegistry.sessionHandle("repaired-session")).isNotNull()
    }

    @Test
    fun withSessionMarksSessionUnhealthyOnConnectionFailure() {
        val managedSession = managedSession(
            sessionId = "with-session-failure",
            driver = StubDriver(),
        )

        val handle = McpSessionRegistry.register(
            managedSession = managedSession,
            deviceId = "sim-2",
        )

        assertThrows<ConnectException> {
            McpSessionRegistry.withSession(handle.sessionId) {
                throw ConnectException("Connection refused")
            }
        }

        val updated = McpSessionRegistry.sessionHandle(handle.sessionId)
        assertThat(updated).isNotNull()
        assertThat(updated?.healthState).isEqualTo("unhealthy")
        assertThat(updated?.lastFailureReason).contains("Connection refused")
    }

    private fun managedSession(
        sessionId: String,
        driver: Driver,
    ): MaestroSessionManager.ManagedSession {
        val maestro = Maestro(driver)
        val session = MaestroSessionManager.MaestroSession(
            maestro = maestro,
            device = null,
        )
        return MaestroSessionManager.ManagedSession(
            sessionId = sessionId,
            session = session,
            platform = Platform.IOS,
            deviceId = "sim-device",
        ) { _ ->
            runCatching { driver.close() }
        }
    }

    private class StubDriver(
        private val deviceInfoFailure: Throwable? = null,
        private val shutdown: Boolean = false,
    ) : Driver {
        override fun name(): String = "Stub Driver"
        override fun open() = Unit
        override fun close() = Unit
        override fun deviceInfo(): DeviceInfo {
            deviceInfoFailure?.let { throw it }
            return DeviceInfo(
                platform = Platform.IOS,
                widthPixels = 1179,
                heightPixels = 2556,
                widthGrid = 393,
                heightGrid = 852,
            )
        }

        override fun launchApp(appId: String, launchArguments: Map<String, Any>) = Unit
        override fun stopApp(appId: String) = Unit
        override fun killApp(appId: String) = Unit
        override fun clearAppState(appId: String) = Unit
        override fun clearKeychain() = Unit
        override fun tap(point: Point) = Unit
        override fun longPress(point: Point) = Unit
        override fun pressKey(code: KeyCode) = Unit
        override fun contentDescriptor(excludeKeyboardElements: Boolean) =
            throw UnsupportedOperationException("unused in McpSessionRegistryTest")

        override fun scrollVertical() = Unit
        override fun isKeyboardVisible(): Boolean = false
        override fun swipe(start: Point, end: Point, durationMs: Long) = Unit
        override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) = Unit
        override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) = Unit
        override fun backPress() = Unit
        override fun inputText(text: String) = Unit
        override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = Unit
        override fun hideKeyboard() = Unit
        override fun takeScreenshot(out: Sink, compressed: Boolean) = Unit
        override fun startScreenRecording(out: Sink): ScreenRecording =
            object : ScreenRecording {
                override fun close() = Unit
            }

        override fun setLocation(latitude: Double, longitude: Double) = Unit
        override fun setOrientation(orientation: DeviceOrientation) = Unit
        override fun eraseText(charactersToErase: Int) = Unit
        override fun setProxy(host: String, port: Int) = Unit
        override fun resetProxy() = Unit
        override fun isShutdown(): Boolean = shutdown
        override fun isUnicodeInputSupported(): Boolean = true
        override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean = true
        override fun waitForAppToSettle(initialHierarchy: maestro.ViewHierarchy?, appId: String?, timeoutMs: Int?) = initialHierarchy
        override fun capabilities(): List<Capability> = emptyList()
        override fun setPermissions(appId: String, permissions: Map<String, String>) = Unit
        override fun addMedia(mediaFiles: List<File>) = Unit
        override fun isAirplaneModeEnabled(): Boolean = false
        override fun setAirplaneMode(enabled: Boolean) = Unit
        override fun queryOnDeviceElements(query: OnDeviceElementQuery) = emptyList<maestro.TreeNode>()
    }
}
