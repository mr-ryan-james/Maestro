package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import maestro.cli.CliError
import maestro.cli.output.OutputBudget
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class PrintHierarchyCommandTest {

    @AfterEach
    fun tearDown() {
        OutputBudget.resetForTests()
    }

    @Test
    fun `writes json to original stdout when payload fits within budget`() {
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        OutputBudget.installForStreams(
            maxBytes = 1024,
            stdout = PrintStream(stdoutBytes, true, StandardCharsets.UTF_8),
            stderr = PrintStream(stderrBytes, true, StandardCharsets.UTF_8),
        )
        val command = PrintHierarchyCommand()

        command.emitHierarchyOutput(
            TreeNode(attributes = mutableMapOf("text" to "hello"))
        )

        val output = stdoutBytes.toString(StandardCharsets.UTF_8)
        assertThat(output).contains("\"text\" : \"hello\"")
        assertThat(output).endsWith("\n")
        assertThat(stderrBytes.toString(StandardCharsets.UTF_8)).isEmpty()
    }

    @Test
    fun `writes compact csv to original stdout when payload fits within budget`() {
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        OutputBudget.installForStreams(
            maxBytes = 1024,
            stdout = PrintStream(stdoutBytes, true, StandardCharsets.UTF_8),
            stderr = PrintStream(stderrBytes, true, StandardCharsets.UTF_8),
        )
        val command = PrintHierarchyCommand()
        CommandLine(command).parseArgs("--compact")

        command.emitHierarchyOutput(
            TreeNode(
                attributes = mutableMapOf("id" to "root"),
                children = listOf(TreeNode(attributes = mutableMapOf("text" to "child"))),
                clickable = true,
            )
        )

        val output = stdoutBytes.toString(StandardCharsets.UTF_8)
        assertThat(output).contains("element_num,depth,attributes,parent_num")
        assertThat(output).contains("id=root; clickable=true")
        assertThat(output).contains("text=child")
        assertThat(stderrBytes.toString(StandardCharsets.UTF_8)).isEmpty()
    }

    @Test
    fun `throws CliError before writing json when payload exceeds remaining budget`() {
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        OutputBudget.installForStreams(
            maxBytes = 32,
            stdout = PrintStream(stdoutBytes, true, StandardCharsets.UTF_8),
            stderr = PrintStream(stderrBytes, true, StandardCharsets.UTF_8),
        )
        val command = PrintHierarchyCommand()

        val error = assertThrows<CliError> {
            command.emitHierarchyOutput(
                TreeNode(attributes = mutableMapOf("text" to "x".repeat(256)))
            )
        }

        assertThat(error).hasMessageThat().contains("MAESTRO_OUTPUT_CAP_BYTES")
        assertThat(stdoutBytes.toString(StandardCharsets.UTF_8)).isEmpty()
        assertThat(stderrBytes.toString(StandardCharsets.UTF_8)).isEmpty()
    }

    @Test
    fun `throws CliError before writing compact csv when payload exceeds remaining budget`() {
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        OutputBudget.installForStreams(
            maxBytes = 32,
            stdout = PrintStream(stdoutBytes, true, StandardCharsets.UTF_8),
            stderr = PrintStream(stderrBytes, true, StandardCharsets.UTF_8),
        )
        val command = PrintHierarchyCommand()
        CommandLine(command).parseArgs("--compact")

        val error = assertThrows<CliError> {
            command.emitHierarchyOutput(
                TreeNode(attributes = mutableMapOf("text" to "x".repeat(256)))
            )
        }

        assertThat(error).hasMessageThat().contains("MAESTRO_OUTPUT_CAP_BYTES")
        assertThat(stdoutBytes.toString(StandardCharsets.UTF_8)).isEmpty()
        assertThat(stderrBytes.toString(StandardCharsets.UTF_8)).isEmpty()
    }
}
