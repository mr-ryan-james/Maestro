package maestro.cli.output

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class OutputBudgetTest {

    @AfterEach
    fun tearDown() {
        OutputBudget.resetForTests()
    }

    @Test
    fun `config falls back to default for invalid or non-positive values`() {
        assertThat(OutputBudget.configFromEnvironment(emptyMap()).maxBytes)
            .isEqualTo(OutputBudget.DEFAULT_MAX_BYTES)
        assertThat(OutputBudget.configFromEnvironment(mapOf(OutputBudget.ENV_VAR to "0")).maxBytes)
            .isEqualTo(OutputBudget.DEFAULT_MAX_BYTES)
        assertThat(OutputBudget.configFromEnvironment(mapOf(OutputBudget.ENV_VAR to "-10")).maxBytes)
            .isEqualTo(OutputBudget.DEFAULT_MAX_BYTES)
        assertThat(OutputBudget.configFromEnvironment(mapOf(OutputBudget.ENV_VAR to "not-a-number")).maxBytes)
            .isEqualTo(OutputBudget.DEFAULT_MAX_BYTES)
    }

    @Test
    fun `config clamps values above hard maximum`() {
        val config = OutputBudget.configFromEnvironment(
            mapOf(OutputBudget.ENV_VAR to (OutputBudget.ABSOLUTE_MAX_BYTES + 1).toString())
        )

        assertThat(config.maxBytes).isEqualTo(OutputBudget.ABSOLUTE_MAX_BYTES)
    }

    @Test
    fun `shared budget truncates combined stdout and stderr writes`() {
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val installed = OutputBudget.installForStreams(
            maxBytes = 7,
            stdout = PrintStream(stdoutBytes, true, StandardCharsets.UTF_8),
            stderr = PrintStream(stderrBytes, true, StandardCharsets.UTF_8),
        )

        installed.stdout.print("abcd")
        installed.stderr.print("efgh")
        installed.stdout.flush()
        installed.stderr.flush()

        assertThat(stdoutBytes.toString(StandardCharsets.UTF_8)).isEqualTo("abcd")
        assertThat(stderrBytes.toString(StandardCharsets.UTF_8)).isEqualTo("efg")
        assertThat(installed.remainingBytes()).isEqualTo(0)
    }

    @Test
    fun `reserved stdout write is all or nothing`() {
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val installed = OutputBudget.installForStreams(
            maxBytes = 4,
            stdout = PrintStream(stdoutBytes, true, StandardCharsets.UTF_8),
            stderr = PrintStream(stderrBytes, true, StandardCharsets.UTF_8),
        )

        assertThat(installed.writeAllOrNothingToOriginalStdout("abc".toByteArray(StandardCharsets.UTF_8))).isTrue()
        assertThat(installed.writeAllOrNothingToOriginalStdout("de".toByteArray(StandardCharsets.UTF_8))).isFalse()

        assertThat(stdoutBytes.toString(StandardCharsets.UTF_8)).isEqualTo("abc")
        assertThat(stderrBytes.toString(StandardCharsets.UTF_8)).isEmpty()
        assertThat(installed.remainingBytes()).isEqualTo(1)
    }

    @Test
    fun `installer skips protocol mode`() {
        val installed = OutputBudget.installIfNeeded(
            protocolMode = true,
            env = mapOf(OutputBudget.ENV_VAR to "128")
        )

        assertThat(installed).isNull()
        assertThat(OutputBudget.currentOrNull()).isNull()
    }
}
