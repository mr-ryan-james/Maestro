package maestro.cli.mcp.tools

import maestro.orchestra.MaestroCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.AssertNoneVisibleNowCommand
import maestro.orchestra.Condition
import maestro.orchestra.DismissKnownOverlaysCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.RetryCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.RunScriptCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.TapFirstVisibleNowCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import maestro.orchestra.yaml.YamlCommandReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal object CompiledFlowCache {

    data class CompiledFlow(
        val commands: List<MaestroCommand>,
        val flowHash: String,
        val envHash: String,
        val projectIndexVersion: String,
        val compiledAtMs: Long,
        val sourceCommandCount: Int,
        val optimizedCommandCount: Int,
        val optimizationSummary: Map<String, Int>,
    )

    private data class InlineCacheKey(
        val basePath: String,
        val digest: String,
    )

    private data class FileCacheKey(
        val absolutePath: String,
        val digest: String,
    )

    private val inlineCache = ConcurrentHashMap<InlineCacheKey, List<MaestroCommand>>()
    private val fileCache = ConcurrentHashMap<FileCacheKey, List<MaestroCommand>>()
    private val compiledFileCache = ConcurrentHashMap<String, CompiledFlow>()
    private val compiledInlineCache = ConcurrentHashMap<String, CompiledFlow>()

    fun readInlineFlow(flowPath: Path, flowYaml: String): List<MaestroCommand> {
        return compileInlineFlow(flowPath, flowYaml).commands
    }

    fun compileInlineFlow(
        flowPath: Path,
        flowYaml: String,
        env: Map<String, String> = emptyMap(),
        projectIndexVersion: String = "static",
    ): CompiledFlow {
        val normalizedPath = flowPath.toAbsolutePath().normalize().toString()
        val cacheKey = InlineCacheKey(
            basePath = normalizedPath,
            digest = digest(flowYaml),
        )

        inlineCache.keys.removeIf { key -> key.basePath == normalizedPath && key != cacheKey }

        val commands = inlineCache.computeIfAbsent(cacheKey) {
            YamlCommandReader.readCommands(flowPath, flowYaml)
        }
        val optimized = optimizeCommands(commands)
        val compiledKey = compiledKey(
            flowHash = cacheKey.digest,
            envHash = digestEnv(env),
            projectIndexVersion = projectIndexVersion,
        )
        return compiledInlineCache.computeIfAbsent(compiledKey) {
            CompiledFlow(
                commands = optimized.commands,
                flowHash = cacheKey.digest,
                envHash = digestEnv(env),
                projectIndexVersion = projectIndexVersion,
                compiledAtMs = System.currentTimeMillis(),
                sourceCommandCount = commands.size,
                optimizedCommandCount = optimized.commands.size,
                optimizationSummary = optimized.summary,
            )
        }
    }

    fun readFlowFile(flowPath: Path): List<MaestroCommand> {
        return compileFlowFile(flowPath).commands
    }

    fun compileFlowFile(
        flowPath: Path,
        env: Map<String, String> = emptyMap(),
        projectIndexVersion: String = "static",
    ): CompiledFlow {
        val normalizedPath = flowPath.toAbsolutePath().normalize()
        val flowText = Files.readString(normalizedPath)
        val cacheKey = FileCacheKey(
            absolutePath = normalizedPath.toString(),
            digest = digest(flowText),
        )

        fileCache.keys.removeIf { key -> key.absolutePath == cacheKey.absolutePath && key != cacheKey }

        val commands = fileCache.computeIfAbsent(cacheKey) {
            YamlCommandReader.readCommands(normalizedPath, flowText)
        }
        val optimized = optimizeCommands(commands)
        val flowDigest = digest("${cacheKey.absolutePath}|${cacheKey.digest}")
        val compiledKey = compiledKey(
            flowHash = flowDigest,
            envHash = digestEnv(env),
            projectIndexVersion = projectIndexVersion,
        )
        return compiledFileCache.computeIfAbsent(compiledKey) {
            CompiledFlow(
                commands = optimized.commands,
                flowHash = flowDigest,
                envHash = digestEnv(env),
                projectIndexVersion = projectIndexVersion,
                compiledAtMs = System.currentTimeMillis(),
                sourceCommandCount = commands.size,
                optimizedCommandCount = optimized.commands.size,
                optimizationSummary = optimized.summary,
            )
        }
    }

    private data class OptimizationResult(
        val commands: List<MaestroCommand>,
        val summary: Map<String, Int>,
    )

    private class OptimizationCounter {
        private val counts = linkedMapOf<String, Int>()

        fun record(name: String, delta: Int = 1) {
            if (delta <= 0) {
                return
            }
            counts[name] = (counts[name] ?: 0) + delta
        }

        fun summary(): Map<String, Int> = counts.toMap()
    }

    private fun optimizeCommands(commands: List<MaestroCommand>): OptimizationResult {
        val counter = OptimizationCounter()
        val optimized = optimizeCommandList(commands, counter)
        return OptimizationResult(
            commands = optimized,
            summary = counter.summary(),
        )
    }

    private fun optimizeCommandList(
        commands: List<MaestroCommand>,
        counter: OptimizationCounter,
    ): List<MaestroCommand> {
        val nestedOptimized = commands.map { optimizeNestedCommands(it, counter) }
        val guardOptimized = nestedOptimized.map { normalizeZeroTimeGuards(it, counter) }
        val waitOptimized = stripRedundantAnimationWaits(guardOptimized, counter)
        return collapseDismissKnownOverlays(waitOptimized, counter)
    }

    private fun optimizeNestedCommands(
        maestroCommand: MaestroCommand,
        counter: OptimizationCounter,
    ): MaestroCommand {
        val command = maestroCommand.asCommand() ?: return maestroCommand
        return when (command) {
            is RunFlowCommand -> MaestroCommand(
                command.copy(
                    commands = optimizeCommandList(command.commands, counter),
                    condition = normalizeCondition(command.condition, counter),
                ),
            )
            is RepeatCommand -> MaestroCommand(
                command.copy(
                    commands = optimizeCommandList(command.commands, counter),
                    condition = normalizeCondition(command.condition, counter),
                ),
            )
            is RetryCommand -> MaestroCommand(
                command.copy(
                    commands = optimizeCommandList(command.commands, counter),
                ),
            )
            is RunScriptCommand -> MaestroCommand(
                command.copy(
                    condition = normalizeCondition(command.condition, counter),
                ),
            )
            else -> maestroCommand
        }
    }

    private fun normalizeZeroTimeGuards(
        maestroCommand: MaestroCommand,
        counter: OptimizationCounter,
    ): MaestroCommand {
        val command = maestroCommand.asCommand() ?: return maestroCommand
        return when (command) {
            is AssertConditionCommand -> {
                val normalizedCondition = normalizeCondition(command.condition, counter) ?: command.condition
                if (normalizedCondition == command.condition) {
                    maestroCommand
                } else {
                    MaestroCommand(command.copy(condition = normalizedCondition))
                }
            }
            else -> maestroCommand
        }
    }

    private fun normalizeCondition(
        condition: Condition?,
        counter: OptimizationCounter,
    ): Condition? {
        if (condition == null || condition.conditionTimeoutMs != 0L) {
            return condition
        }

        var rewrote = false
        val normalized = condition.copy(
            visible = if (condition.visibleNow == null && condition.visible != null) {
                rewrote = true
                null
            } else {
                condition.visible
            },
            visibleNow = condition.visibleNow ?: condition.visible,
            notVisible = if (condition.notVisibleNow == null && condition.notVisible != null) {
                rewrote = true
                null
            } else {
                condition.notVisible
            },
            notVisibleNow = condition.notVisibleNow ?: condition.notVisible,
        )

        if (rewrote) {
            counter.record("zero_time_condition_rewrites")
        }

        return normalized
    }

    private fun stripRedundantAnimationWaits(
        commands: List<MaestroCommand>,
        counter: OptimizationCounter,
    ): List<MaestroCommand> {
        if (commands.isEmpty()) {
            return commands
        }

        val optimized = ArrayList<MaestroCommand>(commands.size)
        for (index in commands.indices) {
            val current = commands[index]
            val currentCommand = current.asCommand()
            val nextCommand = commands.getOrNull(index + 1)?.asCommand()
            if (currentCommand is WaitForAnimationToEndCommand && shouldStripAnimationWaitBefore(nextCommand)) {
                counter.record("redundant_animation_waits_stripped")
                continue
            }
            optimized += current
        }
        return optimized
    }

    private fun shouldStripAnimationWaitBefore(nextCommand: Any?): Boolean {
        return when (nextCommand) {
            is AssertConditionCommand,
            is AssertNoneVisibleNowCommand,
            is DismissKnownOverlaysCommand,
            is ScrollUntilVisibleCommand,
            is TapFirstVisibleNowCommand,
            is WaitForAnimationToEndCommand -> true
            is RunFlowCommand -> nextCommand.condition != null
            is RunScriptCommand -> nextCommand.condition != null
            is RepeatCommand -> nextCommand.condition != null
            else -> false
        }
    }

    private fun collapseDismissKnownOverlays(
        commands: List<MaestroCommand>,
        counter: OptimizationCounter,
    ): List<MaestroCommand> {
        if (commands.isEmpty()) {
            return commands
        }

        val collapsed = ArrayList<MaestroCommand>(commands.size)
        var index = 0
        while (index < commands.size) {
            val currentCommand = commands[index].dismissKnownOverlaysCommand
            if (currentCommand == null) {
                collapsed += commands[index]
                index += 1
                continue
            }

            var merged: DismissKnownOverlaysCommand = currentCommand
            var collapsedCount = 0
            var cursor = index + 1
            while (cursor < commands.size) {
                val nextCommand = commands[cursor].dismissKnownOverlaysCommand
                if (nextCommand == null) {
                    break
                }
                collapsedCount += 1
                merged = merged.copy(
                    maxPasses = maxOf(merged.maxPasses, nextCommand.maxPasses),
                    label = merged.label ?: nextCommand.label,
                    optional = merged.optional && nextCommand.optional,
                )
                cursor += 1
            }

            collapsed += MaestroCommand(merged)
            if (collapsedCount > 0) {
                counter.record("overlay_sweeps_collapsed", collapsedCount)
            }
            index = cursor
        }

        return collapsed
    }

    private fun compiledKey(
        flowHash: String,
        envHash: String,
        projectIndexVersion: String,
    ): String = "$flowHash::$envHash::$projectIndexVersion"

    private fun digestEnv(env: Map<String, String>): String {
        if (env.isEmpty()) {
            return "env:empty"
        }
        val normalized = env.toSortedMap().entries.joinToString(separator = "\n") { (key, value) ->
            "$key=$value"
        }
        return digest(normalized)
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }
}
