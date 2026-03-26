package dev.mobile.maestro

import android.app.UiAutomation
import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_4
import android.view.KeyEvent.KEYCODE_5
import android.view.KeyEvent.KEYCODE_6
import android.view.KeyEvent.KEYCODE_7
import android.view.KeyEvent.KEYCODE_APOSTROPHE
import android.view.KeyEvent.KEYCODE_AT
import java.util.concurrent.TimeUnit
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_COMMA
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_LEFT_BRACKET
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN
import android.view.KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_POUND
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_SEMICOLON
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_STAR
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiDeviceExt.clickExt
import com.google.android.gms.location.LocationServices
import dev.mobile.maestro.location.FusedLocationProvider
import dev.mobile.maestro.location.LocationManagerProvider
import dev.mobile.maestro.location.MockLocationProvider
import dev.mobile.maestro.location.PlayServices
import dev.mobile.maestro.screenshot.ScreenshotException
import dev.mobile.maestro.screenshot.ScreenshotService
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import maestro_android.MaestroAndroid
import maestro_android.MaestroDriverGrpc
import maestro_android.addMediaResponse
import maestro_android.checkWindowUpdatingResponse
import maestro_android.deviceInfo
import maestro_android.emptyResponse
import maestro_android.eraseAllTextResponse
import maestro_android.inputTextResponse
import maestro_android.launchAppResponse
import maestro_android.screenshotResponse
import maestro_android.setLocationResponse
import maestro_android.tapResponse
import maestro_android.viewHierarchyResponse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask
import kotlin.system.measureTimeMillis

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class MaestroDriverService {

    @Test
    fun grpcServer() {
        Configurator.getInstance()
            .setActionAcknowledgmentTimeout(0L)
            .setWaitForIdleTimeout(0L)
            .setWaitForSelectorTimeout(0L)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiDevice = UiDevice.getInstance(instrumentation)
        val uiAutomation = instrumentation.uiAutomation

        val port = InstrumentationRegistry.getArguments().getString("port", "7001").toInt()

        println("Server running on port [ $port ]")

        NettyServerBuilder.forPort(port)
            .addService(Service(uiDevice, uiAutomation))
            .permitKeepAliveTime(30, TimeUnit.SECONDS) // If a client pings more than once every 30 seconds, terminate the connection
            .permitKeepAliveWithoutCalls(true) // Allow pings even when there are no active streams.
            .keepAliveTimeout(20, TimeUnit.SECONDS) // wait 20 seconds for client to ack the keep alive
            .maxConnectionIdle(30, TimeUnit.MINUTES) // If a client is idle for 30 minutes, send a GOAWAY frame.
            .build()
            .start()

        while (!Thread.interrupted()) {
            Thread.sleep(100)
        }
    }
}

