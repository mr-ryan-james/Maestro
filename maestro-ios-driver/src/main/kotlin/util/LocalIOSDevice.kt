package util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.io.File

open class DeviceCtlProcess {

    open fun devicectlDevicesOutput(): File {
        val tempOutput = File.createTempFile("devicectl_response", ".json")
        ProcessBuilder(listOf("xcrun", "devicectl", "--json-output", tempOutput.path, "list", "devices"))
            .redirectError(ProcessBuilder.Redirect.PIPE).start().apply {
                waitFor()
            }

        return tempOutput
    }
}

class LocalIOSDevice(private val deviceCtlProcess: DeviceCtlProcess = DeviceCtlProcess()) {
    companion object {
        private val logger = LoggerFactory.getLogger(LocalIOSDevice::class.java)
    }

    private fun mapper() = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, false)
    }

    private fun readDeviceCtlResponse(tempOutput: File): DeviceCtlResponse? {
        val response = runCatching {
            tempOutput.readText()
        }.getOrElse { error ->
            logger.warn("Failed reading devicectl output from {}", tempOutput.absolutePath, error)
            return null
        }

        return runCatching {
            mapper().readValue<DeviceCtlResponse>(response)
        }.onFailure { error ->
            logger.warn(
                "Ignoring malformed devicectl device list output from {}. Falling back to no real iOS devices. Raw size={} bytes",
                tempOutput.absolutePath,
                response.length,
                error
            )
        }.getOrNull()
    }

    fun uninstall(deviceId: String, bundleIdentifier: String) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "devicectl",
                "device",
                "uninstall",
                "app",
                "--device",
                deviceId,
                bundleIdentifier
            )
        )
    }

    fun listDeviceViaDeviceCtl(deviceId: String): DeviceCtlResponse.Device {
        val tempOutput = deviceCtlProcess.devicectlDevicesOutput()
        try {
            return readDeviceCtlResponse(tempOutput)
                ?.result
                ?.devices
                .orEmpty()
                .find {
                it.hardwareProperties?.udid == deviceId
            } ?: throw IllegalArgumentException("iOS device with identifier $deviceId not connected or available")
        } finally {
            tempOutput.delete()
        }
    }

    fun listDeviceViaDeviceCtl(): List<DeviceCtlResponse.Device> {
        val tempOutput = deviceCtlProcess.devicectlDevicesOutput()
        try {
            return readDeviceCtlResponse(tempOutput)
                ?.result
                ?.devices
                .orEmpty()
        } finally {
            tempOutput.delete()
        }
    }
}
