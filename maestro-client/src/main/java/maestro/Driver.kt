/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro

import maestro.device.DeviceOrientation
import okio.Sink
import java.io.File

interface Driver {

    fun name(): String

    fun open()

    fun close()

    fun deviceInfo(): DeviceInfo

    fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
    )

    fun stopApp(appId: String)

    fun killApp(appId: String)

    fun clearAppState(appId: String)

    fun clearKeychain()

    fun tap(point: Point)

    fun longPress(point: Point)

    fun pressKey(code: KeyCode)

    fun contentDescriptor(excludeKeyboardElements: Boolean = false): TreeNode

    fun scrollVertical()

    fun isKeyboardVisible(): Boolean

    fun swipe(start: Point, end: Point, durationMs: Long)

    fun swipe(swipeDirection: SwipeDirection, durationMs: Long)

    fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long)

    fun backPress()

    fun inputText(text: String)

    fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean)

    fun hideKeyboard()

    fun takeScreenshot(out: Sink, compressed: Boolean)

    fun startScreenRecording(out: Sink): ScreenRecording

    fun setLocation(latitude: Double, longitude: Double)

    fun setOrientation(orientation: DeviceOrientation)

    fun eraseText(charactersToErase: Int)

    fun setProxy(host: String, port: Int)

    fun resetProxy()

    fun isShutdown(): Boolean

    fun isUnicodeInputSupported(): Boolean

    fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean

    fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int? = null): ViewHierarchy?

    fun capabilities(): List<Capability>

    fun setPermissions(appId: String, permissions: Map<String, String>)

    fun addMedia(mediaFiles: List<File>)

    fun isAirplaneModeEnabled(): Boolean

    fun setAirplaneMode(enabled: Boolean)

    fun setAndroidChromeDevToolsEnabled(enabled: Boolean) = Unit

    fun queryOnDeviceElements(query: OnDeviceElementQuery): List<TreeNode> {
        return listOf()
    }

    fun automationSnapshot(
        request: AutomationSnapshotRequest = AutomationSnapshotRequest(),
    ): AutomationSnapshot {
        return contentDescriptor(excludeKeyboardElements = request.excludeKeyboardElements)
            .toAutomationSnapshot(request)
    }

    fun queryAutomationElements(
        request: AutomationQueryRequest,
    ): AutomationQueryResult {
        return contentDescriptor(excludeKeyboardElements = request.excludeKeyboardElements)
            .queryAutomationElements(request)
    }

    fun awaitAutomation(
        request: AutomationWaitRequest,
    ): AutomationWaitResult {
        val startedAt = System.currentTimeMillis()
        var lastSnapshot: AutomationSnapshot? = null

        while (true) {
            val queryRequest = AutomationQueryRequest(
                selectors = request.selectors,
                interactiveOnly = request.interactiveOnly,
                fields = request.fields,
                maxDepth = request.maxDepth,
                includeStatusBars = request.includeStatusBars,
                includeSafariWebViews = request.includeSafariWebViews,
                excludeKeyboardElements = request.excludeKeyboardElements,
            )
            val queryResult = queryAutomationElements(queryRequest)
            val anyMatchVisible = queryResult.matches.any { it.matchCount > 0 }
            val satisfied = if (request.notVisible) !anyMatchVisible else anyMatchVisible

            if (satisfied) {
                lastSnapshot = automationSnapshot(
                    AutomationSnapshotRequest(
                        mode = AutomationSnapshotMode.MINIMAL,
                        interactiveOnly = request.interactiveOnly,
                        fields = request.fields,
                        maxDepth = request.maxDepth,
                        includeStatusBars = request.includeStatusBars,
                        includeSafariWebViews = request.includeSafariWebViews,
                        excludeKeyboardElements = request.excludeKeyboardElements,
                    ),
                )
                return AutomationWaitResult(
                    satisfied = true,
                    source = queryResult.source,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    token = queryResult.token,
                    snapshot = lastSnapshot,
                )
            }

            if (System.currentTimeMillis() - startedAt >= request.timeoutMs) {
                return AutomationWaitResult(
                    satisfied = false,
                    source = queryResult.source,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    token = queryResult.token,
                    snapshot = lastSnapshot,
                )
            }

            Thread.sleep(request.pollIntervalMs.coerceAtLeast(25L))
        }
    }

}
