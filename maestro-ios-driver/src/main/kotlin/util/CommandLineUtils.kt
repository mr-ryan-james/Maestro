package util

import maestro.utils.MaestroRunMetadata
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory

object CommandLineUtils {

    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val nullFile = File(if (isWindows) "NUL" else "/dev/null")
    private val logger = LoggerFactory.getLogger(CommandLineUtils::class.java)

    @Suppress("SpreadOperator")
    fun runCommand(
            parts: List<String>,
            waitForCompletion: Boolean = true,
            outputFile: File? = null,
            params: Map<String, String> = emptyMap()
    ): Process {
        logger.info("Running command line operation: $parts with $params")

        val processBuilder =
                if (outputFile != null) {
                    ProcessBuilder(*parts.toTypedArray())
                            .redirectOutput(outputFile)
                            .redirectError(outputFile)
                } else {
                    ProcessBuilder(*parts.toTypedArray())
                            .redirectOutput(nullFile)
                            .redirectError(ProcessBuilder.Redirect.PIPE)
                }

        processBuilder.environment().putAll(MaestroRunMetadata.environmentVariables())
        processBuilder.environment().putAll(params)
        val process = processBuilder.start()

        if (waitForCompletion) {
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                logger.error("Process timed out after 5 minutes: $parts")
                process.destroy()
                val terminatedAfterTerm = runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                    .onFailure {
                        if (it is InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    .getOrDefault(false)

                if (!terminatedAfterTerm) {
                    logger.warn("Process still alive after SIGTERM, forcing kill: $parts")
                    process.destroyForcibly()
                    runCatching { process.waitFor(2, TimeUnit.SECONDS) }
                        .onFailure {
                            if (it is InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        }
                }

                throw TimeoutException("Command timed out after 5 minutes: ${parts.joinToString(" ")}")
            }

            if (process.exitValue() != 0) {
                val processOutput = process.errorStream.source().buffer().readUtf8()

                logger.error("Process failed with exit code ${process.exitValue()}")
                logger.error("Error output $processOutput")

                throw IllegalStateException(processOutput)
            }
        }

        return process
    }
}