class Service(
    private val uiDevice: UiDevice,
    private val uiAutomation: UiAutomation,
) : MaestroDriverGrpc.MaestroDriverImplBase() {

    private var locationTimerTask : TimerTask? = null
    private val locationTimer = Timer()

    private val screenshotService = ScreenshotService()
    private val mockLocationProviderList = mutableListOf<MockLocationProvider>()
    private val toastAccessibilityListener = ToastAccessibilityListener.start(uiAutomation)

    companion object {
        private const val TAG = "Maestro"
        private const val UPDATE_INTERVAL_IN_MILLIS = 2000L

        private val ERROR_TYPE_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-type", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_MSG_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-message", Metadata.ASCII_STRING_MARSHALLER)
        private val ERROR_CAUSE_KEY: Metadata.Key<String> =
            Metadata.Key.of("error-cause", Metadata.ASCII_STRING_MARSHALLER)
    }

    override fun launchApp(
        request: MaestroAndroid.LaunchAppRequest,
        responseObserver: StreamObserver<MaestroAndroid.LaunchAppResponse>
    ) {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            val intent = context.packageManager.getLaunchIntentForPackage(request.packageName)

            if (intent == null) {
                Log.e("Maestro", "No launcher intent found for package ${request.packageName}")
                responseObserver.onError(RuntimeException("No launcher intent found for package ${request.packageName}"))
                return
            }

            request.argumentsList
                .forEach {
                    when (it.type) {
                        String::class.java.name -> intent.putExtra(it.key, it.value)
                        Boolean::class.java.name -> intent.putExtra(it.key, it.value.toBoolean())
                        Int::class.java.name -> intent.putExtra(it.key, it.value.toInt())
                        Double::class.java.name -> intent.putExtra(it.key, it.value.toDouble())
                        Long::class.java.name -> intent.putExtra(it.key, it.value.toLong())
                        else -> intent.putExtra(it.key, it.value)
                    }
                }
            context.startActivity(intent)

            responseObserver.onNext(launchAppResponse { })
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    override fun deviceInfo(
        request: MaestroAndroid.DeviceInfoRequest,
        responseObserver: StreamObserver<MaestroAndroid.DeviceInfo>
    ) {
        try {
            val windowManager = InstrumentationRegistry.getInstrumentation()
                .context
                .getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)

            responseObserver.onNext(
                deviceInfo {
                    widthPixels = displayMetrics.widthPixels
                    heightPixels = displayMetrics.heightPixels
                }
            )
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    override fun viewHierarchy(
        request: MaestroAndroid.ViewHierarchyRequest,
        responseObserver: StreamObserver<MaestroAndroid.ViewHierarchyResponse>
    ) {
        try {
            refreshAccessibilityCache()
            val stream = ByteArrayOutputStream()

            val ms = measureTimeMillis {
                if (toastAccessibilityListener.getToastAccessibilityNode() != null && !toastAccessibilityListener.isTimedOut()) {
                    Log.d("Maestro", "Requesting view hierarchy with toast")
                    ViewHierarchy.dump(
                        uiDevice,
                        uiAutomation,
                        stream,
                        toastAccessibilityListener.getToastAccessibilityNode()
                    )
                } else {
                    Log.d("Maestro", "Requesting view hierarchy")
                    ViewHierarchy.dump(
                        uiDevice,
                        uiAutomation,
                        stream
                    )
                }
            }
            Log.d("Maestro", "View hierarchy received in $ms ms")

            responseObserver.onNext(
                viewHierarchyResponse {
                    hierarchy = stream.toString(Charsets.UTF_8.name())
                }
            )
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    /**
     * Clears the in-process Accessibility cache, removing any stale references. Because the
     * AccessibilityInteractionClient singleton stores copies of AccessibilityNodeInfo instances,
     * calls to public APIs such as `recycle` do not guarantee cached references get updated.
     */
    private fun refreshAccessibilityCache() {
        try {
            uiDevice.waitForIdle(500)
            uiAutomation.serviceInfo = null
        } catch (nullExp: NullPointerException) {
            /* no-op */
        }
    }

    override fun tap(
        request: MaestroAndroid.TapRequest,
        responseObserver: StreamObserver<MaestroAndroid.TapResponse>
    ) {
        try {
            uiDevice.clickExt(
                request.x,
                request.y
            )

            responseObserver.onNext(tapResponse {})
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    override fun addMedia(responseObserver: StreamObserver<MaestroAndroid.AddMediaResponse>): StreamObserver<MaestroAndroid.AddMediaRequest> {
        return object : StreamObserver<MaestroAndroid.AddMediaRequest> {

            var outputStream: OutputStream? = null

            override fun onNext(value: MaestroAndroid.AddMediaRequest) {
                if (outputStream == null) {
                    outputStream = MediaStorage.getOutputStream(
                        value.mediaName,
                        value.mediaExt
                    )
                }
                value.payload.data.writeTo(outputStream)
            }

            override fun onError(t: Throwable) {
                responseObserver.onError(t.internalError())
            }

            override fun onCompleted() {
                responseObserver.onNext(addMediaResponse { })
                responseObserver.onCompleted()
            }

        }
    }

    override fun eraseAllText(
        request: MaestroAndroid.EraseAllTextRequest,
        responseObserver: StreamObserver<MaestroAndroid.EraseAllTextResponse>
    ) {
        try {
            val requestedCharactersToErase = request.charactersToErase
            val erasedCharacters = eraseTextFromFocusedEditable(requestedCharactersToErase)

            Log.i(
                TAG,
                "[eraseText] completed requested=$requestedCharactersToErase erased=$erasedCharacters"
            )

            responseObserver.onNext(eraseAllTextResponse { })
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    private fun eraseTextFromFocusedEditable(requestedCharactersToErase: Int): Int {
        val startedAtMs = SystemClock.elapsedRealtime()

        if (requestedCharactersToErase <= 0) {
            Log.i(TAG, "[eraseText] skipping non-positive request=$requestedCharactersToErase")
            return 0
        }

        var node: AccessibilityNodeInfo? = null
        try {
            node = findBestEditableTarget()
            if (node == null) {
                throw IllegalStateException("[eraseText] no editable target found")
            }

            if (!node.isFocused && supportsAction(node, AccessibilityNodeInfo.ACTION_FOCUS)) {
                val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Log.i(TAG, "[eraseText] ACTION_FOCUS result=$focused")
                node.recycle()
                node = null
                SystemClock.sleep(50)
                refreshAccessibilityCache()
                node = findBestEditableTarget()
            }

            if (node == null) {
                throw IllegalStateException("[eraseText] editable target disappeared after focus")
            }

            val startingTextLength = node.safeTextLength()
            val targetDeleteCount = requestedCharactersToErase.coerceAtMost(startingTextLength)

            Log.i(
                TAG,
                "[eraseText] start requested=$requestedCharactersToErase startingLength=$startingTextLength targetDeletes=$targetDeleteCount"
            )

            if (startingTextLength == 0 || targetDeleteCount == 0) {
                Log.i(
                    TAG,
                    "[eraseText] field already empty; nothing to delete durationMs=${SystemClock.elapsedRealtime() - startedAtMs}"
                )
                return 0
            }

            if (targetDeleteCount == startingTextLength) {
                attemptFastFullClear(node, startingTextLength)?.let { return it }
            }

            var actualDeletes = 0
            var currentLength = startingTextLength
            var exitReason = "requested_limit_reached"

            while (actualDeletes < targetDeleteCount && currentLength > 0) {
                val pressedDelete = pressDeleteFast()
                actualDeletes += 1
                SystemClock.sleep(10)

                val refreshedLength = refreshEditableTextLength(node)
                Log.i(
                    TAG,
                    "[eraseText] deleteIndex=$actualDeletes pressed=$pressedDelete lengthBefore=$currentLength lengthAfter=$refreshedLength"
                )

                currentLength = refreshedLength
                if (currentLength == 0) {
                    exitReason = "field_empty"
                    break
                }
            }

            if (actualDeletes == targetDeleteCount && currentLength > 0) {
                exitReason = "requested_limit_reached"
            }

            Log.i(
                TAG,
                "[eraseText] exitReason=$exitReason startingLength=$startingTextLength finalLength=$currentLength actualDeletes=$actualDeletes durationMs=${SystemClock.elapsedRealtime() - startedAtMs}"
            )

            if (targetDeleteCount >= startingTextLength && currentLength > 0) {
                throw IllegalStateException(
                    "[eraseText] field not empty after full-clear request: requested=$requestedCharactersToErase startingLength=$startingTextLength finalLength=$currentLength actualDeletes=$actualDeletes"
                )
            }

            return actualDeletes
        } finally {
            node?.recycle()
        }
    }

    private fun attemptFastFullClear(
        node: AccessibilityNodeInfo,
        startingTextLength: Int
    ): Int? {
        if (trySelectAllAndPasteEmpty(node, startingTextLength)) {
            return startingTextLength
        }

        if (trySelectAllAndDelete(node, startingTextLength)) {
            return startingTextLength
        }

        if (trySelectAllAndCut(node, startingTextLength)) {
            return startingTextLength
        }

        if (trySetTextEmpty(node)) {
            return startingTextLength
        }

        return null
    }

    private fun trySelectAllAndDelete(
        node: AccessibilityNodeInfo,
        startingTextLength: Int
    ): Boolean {
        if (!selectAllText(node, startingTextLength)) {
            return false
        }

        SystemClock.sleep(35)
        val pressedDelete = pressDeleteFast()
        val finalLength = waitForEditableTextLength(
            node = node,
            timeoutMs = 350,
            operation = "selectAll+delete"
        ) { it == 0 }
        Log.i(TAG, "[eraseText] selectAll+delete pressed=$pressedDelete finalLength=$finalLength")
        return finalLength == 0
    }

    private fun trySelectAllAndPasteEmpty(
        node: AccessibilityNodeInfo,
        startingTextLength: Int
    ): Boolean {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) {
            return false
        }

        if (!withTemporaryClipboardText("") {
                if (!selectAllText(node, startingTextLength)) {
                    return@withTemporaryClipboardText false
                }

                SystemClock.sleep(35)
                val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.i(TAG, "[eraseText] ACTION_PASTE(emptyClipboard) result=$pasted")
                if (!pasted) {
                    return@withTemporaryClipboardText false
                }

                waitForEditableTextLength(
                    node = node,
                    timeoutMs = 350,
                    operation = "selectAll+pasteEmpty"
                ) { it == 0 } == 0
            }
        ) {
            return false
        }

        Log.i(TAG, "[eraseText] selectAll+pasteEmpty finalLength=0")
        return true
    }

    private fun trySelectAllAndCut(
        node: AccessibilityNodeInfo,
        startingTextLength: Int
    ): Boolean {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_CUT)) {
            return false
        }

        if (!selectAllText(node, startingTextLength)) {
            return false
        }

        SystemClock.sleep(50)
        val cut = node.performAction(AccessibilityNodeInfo.ACTION_CUT)
        Log.i(TAG, "[eraseText] ACTION_CUT result=$cut")
        if (!cut) {
            return false
        }

        val finalLength = waitForEditableTextLength(
            node = node,
            timeoutMs = 350,
            operation = "selectAll+cut"
        ) { it == 0 }
        Log.i(TAG, "[eraseText] selectAll+cut finalLength=$finalLength")
        return finalLength == 0
    }

    private fun trySetTextEmpty(node: AccessibilityNodeInfo): Boolean {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) {
            return false
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                ""
            )
        }
        val cleared = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "[eraseText] ACTION_SET_TEXT(empty) result=$cleared")
        if (!cleared) {
            return false
        }

        val finalLength = waitForEditableTextLength(
            node = node,
            timeoutMs = 350,
            operation = "setTextEmpty"
        ) { it == 0 }
        Log.i(TAG, "[eraseText] setTextEmpty finalLength=$finalLength")
        return finalLength == 0
    }

    private fun selectAllText(node: AccessibilityNodeInfo, textLength: Int): Boolean {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION)) {
            return false
        }

        val selectAllArgs = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                0
            )
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                textLength
            )
        }
        val selected = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            selectAllArgs
        )
        Log.i(TAG, "[eraseText] ACTION_SET_SELECTION(all) result=$selected")
        return selected
    }

    private fun refreshEditableTextLength(node: AccessibilityNodeInfo): Int {
        if (node.refresh()) {
            return node.safeTextLength()
        }

        refreshAccessibilityCache()
        return findBestEditableTarget().useAndRecycle { it.safeTextLength() }
    }

    private fun pressDeleteFast(): Boolean {
        return try {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
            true
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "[eraseText] sendKeyDownUpSync(KEYCODE_DEL) failed; falling back to UiDevice.pressDelete()",
                t
            )
            uiDevice.pressDelete()
        }
    }

    private fun waitForEditableTextLength(
        node: AccessibilityNodeInfo,
        timeoutMs: Long,
        operation: String,
        predicate: (Int) -> Boolean
    ): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var currentLength = refreshEditableTextLength(node)

        while (!predicate(currentLength) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25)
            currentLength = refreshEditableTextLength(node)
        }

        Log.i(
            TAG,
            "[editableWait] operation=$operation finalLength=$currentLength timeoutMs=$timeoutMs"
        )
        return currentLength
    }

    override fun inputText(
        request: MaestroAndroid.InputTextRequest,
        responseObserver: StreamObserver<MaestroAndroid.InputTextResponse>
    ) {
        try {
            Log.i(TAG, "[inputText] called with ${request.text.length} chars")

            setSystemClipboard(request.text)

            val pasted = pasteFromClipboardIntoFocusedEditable()
            if (!pasted) {
                Log.w(TAG, "[inputText] paste path unavailable, falling back to hardware keys")
                request.text.forEach { ch ->
                    setText(ch.toString())
                    SystemClock.sleep(35)
                }
            }

            responseObserver.onNext(inputTextResponse {})
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    /**
     * Put text on the real Android system clipboard so ACTION_PASTE can use it.
     */
    private fun setSystemClipboard(text: String) {
        val clipboard = getClipboardManager()
        clipboard.setPrimaryClip(ClipData.newPlainText("maestro-input", text))
        SystemClock.sleep(50)
    }

    private fun withTemporaryClipboardText(
        text: String,
        block: () -> Boolean
    ): Boolean {
        val clipboard = getClipboardManager()
        val previousClip = clipboard.primaryClip

        clipboard.setPrimaryClip(ClipData.newPlainText("maestro-input", text))
        SystemClock.sleep(50)

        return try {
            block()
        } finally {
            if (previousClip != null) {
                clipboard.setPrimaryClip(previousClip)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            SystemClock.sleep(25)
        }
    }

    private fun getClipboardManager(): ClipboardManager {
        return InstrumentationRegistry.getInstrumentation()
            .context
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    /**
     * Paste clipboard content into the focused editable field via ACTION_PASTE.
     * This goes through EditText.onTextContextMenuItem → InputConnection.commitText,
     * which is the code path React Native's controlled TextInput actually listens to.
     * Returns false if no suitable target found or paste failed.
     */
    private fun pasteFromClipboardIntoFocusedEditable(): Boolean {
        refreshAccessibilityCache()

        var node: AccessibilityNodeInfo? = findBestEditableTarget()
        try {
            if (node == null) {
                Log.w(TAG, "[inputText] no editable target found")
                return false
            }

            // Focus the node if it isn't already focused
            if (!node.isFocused && supportsAction(node, AccessibilityNodeInfo.ACTION_FOCUS)) {
                val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Log.i(TAG, "[inputText] ACTION_FOCUS result=$focused")

                node.recycle()
                node = null

                SystemClock.sleep(50)
                refreshAccessibilityCache()
                node = findBestEditableTarget()
                if (node == null) {
                    Log.w(TAG, "[inputText] target disappeared after ACTION_FOCUS")
                    return false
                }
            }

            if (!supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) {
                Log.w(TAG, "[inputText] target does not expose ACTION_PASTE")
                return false
            }

            val startingTextLength = node.safeTextLength()
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "[inputText] ACTION_PASTE result=$pasted")
            if (!pasted) {
                return false
            }

            SystemClock.sleep(100)
            val finalLength = refreshEditableTextLength(node)
            Log.i(
                TAG,
                "[inputText] ACTION_PASTE startingLength=$startingTextLength finalLength=$finalLength"
            )
            return true
        } finally {
            node?.recycle()
        }
    }

    /**
     * Find the best editable target in the active window with cascading fallback:
     * 1. focused + editable + supports paste
     * 2. focused + editable
     * 3. editable + supports paste
     * 4. any editable
     */
    private fun findBestEditableTarget(): AccessibilityNodeInfo? {
        val root = uiAutomation.rootInActiveWindow ?: return null
        try {
            return findNode(root) {
                it.isEditable &&
                    it.isFocused &&
                    supportsAction(it, AccessibilityNodeInfo.ACTION_PASTE)
            } ?: findNode(root) {
                it.isEditable && it.isFocused
            } ?: findNode(root) {
                it.isEditable &&
                    supportsAction(it, AccessibilityNodeInfo.ACTION_PASTE)
            } ?: findNode(root) {
                it.isEditable
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Generic accessibility tree walker. Returns the first node matching the predicate,
     * with proper recycle() on non-matching nodes.
     */
    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findNode(child, predicate)
                if (result != null) {
                    return result
                }
            } finally {
                child.recycle()
            }
        }

        return null
    }

    /**
     * Check if an accessibility node supports a given action.
     */
    @Suppress("DEPRECATION")
    private fun supportsAction(node: AccessibilityNodeInfo, action: Int): Boolean {
        return node.actions and action != 0
    }

    private fun AccessibilityNodeInfo.safeTextLength(): Int {
        if (isShowingHintText) {
            return 0
        }
        return text?.toString()?.length ?: 0
    }

    private inline fun <T> AccessibilityNodeInfo?.useAndRecycle(block: (AccessibilityNodeInfo) -> T): T {
        if (this == null) {
            throw IllegalStateException("[eraseText] editable target missing during refresh")
        }

        try {
            return block(this)
        } finally {
            recycle()
        }
    }

    override fun screenshot(
        request: MaestroAndroid.ScreenshotRequest,
        responseObserver: StreamObserver<MaestroAndroid.ScreenshotResponse>
    ) {
        try {
            val bitmap = screenshotService.takeScreenshotWithRetry { uiAutomation.takeScreenshot() }
            val bytes = screenshotService.encodePng(bitmap)
            responseObserver.onNext(screenshotResponse { this.bytes = bytes })
            responseObserver.onCompleted()
        } catch (e: NullPointerException) {
            Log.e(TAG, "Screenshot failed with NullPointerException: ${e.message}", e)
            responseObserver.onError(e.internalError())
        } catch (e: ScreenshotException) {
            Log.e(TAG, "Screenshot failed with ScreenshotException: ${e.message}", e)
            responseObserver.onError(e.internalError())
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed with: ${e.message}", e)
            responseObserver.onError(e.internalError())
        }
    }

    override fun isWindowUpdating(
        request: MaestroAndroid.CheckWindowUpdatingRequest,
        responseObserver: StreamObserver<MaestroAndroid.CheckWindowUpdatingResponse>
    ) {
        try {
            responseObserver.onNext(checkWindowUpdatingResponse {
                isWindowUpdating = uiDevice.waitForWindowUpdate(request.appId, 500)
            })
            responseObserver.onCompleted()
        } catch (e: Throwable) {
            responseObserver.onError(e.internalError())
        }
    }

    override fun disableLocationUpdates(
        request: MaestroAndroid.EmptyRequest,
        responseObserver: StreamObserver<MaestroAndroid.EmptyResponse>
    ) {
        try {
            Log.d(TAG, "[Start] Disabling location updates")
            locationTimerTask?.cancel()
            locationTimer.cancel()
            mockLocationProviderList.forEach {
                it.disable()
            }
            Log.d(TAG, "[Done] Disabling location updates")
            responseObserver.onNext(emptyResponse {  })
            responseObserver.onCompleted()
        } catch (exception: Exception) {
            responseObserver.onError(exception.internalError())
        }
    }

    override fun enableMockLocationProviders(
        request: MaestroAndroid.EmptyRequest,
        responseObserver: StreamObserver<MaestroAndroid.EmptyResponse>
    ) {
        try {
            Log.d(TAG, "[Start] Enabling mock location providers")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager

            mockLocationProviderList.addAll(
                createMockProviders(context, locationManager)
            )

            mockLocationProviderList.forEach {
                it.enable()
            }
            Log.d(TAG, "[Done] Enabling mock location providers")

            responseObserver.onNext(emptyResponse {  })
            responseObserver.onCompleted()
        } catch (exception: Exception) {
            Log.e(TAG, "Error while enabling mock location provider", exception)
            responseObserver.onError(exception.internalError())
        }
    }

    private fun createMockProviders(
        context: Context,
        locationManager: LocationManager
    ): List<MockLocationProvider> {
        val playServices = PlayServices()
        val fusedLocationProvider: MockLocationProvider? = if (playServices.isAvailable(context)) {
            val fusedLocationProviderClient =
                LocationServices.getFusedLocationProviderClient(context)
            FusedLocationProvider(fusedLocationProviderClient)
        } else {
            null
        }
        return (locationManager.allProviders.mapNotNull {
            if (it.equals(LocationManager.PASSIVE_PROVIDER)) {
                null
            } else {
                val mockProvider = createLocationManagerMockProvider(locationManager, it)
                mockProvider
            }
        } + fusedLocationProvider).mapNotNull { it }
    }

    private fun createLocationManagerMockProvider(
        locationManager: LocationManager,
        providerName: String?
    ): MockLocationProvider? {
        if (providerName == null) {
            return null
        }
        // API level check for existence of provider properties
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API level 31 and above
            val providerProperties =
                locationManager.getProviderProperties(providerName) ?: return null
            return LocationManagerProvider(
                locationManager,
                providerName,
                providerProperties.hasNetworkRequirement(),
                providerProperties.hasSatelliteRequirement(),
                providerProperties.hasCellRequirement(),
                providerProperties.hasMonetaryCost(),
                providerProperties.hasAltitudeSupport(),
                providerProperties.hasSpeedSupport(),
                providerProperties.hasBearingSupport(),
                providerProperties.powerUsage,
                providerProperties.accuracy
            )
        }
        val provider = locationManager.getProvider(providerName) ?: return null
        return LocationManagerProvider(
            locationManager,
            provider.name,
            provider.requiresNetwork(),
            provider.requiresSatellite(),
            provider.requiresCell(),
            provider.hasMonetaryCost(),
            provider.supportsAltitude(),
            provider.supportsSpeed(),
            provider.supportsBearing(),
            provider.powerRequirement,
            provider.accuracy
        )
    }


    override fun setLocation(
        request: MaestroAndroid.SetLocationRequest,
        responseObserver: StreamObserver<MaestroAndroid.SetLocationResponse>
    ) {
        try {
            if (locationTimerTask != null) {
                locationTimerTask?.cancel()
            }

            locationTimerTask = object : TimerTask() {
                override fun run() {
                    mockLocationProviderList.forEach {
                        val latitude = request.latitude
                        val longitude = request.longitude
                        Log.d(TAG, "Setting location latitude: $latitude and longitude: $longitude for ${it.getProviderName()}")
                        val location = Location(it.getProviderName()).apply {
                            setLatitude(latitude)
                            setLongitude(longitude)
                            accuracy = Criteria.ACCURACY_FINE.toFloat()
                            altitude = 0.0
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        }
                        it.setLocation(location)
                    }
                }
            }
            locationTimer.schedule(
                locationTimerTask,
                0,
                UPDATE_INTERVAL_IN_MILLIS
            )
            responseObserver.onNext(setLocationResponse { })
            responseObserver.onCompleted()
        } catch (t: Throwable) {
            responseObserver.onError(t.internalError())
        }
    }

    private fun setText(text: String) {
        for (element in text) {
            Log.d("Maestro", element.code.toString())
            when (element.code) {
                in 48..57 -> {
                    /** 0~9 **/
                    uiDevice.pressKeyCode(element.code - 41)
                }

                in 65..90 -> {
                    /** A~Z **/
                    uiDevice.pressKeyCode(element.code - 36, 1)
                }

                in 97..122 -> {
                    /** a~z **/
                    uiDevice.pressKeyCode(element.code - 68)
                }

                ';'.code -> uiDevice.pressKeyCode(KEYCODE_SEMICOLON)
                '='.code -> uiDevice.pressKeyCode(KEYCODE_EQUALS)
                ','.code -> uiDevice.pressKeyCode(KEYCODE_COMMA)
                '-'.code -> uiDevice.pressKeyCode(KEYCODE_MINUS)
                '.'.code -> uiDevice.pressKeyCode(KEYCODE_PERIOD)
                '/'.code -> uiDevice.pressKeyCode(KEYCODE_SLASH)
                '`'.code -> uiDevice.pressKeyCode(KEYCODE_GRAVE)
                '\''.code -> uiDevice.pressKeyCode(KEYCODE_APOSTROPHE)
                '['.code -> uiDevice.pressKeyCode(KEYCODE_LEFT_BRACKET)
                ']'.code -> uiDevice.pressKeyCode(KEYCODE_RIGHT_BRACKET)
                '\\'.code -> uiDevice.pressKeyCode(KEYCODE_BACKSLASH)
                ' '.code -> uiDevice.pressKeyCode(KEYCODE_SPACE)
                '@'.code -> uiDevice.pressKeyCode(KEYCODE_AT)
                '#'.code -> uiDevice.pressKeyCode(KEYCODE_POUND)
                '*'.code -> uiDevice.pressKeyCode(KEYCODE_STAR)
                '('.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_LEFT_PAREN)
                ')'.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_RIGHT_PAREN)
                '+'.code -> uiDevice.pressKeyCode(KEYCODE_NUMPAD_ADD)
                '!'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_1)
                '$'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_4)
                '%'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_5)
                '^'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_6)
                '&'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_7)
                '"'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_APOSTROPHE)
                '{'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_LEFT_BRACKET)
                '}'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_RIGHT_BRACKET)
                ':'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_SEMICOLON)
                '|'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_BACKSLASH)
                '<'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_COMMA)
                '>'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_PERIOD)
                '?'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_SLASH)
                '~'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_GRAVE)
                '_'.code -> keyPressShiftedToEvents(uiDevice, KEYCODE_MINUS)
            }
        }
    }

    private fun keyPressShiftedToEvents(uiDevice: UiDevice, keyCode: Int) {
        uiDevice.pressKeyCode(keyCode, META_SHIFT_LEFT_ON)
    }

    internal fun Throwable.internalError(): StatusRuntimeException {
        val trailers = Metadata().apply {
            put(ERROR_TYPE_KEY, this@internalError::class.java.name)
            this@internalError.message?.let { put(ERROR_MSG_KEY, it) }
            this@internalError.cause?.let { put(ERROR_CAUSE_KEY, it.toString()) }
        }
        return Status.INTERNAL.withDescription(message).asRuntimeException(trailers)
    }

    enum class FileType(val ext: String, val mimeType: String) {
        JPG("jpg", "image/jpg"),
        JPEG("jpeg", "image/jpeg"),
        PNG("png", "image/png"),
        GIF("gif", "image/gif"),
        MP4("mp4", "video/mp4"),
    }
}
