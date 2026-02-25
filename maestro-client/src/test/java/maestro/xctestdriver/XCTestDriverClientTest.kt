package maestro.xctestdriver

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.ios.MockXCTestInstaller
import maestro.utils.network.XCUITestServerError
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.api.DeviceInfo
import xcuitest.api.Error
import java.net.InetAddress

class XCTestDriverClientTest {

    @Test
    fun `it should return the 4xx response as is without retrying`() {
        // given
        val mockWebServer = MockWebServer()
        try {
            val mapper = jacksonObjectMapper()
            val error = Error(errorMessage = "This is bad request, failure", errorCode = "bad-request")
            val mockResponse = MockResponse().apply {
                setResponseCode(401)
                setBody(mapper.writeValueAsString(error))
            }
            mockWebServer.enqueue(mockResponse)
            mockWebServer.start(InetAddress.getByName("localhost"), 22087)
            val httpUrl = mockWebServer.url("/deviceInfo")

            // when
            val simulator = MockXCTestInstaller.Simulator()
            val mockXCTestInstaller = MockXCTestInstaller(simulator)
            val xcTestDriverClient = XCTestDriverClient(
                mockXCTestInstaller,
                XCTestClient("localhost", 22087)
            )


            // then
            assertThrows<XCUITestServerError.BadRequest> {
                xcTestDriverClient.deviceInfo(httpUrl)
            }
            mockXCTestInstaller.assertInstallationRetries(0)
        } finally {
            mockWebServer.shutdown()
        }
    }

    @Test
    fun `it should return the 200 response as is without retrying`() {
        // given
        val mockWebServer = MockWebServer()
        try {
            val mapper = jacksonObjectMapper()
            val expectedDeviceInfo = DeviceInfo(1123, 5000, 1223, 1123)
            val mockResponse = MockResponse().apply {
                setResponseCode(200)
                setBody(mapper.writeValueAsString(expectedDeviceInfo))
            }
            mockWebServer.enqueue(mockResponse)
            mockWebServer.start(InetAddress.getByName("localhost"), 22087)
            val httpUrl = mockWebServer.url("/deviceInfo")

            // when
            val simulator = MockXCTestInstaller.Simulator()
            val mockXCTestInstaller = MockXCTestInstaller(simulator)
            val xcTestDriverClient = XCTestDriverClient(
                mockXCTestInstaller,
                XCTestClient("localhost", 22087)
            )
            val actualDeviceInfo = xcTestDriverClient.deviceInfo(httpUrl)

            // then
            assertThat(actualDeviceInfo).isEqualTo(expectedDeviceInfo)
            mockXCTestInstaller.assertInstallationRetries(0)
        } finally {
            mockWebServer.shutdown()
        }
    }

    @Test
    fun `it should retry viewHierarchy for AX transient unknown failure payload`() {
        // given
        val mockWebServer = MockWebServer()
        try {
            val mapper = jacksonObjectMapper()
            val transientError = Error(
                errorMessage = "Error getting element frame kAXErrorInvalidUIElement",
                errorCode = "internal"
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody(mapper.writeValueAsString(transientError))
            )
            enqueueViewHierarchySuccess(mockWebServer)
            mockWebServer.start(InetAddress.getByName("localhost"), 22087)

            // when
            val xcTestDriverClient = createDriverClient()
            val hierarchy = xcTestDriverClient.viewHierarchy(emptySet(), excludeKeyboardElements = false)

            // then
            assertThat(hierarchy.depth).isEqualTo(1)
            assertThat(mockWebServer.requestCount).isEqualTo(2)
        } finally {
            mockWebServer.shutdown()
        }
    }

