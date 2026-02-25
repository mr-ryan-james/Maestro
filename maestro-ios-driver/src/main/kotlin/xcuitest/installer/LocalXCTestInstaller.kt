package xcuitest.installer

import device.IOSDevice
import maestro.utils.HttpClient
import maestro.utils.MaestroTimer
import maestro.utils.Metrics
import maestro.utils.MetricsProvider
import maestro.utils.MaestroRunMetadata
import maestro.utils.TempFileHandler
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import util.IOSDeviceType
import util.LocalIOSDeviceController
import util.LocalSimulatorUtils
import util.XCRunnerCLIUtils
import xcuitest.XCTestClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.seconds

class LocalXCTestInstaller(
    private val deviceId: String,
    private val host: String = "127.0.0.1",
    private val deviceType: IOSDeviceType,
    private val defaultPort: Int,
    private val metricsProvider: Metrics = MetricsProvider.getInstance(),
    private val httpClient: OkHttpClient = HttpClient.build(
        name = "XCUITestDriverStatusCheck",
        connectTimeout = 1.seconds,
        readTimeout = 100.seconds,
    ),
    val reinstallDriver: Boolean = true,
    private val iOSDriverConfig: IOSDriverConfig,
    private val deviceController: IOSDevice,
    private val tempFileHandler: TempFileHandler = TempFileHandler()
) : XCTestInstaller {

    private val logger = LoggerFactory.getLogger(LocalXCTestInstaller::class.java)
    private val metrics = metricsProvider.withPrefix("xcuitest.installer").withTags(mapOf("kind" to "local", "deviceId" to deviceId, "host" to host))

    /**
     * If true, allow for using a xctest runner started from Xcode.
     *
     * When this flag is set, maestro will not install, run, stop or remove the xctest runner.
     * Make sure to launch the xctest runner from Xcode whenever maestro needs it.
     */
    private val useXcodeTestRunner = !System.getenv("USE_XCODE_TEST_RUNNER").isNullOrEmpty()
    private val tempDir = tempFileHandler.createTempDirectory(deviceId)
    private val localSimulatorUtils = LocalSimulatorUtils(tempFileHandler)
    private val iosBuildProductsExtractor = IOSBuildProductsExtractor(
        target = tempDir.toPath(),
        context = iOSDriverConfig.context,
        deviceType = deviceType,
    )
    private val xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler)

    private var xcTestProcess: Process? = null

    private fun killProcessByPid(pid: Int) {
        runCatching {
            logger.trace("Sending SIGTERM to pid=$pid")
            ProcessBuilder(listOf("kill", pid.toString()))
                .start()
                .waitFor(2, TimeUnit.SECONDS)
        }.onFailure {
            logger.warn("Failed to SIGTERM pid=$pid", it)
        }

        try {
            Thread.sleep(1000)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while waiting for pid=$pid to exit after SIGTERM")
        }

        runCatching {
            val probe = ProcessBuilder(listOf("kill", "-0", pid.toString())).start()
            val completed = probe.waitFor(2, TimeUnit.SECONDS)
            val isAlive = completed && probe.exitValue() == 0
            if (isAlive) {
                logger.trace("pid=$pid still alive after SIGTERM grace period; sending SIGKILL")
                ProcessBuilder(listOf("kill", "-9", pid.toString()))
                    .start()
                    .waitFor(2, TimeUnit.SECONDS)
            }
        }.onFailure {
            logger.warn("Failed to verify/force kill pid=$pid", it)
        }
    }

    private fun hostListenerPidForPort(port: Int): Int? {
        return runCatching {
            val process = ProcessBuilder("bash", "-lc", "lsof -nPiTCP:${port} -sTCP:LISTEN -t | head -n1")
                .start()
            process.waitFor(2, TimeUnit.SECONDS)
            process.inputStream.bufferedReader().readLine()?.trim()?.toIntOrNull()
        }.getOrNull()
    }

    private fun isXCTestProcess(pid: Int): Boolean {
        return runCatching {
            val process = ProcessBuilder("bash", "-lc", "ps -p $pid -o command=")
                .start()
            process.waitFor(2, TimeUnit.SECONDS)
            val cmd = process.inputStream.bufferedReader().readLine()?.trim() ?: ""
            cmd.contains("xctest", ignoreCase = true) ||
                cmd.contains("XCTRunner", ignoreCase = true) ||
                cmd.contains("maestro-driver", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun processRunMetadata(pid: Int): Map<String, String> {
        return runCatching {
            val process = ProcessBuilder("bash", "-lc", "ps eww -p $pid -o command=")
                .start()
            process.waitFor(2, TimeUnit.SECONDS)
            val cmd = process.inputStream.bufferedReader().readLine()?.trim().orEmpty()
            if (cmd.isBlank()) {
                return@runCatching emptyMap()
            }

            Regex("""(MAESTRO_[A-Z_]+)=([^\s]+)""")
                .findAll(cmd)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }.getOrDefault(emptyMap())
    }

    private fun shouldKillProcess(pid: Int, source: String): Boolean {
        val metadata = processRunMetadata(pid)
        val current = MaestroRunMetadata.current()

        val processRunId = metadata[MaestroRunMetadata.ENV_RUN_ID]
        val processOwner = metadata[MaestroRunMetadata.ENV_RUN_OWNER]
        val processLabel = metadata[MaestroRunMetadata.ENV_RUN_LABEL]
        val processRepo = metadata[MaestroRunMetadata.ENV_REPO_NAME]
        val forceStaleKill = MaestroRunMetadata.shouldForceStaleKill()

        if (forceStaleKill) {
            logger.warn(
                "FORCED_STALE_KILL source={} pid={} currentRunId={} processRunId={} processOwner={} processLabel={} processRepo={}",
                source,
                pid,
                current.runId,
                processRunId ?: "unset",
                processOwner ?: "unset",
                processLabel ?: "unset",
                processRepo ?: "unset",
            )
            return true
        }

        if (processRunId != null && processRunId != current.runId) {
            logger.warn(
                "Skipping kill for pid={} source={} due to run ownership mismatch (currentRunId={}, processRunId={}, processOwner={}, processLabel={}, processRepo={})",
                pid,
                source,
                current.runId,
                processRunId,
                processOwner ?: "unset",
                processLabel ?: "unset",
                processRepo ?: "unset",
            )
            return false
        }

        if (processOwner != null && processOwner != current.runOwner) {
            logger.warn(
                "Skipping kill for pid={} source={} due to owner mismatch (currentOwner={}, processOwner={}, processRunId={}, processLabel={}, processRepo={})",
                pid,
                source,
                current.runOwner,
                processOwner,
                processRunId ?: "unset",
                processLabel ?: "unset",
                processRepo ?: "unset",
            )
            return false
        }

        if (processRunId == null && processOwner == null && source == "port") {
            logger.warn(
                "Skipping kill for pid={} source={} because process has no Maestro ownership metadata and source is port-based",
                pid,
                source,
            )
            return false
        }

        return true
    }

    private fun stopRunnerProcessesWithFallback() {
        logger.info("XCTest cleanup started for deviceId={} hostPort={}", deviceId, defaultPort)
        logger.trace("Will attempt to stop all alive XCTest Runner processes before uninstalling")

        if (xcTestProcess?.isAlive == true) {
            logger.trace("XCTest Runner process started by us is alive, killing it")
            xcTestProcess?.destroy()
            runCatching { xcTestProcess?.waitFor(2, TimeUnit.SECONDS) }
            if (xcTestProcess?.isAlive == true) {
                xcTestProcess?.destroyForcibly()
            }
        }
        xcTestProcess = null

        val pidFromBundle = xcRunnerCLIUtils.pidForApp(UI_TEST_RUNNER_APP_BUNDLE_ID, deviceId)
        if (pidFromBundle != null) {
            if (shouldKillProcess(pidFromBundle, source = "bundle")) {
                logger.trace("Killing XCTest Runner process by bundle pid=$pidFromBundle")
                killProcessByPid(pidFromBundle)
            } else {
                logger.warn("Skipped killing XCTest Runner process by bundle pid={} due to ownership safeguards", pidFromBundle)
            }
        }

        val pidFromPort = hostListenerPidForPort(defaultPort)
        if (pidFromPort != null && isXCTestProcess(pidFromPort)) {
            if (shouldKillProcess(pidFromPort, source = "port")) {
                logger.trace("Killing lingering listener for XCTest driver host port $defaultPort (pid=$pidFromPort)")
                killProcessByPid(pidFromPort)
            } else {
                logger.warn(
                    "Skipped killing lingering listener for XCTest driver host port {} (pid={}) due to ownership safeguards",
                    defaultPort,
                    pidFromPort
                )
            }
        } else if (pidFromPort != null) {
            logger.warn(
                "XCTest driver host port {} is occupied by non-XCTest process (pid={}); skipping kill",
                defaultPort,
                pidFromPort
            )
        }

        logger.trace("Finished stopping XCTest Runner processes")
        logger.info("XCTest cleanup finished for deviceId={} hostPort={}", deviceId, defaultPort)
    }

    override fun uninstall(): Boolean {
        return metrics.measured("operation", mapOf("command" to "uninstall")) {
            // FIXME(bartekpacia): This method probably doesn't have to care about killing the XCTest Runner process.
            //  Just uninstalling should suffice. It automatically kills the process.

            if (useXcodeTestRunner || !reinstallDriver) {
                logger.trace("Skipping uninstalling XCTest Runner as USE_XCODE_TEST_RUNNER is set")
                return@measured false
            }

            stopRunnerProcessesWithFallback()

            logger.trace("Stopping and uninstalling XCTest Runner from device $deviceId")
            runCatching {
                deviceController.stop(id = UI_TEST_RUNNER_APP_BUNDLE_ID)
            }.onFailure {
                logger.trace("Skipping explicit stop for XCTest Runner", it)
            }
            runCatching {
                deviceController.uninstall(id = UI_TEST_RUNNER_APP_BUNDLE_ID)
            }.onFailure {
                logger.trace("Explicit uninstall for XCTest Runner failed", it)
            }
            logger.info("XCTest uninstall completed for deviceId={} hostPort={}", deviceId, defaultPort)
            true
        }
    }

    override fun start(): XCTestClient {
        return metrics.measured("operation", mapOf("command" to "start")) {
            logger.info("start()")

            if (useXcodeTestRunner) {
                logger.info("USE_XCODE_TEST_RUNNER is set. Will wait for XCTest runner to be started manually")

                repeat(20) {
                    if (ensureOpen()) {
                        return@measured XCTestClient(host, defaultPort)
                    }
                    logger.info("==> Start XCTest runner to continue flow")
                    Thread.sleep(500)
                }
                throw IllegalStateException("XCTest was not started manually")
            }


            logger.info("[Start] Install XCUITest runner on $deviceId")
            startXCTestRunner(deviceId, iOSDriverConfig.prebuiltRunner)
            logger.info("[Done] Install XCUITest runner on $deviceId")

            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
                runCatching {
                    if (isChannelAlive()) return@measured XCTestClient(host, defaultPort)
                }
                Thread.sleep(500)
            }

            throw IOSDriverTimeoutException("iOS driver not ready in time, consider increasing timeout by configuring MAESTRO_DRIVER_STARTUP_TIMEOUT env variable")
        }
    }

    class IOSDriverTimeoutException(message: String): RuntimeException(message)

    private fun getStartupTimeout(): Long = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_TIMEOUT).toLong()
    }.getOrDefault(SERVER_LAUNCH_TIMEOUT_MS)

    override fun isChannelAlive(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isChannelAlive")) {
        return@measured xcTestDriverStatusCheck()
        }
    }

    private fun ensureOpen(): Boolean {
        val timeout = 120_000L
        logger.info("ensureOpen(): Will spend $timeout ms waiting for the channel to become alive")
        val result = MaestroTimer.retryUntilTrue(timeout, 200, onException = {
            logger.error("ensureOpen() failed with exception: $it")
        }) { isChannelAlive() }
        logger.info("ensureOpen() finished, is channel alive?: $result")
        return result
    }

    private fun xcTestDriverStatusCheck(): Boolean {
        logger.info("[Start] Perform XCUITest driver status check on $deviceId")
        fun xctestAPIBuilder(pathSegment: String): HttpUrl.Builder {
            return HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
                .addPathSegment(pathSegment)
                .port(defaultPort)
        }

        val url by lazy {
            xctestAPIBuilder("status")
                .build()
        }

        val request by lazy {  Request.Builder()
            .get()
            .url(url)
            .build()
        }

        val checkSuccessful = try {
            httpClient.newCall(request).execute().use {
                logger.info("[Done] Perform XCUITest driver status check on $deviceId")
                it.isSuccessful
            }
        } catch (ignore: IOException) {
            logger.info("[Failed] Perform XCUITest driver status check on $deviceId, exception: $ignore")
            false
        }

        return checkSuccessful
    }

    private fun startXCTestRunner(deviceId: String, preBuiltRunner: Boolean) {
        if (isChannelAlive()) {
            logger.info("UI Test runner already running, returning")
            return
        }

        val buildProducts = iosBuildProductsExtractor.extract(iOSDriverConfig.sourceDirectory)

        if (preBuiltRunner) {
            logger.info("Installing pre built driver without xcodebuild")
            installPrebuiltRunner(deviceId, buildProducts.uiRunnerPath)
        } else {
            logger.info("Installing driver with xcodebuild")
            logger.info("[Start] Running XcUITest with `xcodebuild test-without-building` with $defaultPort and config: $iOSDriverConfig")
            xcTestProcess = xcRunnerCLIUtils.runXcTestWithoutBuild(
                deviceId = this.deviceId,
                xcTestRunFilePath = buildProducts.xctestRunPath.absolutePath,
                port = defaultPort,
                snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews
            )
            logger.info("[Done] Running XcUITest with `xcodebuild test-without-building`")
        }
    }

    private fun installPrebuiltRunner(deviceId: String, bundlePath: File) {
        logger.info("Installing prebuilt driver for $deviceId and type $deviceType")
        when (deviceType) {
            IOSDeviceType.REAL -> {
                LocalIOSDeviceController.install(deviceId, bundlePath.toPath())
                LocalIOSDeviceController.launchRunner(
                    deviceId = deviceId,
                    port = defaultPort,
                    snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews
                )
            }
            IOSDeviceType.SIMULATOR -> {
                localSimulatorUtils.install(deviceId, bundlePath.toPath())
                localSimulatorUtils.launchUITestRunner(
                    deviceId = deviceId,
                    port = defaultPort,
                    snapshotKeyHonorModalViews = iOSDriverConfig.snapshotKeyHonorModalViews
                )
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        if (useXcodeTestRunner) {
            return
        }

        logger.info("[Start] Cleaning up the ui test runner files")
        tempFileHandler.close()
        stopRunnerProcessesWithFallback()
        if(reinstallDriver) {
            uninstall()
            deviceController.close()
            logger.info("[Done] Cleaning up the ui test runner files")
        } else {
            logger.info("[Done] Cleaning up the ui test runner files (reinstall disabled)")
        }
    }

    data class IOSDriverConfig(
        val prebuiltRunner: Boolean,
        val sourceDirectory: String,
        val context: Context,
        val snapshotKeyHonorModalViews: Boolean?
    )

    companion object {
        const val UI_TEST_RUNNER_APP_BUNDLE_ID = "dev.mobile.maestro-driver-iosUITests.xctrunner"

        private const val SERVER_LAUNCH_TIMEOUT_MS = 120000L
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"
    }

}
