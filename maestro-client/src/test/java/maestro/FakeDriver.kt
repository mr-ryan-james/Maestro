package maestro

import maestro.device.DeviceOrientation
import okio.Sink
import java.io.File

/**
 * Test double for [Driver]. Every member throws until a test subclass overrides
 * the calls it expects; an unexpected driver call fails the test loudly.
 */
open class FakeDriver : Driver {
    override fun name(): String = "FakeDriver"
    override fun open(): Unit = TODO("not expected in this test")
    override fun close(): Unit = TODO("not expected in this test")
    override fun deviceInfo(): DeviceInfo = TODO("not expected in this test")
    override fun launchApp(appId: String, launchArguments: Map<String, Any>): Unit = TODO("not expected in this test")
    override fun stopApp(appId: String): Unit = TODO("not expected in this test")
    override fun killApp(appId: String): Unit = TODO("not expected in this test")
    override fun clearAppState(appId: String): Unit = TODO("not expected in this test")
    override fun clearKeychain(): Unit = TODO("not expected in this test")
    override fun tap(point: Point): Unit = TODO("not expected in this test")
    override fun longPress(point: Point): Unit = TODO("not expected in this test")
    override fun pressKey(code: KeyCode): Unit = TODO("not expected in this test")
    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode = TODO("not expected in this test")
    override fun scrollVertical(): Unit = TODO("not expected in this test")
    override fun isKeyboardVisible(): Boolean = TODO("not expected in this test")
    override fun swipe(start: Point, end: Point, durationMs: Long): Unit = TODO("not expected in this test")
    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long): Unit = TODO("not expected in this test")
    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long): Unit = TODO("not expected in this test")
    override fun backPress(): Unit = TODO("not expected in this test")
    override fun inputText(text: String): Unit = TODO("not expected in this test")
    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean): Unit = TODO("not expected in this test")
    override fun hideKeyboard(): Unit = TODO("not expected in this test")
    override fun takeScreenshot(out: Sink, compressed: Boolean): Unit = TODO("not expected in this test")
    override fun startScreenRecording(out: Sink): ScreenRecording = TODO("not expected in this test")
    override fun setLocation(latitude: Double, longitude: Double): Unit = TODO("not expected in this test")
    override fun setOrientation(orientation: DeviceOrientation): Unit = TODO("not expected in this test")
    override fun eraseText(charactersToErase: Int): Unit = TODO("not expected in this test")
    override fun setProxy(host: String, port: Int): Unit = TODO("not expected in this test")
    override fun resetProxy(): Unit = TODO("not expected in this test")
    override fun isShutdown(): Boolean = false
    override fun isUnicodeInputSupported(): Boolean = TODO("not expected in this test")
    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean = TODO("not expected in this test")
    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? = TODO("not expected in this test")
    override fun capabilities(): List<Capability> = emptyList()
    override fun setPermissions(appId: String, permissions: Map<String, String>): Unit = TODO("not expected in this test")
    override fun addMedia(mediaFiles: List<File>): Unit = TODO("not expected in this test")
    override fun isAirplaneModeEnabled(): Boolean = TODO("not expected in this test")
    override fun setAirplaneMode(enabled: Boolean): Unit = TODO("not expected in this test")
}