    @Test
    fun `it should retry viewHierarchy for AX transient app crash payload`() {
        // given
        val mockWebServer = MockWebServer()
        try {
            val mapper = jacksonObjectMapper()
            val transientError = Error(
                errorMessage = "Error getting main window kAXErrorCannotComplete",
                errorCode = "internal"
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody(mapper.writeValueAsString(transientError))
            )
            enqueueViewHierarchySuccess(mockWebServer)
            mockWebServer.start(InetAddress.getByName("localhost"), 22087)

            // when
            val xcTestDriverClient = createDriverClient()
            val hierarchy = xcTestDriverClient.viewHierarchy(emptySet(), excludeKeyboardElements = false)

            // then
            assertThat(hierarchy.depth).isEqualTo(1)
            assertThat(mockWebServer.requestCount).isEqualTo(2)
        } finally {
            mockWebServer.shutdown()
        }
    }

    @Test
    fun `it should not retry viewHierarchy for non transient 500 payload`() {
        // given
        val mockWebServer = MockWebServer()
        try {
            val mapper = jacksonObjectMapper()
            val internalError = Error(
                errorMessage = "Unexpected XCTest server failure",
                errorCode = "internal"
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(500)
                    .setBody(mapper.writeValueAsString(internalError))
            )
            mockWebServer.start(InetAddress.getByName("localhost"), 22087)

            // when
            val xcTestDriverClient = createDriverClient()

            // then
            assertThrows<XCUITestServerError.UnknownFailure> {
                xcTestDriverClient.viewHierarchy(emptySet(), excludeKeyboardElements = false)
            }
            assertThat(mockWebServer.requestCount).isEqualTo(1)
        } finally {
            mockWebServer.shutdown()
        }
    }

    @ParameterizedTest
    @MethodSource("provideAppCrashMessage")
    fun `it should throw app crash exception correctly`(errorMessage: String) {
        // given
        val mockWebServer = MockWebServer()
        try {
            val mapper = jacksonObjectMapper()
            val expectedDeviceInfo = Error(errorMessage = errorMessage, errorCode = "internal")
            val mockResponse = MockResponse().apply {
                setResponseCode(500)
                setBody(mapper.writeValueAsString(expectedDeviceInfo))
            }
            mockWebServer.enqueue(mockResponse)
            mockWebServer.start(InetAddress.getByName( "localhost"), 22087)
            val httpUrl = mockWebServer.url("/deviceInfo")

            // when
            val simulator = MockXCTestInstaller.Simulator()
            val mockXCTestInstaller = MockXCTestInstaller(simulator)
            val xcTestDriverClient = XCTestDriverClient(
                mockXCTestInstaller,
                XCTestClient("localhost", 22087)
            )


            // then
            assertThrows<XCUITestServerError.AppCrash> {
                xcTestDriverClient.deviceInfo(httpUrl)
            }
            mockXCTestInstaller.assertInstallationRetries(0)
        } finally {
            mockWebServer.shutdown()
        }
    }

    companion object {

        @JvmStatic
        fun provideAppCrashMessage(): Array<String> {
            return arrayOf(
                "Application com.app.id is not running",
                "Lost connection to the application (pid 19985).",
                "Error getting main window kAXErrorCannotComplete",
                "Error getting main window Unknown kAXError value -25218"
            )
        }

        private fun enqueueViewHierarchySuccess(mockWebServer: MockWebServer) {
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "axElement": {
                            "label": "",
                            "elementType": 0,
                            "identifier": "",
                            "horizontalSizeClass": 0,
                            "windowContextID": 0,
                            "verticalSizeClass": 0,
                            "selected": false,
                            "displayID": 0,
                            "hasFocus": false,
                            "placeholderValue": null,
                            "value": null,
                            "frame": {
                              "X": 0.0,
                              "Y": 0.0,
                              "Width": 1.0,
                              "Height": 1.0
                            },
                            "enabled": true,
                            "title": "",
                            "children": []
                          },
                          "depth": 1
                        }
                        """.trimIndent()
                    )
            )
        }

        private fun createDriverClient(): XCTestDriverClient {
            val simulator = MockXCTestInstaller.Simulator()
            val mockXCTestInstaller = MockXCTestInstaller(simulator)
            return XCTestDriverClient(
                mockXCTestInstaller,
                XCTestClient("localhost", 22087)
            )
        }
    }
}
