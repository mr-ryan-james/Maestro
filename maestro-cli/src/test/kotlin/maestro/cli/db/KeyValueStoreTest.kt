package maestro.cli.db

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class KeyValueStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `updates from separate processes preserve both writes`() {
        val databaseFile = tempDir.resolve("cross-process-store").toFile()
        val first = startProcess(databaseFile, "hold", "1000")
        val firstOutput = first.inputStream.bufferedReader()
        var second: Process? = null

        try {
            assertThat(firstOutput.readLine()).isEqualTo("locked")
            second = startProcess(databaseFile, "write")

            assertThat(second.waitFor(5, TimeUnit.SECONDS)).isTrue()
            assertThat(second.exitValue()).isEqualTo(0)
            assertThat(first.waitFor(5, TimeUnit.SECONDS)).isTrue()
            assertThat(first.exitValue()).isEqualTo(0)
            assertThat(KeyValueStore(databaseFile).entries()).containsExactly(
                "first",
                "1",
                "second",
                "2",
            )
        } finally {
            first.destroyForcibly()
            second?.destroyForcibly()
        }
    }

    private fun startProcess(databaseFile: File, mode: String, vararg arguments: String): Process {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = listOf(
            KeyValueStoreProcessFixture::class.java,
            KeyValueStore::class.java,
            Unit::class.java,
        )
            .mapNotNull { it.protectionDomain.codeSource?.location }
            .map { Path.of(it.toURI()).toString() }
            .distinct()
            .joinToString(File.pathSeparator)

        return ProcessBuilder(
            javaExecutable,
            "-cp",
            classpath,
            KeyValueStoreProcessFixture::class.java.name,
            databaseFile.absolutePath,
            mode,
            *arguments,
        ).redirectErrorStream(true).start()
    }
}
