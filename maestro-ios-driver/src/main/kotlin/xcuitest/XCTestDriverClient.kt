package xcuitest

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import hierarchy.ViewHierarchy
import maestro.utils.HttpClient
import maestro.utils.network.XCUITestServerError
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import xcuitest.api.*
import xcuitest.installer.XCTestInstaller
import kotlin.time.Duration.Companion.seconds

class XCTestDriverClient(
    private val installer: XCTestInstaller,
    private val okHttpClient: OkHttpClient = HttpClient.build(
        name = "XCTestDriverClient",
        readTimeout = 200.seconds,
        connectTimeout = 1.seconds,
        callTimeout = 200.seconds
    ),
    private val reinstallDriver: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(XCTestDriverClient::class.java)
    private val axRetryCount = System.getenv("MAESTRO_IOS_AX_RETRY_COUNT")
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: 3
    private val axRetryBaseMs = System.getenv("MAESTRO_IOS_AX_RETRY_BASE_MS")
        ?.toLongOrNull()
        ?.coerceAtLeast(50L)
        ?: 200L

    private lateinit var client: XCTestClient

    constructor(installer: XCTestInstaller, client: XCTestClient, reinstallDriver: Boolean = true): this(installer, reinstallDriver = reinstallDriver) {
        this.client = client
    }

    fun restartXCTestRunner() {
        if(reinstallDriver) {
            logger.trace("Restarting XCTest Runner (uninstalling, installing and starting)")
            installer.uninstall()
            logger.trace("XCTest Runner uninstalled, will install and start it")
        }

        client = installer.start()
    }

    private val mapper = jacksonObjectMapper()

    fun viewHierarchy(installedApps: Set<String>, excludeKeyboardElements: Boolean): ViewHierarchy {
        val responseString = executeViewHierarchyWithRetry(installedApps, excludeKeyboardElements)
        return mapper.readValue(responseString, ViewHierarchy::class.java)
    }

    fun screenshot(compressed: Boolean): ByteArray {
        val url = client.xctestAPIBuilder("screenshot")
            .addQueryParameter("compressed", compressed.toString())
            .build()

        return executeJsonRequest(url)
    }

    fun terminateApp(appId: String) {
        executeJsonRequest("terminateApp", TerminateAppRequest(appId))
    }

    fun launchApp(appId: String) {
        executeJsonRequest("launchApp", LaunchAppRequest(appId))
    }

    fun keyboardInfo(installedApps: Set<String>): KeyboardInfoResponse {
        val response = executeJsonRequest(
            "keyboard",
            KeyboardInfoRequest(installedApps)
        )
        return mapper.readValue(response, KeyboardInfoResponse::class.java)
    }

    fun isScreenStatic(): IsScreenStaticResponse {
        val responseString = executeJsonRequest("isScreenStatic")
        return mapper.readValue(responseString, IsScreenStaticResponse::class.java)
    }

    fun runningAppId(appIds: Set<String>): GetRunningAppIdResponse {
        val response = executeJsonRequest(
            "runningApp",
            GetRunningAppRequest(appIds)
        )
        return mapper.readValue(response, GetRunningAppIdResponse::class.java)
    }

    @Deprecated("swipeV2 is the latest one getting used everywhere because it requires one http call")
    fun swipe(
        appId: String,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        duration: Double,
    ) {
        executeJsonRequest("swipe",
            SwipeRequest(
                appId = appId,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                duration = duration
            )
        )
    }

    fun swipeV2(
        installedApps: Set<String>,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        duration: Double,
    ) {
        executeJsonRequest("swipeV2",
            SwipeRequest(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                duration = duration,
                appIds = installedApps
            )
        )
    }

    fun inputText(
        text: String,
        appIds: Set<String>,
    ) {
        executeJsonRequest("inputText", InputTextRequest(text, appIds))
    }

    fun tap(
        x: Float,
        y: Float,
        duration: Double? = null,
    ) {
        executeJsonRequest("touch", TouchRequest(
            x = x,
            y = y,
            duration = duration
        ))
    }

    fun setOrientation(orientation: String) {
        executeJsonRequest("setOrientation", SetOrientationRequest(orientation))
    }

    fun pressKey(name: String) {
        executeJsonRequest("pressKey", PressKeyRequest(name))
    }

    fun pressButton(name: String) {
        executeJsonRequest("pressButton", PressButtonRequest(name))
    }

    fun eraseText(charactersToErase: Int, appIds: Set<String>) {
        executeJsonRequest("eraseText", EraseTextRequest(charactersToErase, appIds))
    }

    fun deviceInfo(httpUrl: HttpUrl = client.xctestAPIBuilder("deviceInfo").build()): DeviceInfo {
        val response = executeJsonRequest(httpUrl, Unit)
        return mapper.readValue(response, DeviceInfo::class.java)
    }

    fun isChannelAlive(): Boolean {
        return installer.isChannelAlive()
    }

    fun close() {
        installer.close()
    }

    fun setPermissions(permissions: Map<String, String>) {
        executeJsonRequest("setPermissions", SetPermissionsRequest(permissions))
    }

    private fun executeJsonRequest(httpUrl: HttpUrl, body: Any): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyData = mapper.writeValueAsString(body).toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(httpUrl)
            .post(bodyData)

        return okHttpClient
            .newCall(requestBuilder.build())
            .execute().use { processResponse(it, httpUrl.toString()) }
    }

    private fun executeJsonRequest(httpUrl: HttpUrl): ByteArray {
        val request = Request.Builder()
            .get()
            .url(httpUrl)
            .build()

        return okHttpClient
            .newCall(request)
            .execute().use {
                val bytes = it.body?.bytes() ?: ByteArray(0)
                if (!it.isSuccessful) {
                    //handle exception
                    val responseBodyAsString = String(bytes)
                    handleExceptions(it.code, request.url.pathSegments.first(), responseBodyAsString)
                }
                bytes
            }
    }

    private fun executeJsonRequest(pathSegment: String, body: Any): String {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val bodyData = mapper.writeValueAsString(body).toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .url(client.xctestAPIBuilder(pathSegment).build())
            .post(bodyData)

        return okHttpClient
            .newCall(requestBuilder.build())
            .execute().use { processResponse(it, pathSegment) }
    }

    private fun executeJsonRequest(pathSegment: String): String {
        val requestBuilder = Request.Builder()
            .url(client.xctestAPIBuilder(pathSegment).build())
            .get()

        return okHttpClient
            .newCall(requestBuilder.build())
            .execute().use { processResponse(it, pathSegment) }
    }

    private fun processResponse(response: Response, url: String): String {
        val responseBodyAsString = response.body?.bytes()?.let { bytes -> String(bytes) } ?: ""

        return if (!response.isSuccessful) {
            val code = response.code
            handleExceptions(code, url, responseBodyAsString)
        } else {
            responseBodyAsString
        }
    }

    private fun handleExceptions(
        code: Int,
        pathString: String,
        responseBodyAsString: String,
    ): String {
        logger.warn("XCTestDriver request failed. Status code: $code, path: $pathString, body: $responseBodyAsString");
        val error = try {
            mapper.readValue(responseBodyAsString, Error::class.java)
        } catch (_: JsonProcessingException) {
            Error("Unable to parse error", "unknown")
        }
        when {
            code == 408 -> {
                logger.error("Request for $pathString timeout, body: $responseBodyAsString")
                throw XCUITestServerError.OperationTimeout(error.errorMessage, pathString)
            }
            code in 400..499 -> {
                logger.error("Request for $pathString failed with bad request ${code}, body: $responseBodyAsString")
                throw XCUITestServerError.BadRequest(
                    "Request for $pathString failed with bad request ${code}, body: $responseBodyAsString",
                    responseBodyAsString
                )
            }
            error.errorMessage.contains("Lost connection to the application.*".toRegex()) -> {
                logger.error("Request for $pathString failed, because of app crash, body: $responseBodyAsString")
                throw XCUITestServerError.AppCrash(
                    "Request for $pathString failed, due to app crash with message ${error.errorMessage}"
                )
            }
            error.errorMessage.contains("Application [a-zA-Z0-9.]+ is not running".toRegex()) -> {
                logger.error("Request for $pathString failed, because of app crash, body: $responseBodyAsString")
                throw XCUITestServerError.AppCrash(
                    "Request for $pathString failed, due to app crash with message ${error.errorMessage}"
                )
            }
            error.errorMessage.contains("Error getting main window kAXErrorCannotComplete") -> {
                logger.error("Request for $pathString failed, because of app crash, body: $responseBodyAsString")
                throw XCUITestServerError.AppCrash(
                    "Request for $pathString failed, due to app crash with message ${error.errorMessage}"
                )
            }
            error.errorMessage.contains("Error getting main window.*".toRegex()) -> {
                logger.error("Request for $pathString failed, because of app crash, body: $responseBodyAsString")
                throw XCUITestServerError.AppCrash(
                    "Request for $pathString failed, due to app crash with message ${error.errorMessage}"
                )
            }
            else -> {
                logger.error("Request for $pathString failed, because of unknown reason, body: $responseBodyAsString")
                throw XCUITestServerError.UnknownFailure(
                    "Request for $pathString failed, code: ${code}, body: $responseBodyAsString"
                )
            }
        }
    }

    private fun executeViewHierarchyWithRetry(
        installedApps: Set<String>,
        excludeKeyboardElements: Boolean,
    ): String {
        val request = ViewHierarchyRequest(installedApps, excludeKeyboardElements)
        var attempt = 1
        while (true) {
            try {
                return executeJsonRequest("viewHierarchy", request)
            } catch (error: Throwable) {
                val classifierText = retryClassifierText(error)
                val retryToken = retriableAXToken(classifierText)
                if (retryToken == null || attempt >= axRetryCount) {
                    throw error
                }

                val delayMs = axRetryDelayMs(attempt)
                logger.warn(
                    "Transient AX hierarchy failure (attempt {}/{}) host={} port={} delay={}ms token={} summary={}",
                    attempt,
                    axRetryCount,
                    client.host,
                    client.port,
                    delayMs,
                    retryToken,
                    retryClassifierSummary(classifierText)
                )
                try {
                    Thread.sleep(delayMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
                attempt += 1
            }
        }
    }

    private fun retriableAXToken(classifierText: String): String? {
        return RETRIABLE_AX_ERROR_TOKENS.firstOrNull { token ->
            classifierText.contains(token, ignoreCase = true)
        }
    }

    private fun retryClassifierText(error: Throwable): String {
        val details = mutableListOf<String>()
        error.message?.takeIf { it.isNotBlank() }?.let(details::add)

        when (error) {
            is XCUITestServerError.UnknownFailure -> details.add(error.errorResponse)
            is XCUITestServerError.AppCrash -> details.add(error.errorResponse)
            is XCUITestServerError.OperationTimeout -> details.add(error.errorResponse)
            is XCUITestServerError.BadRequest -> details.add(error.errorResponse)
            is XCUITestServerError.NetworkError -> details.add(error.errorResponse)
        }

        val throwableString = error.toString()
        if (throwableString.isNotBlank()) {
            details.add(throwableString)
        }

        return details.joinToString("\n")
    }

    private fun retryClassifierSummary(classifierText: String): String {
        return classifierText.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.take(180)
            ?: "unknown"
    }

    private fun axRetryDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtMost(10)
        val multiplier = 1L shl shift
        return axRetryBaseMs * multiplier
    }

    companion object {
        private val RETRIABLE_AX_ERROR_TOKENS = listOf(
            "kAXErrorInvalidUIElement",
            "kAXErrorCannotComplete",
            "Error getting element frame",
            "Error getting main window"
        )
    }
}
