package maestro.cli.mcp.tools

import maestro.orchestra.MaestroCommand
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
    )

    private data class InlineCacheKey(
        val basePath: String,
        val digest: String,
    )

    private data class FileCacheKey(
        val absolutePath: String,
        val lastModifiedMs: Long,
        val sizeBytes: Long,
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
        val compiledKey = compiledKey(
            flowHash = cacheKey.digest,
            envHash = digestEnv(env),
            projectIndexVersion = projectIndexVersion,
        )
        return compiledInlineCache.computeIfAbsent(compiledKey) {
            CompiledFlow(
                commands = commands,
                flowHash = cacheKey.digest,
                envHash = digestEnv(env),
                projectIndexVersion = projectIndexVersion,
                compiledAtMs = System.currentTimeMillis(),
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
        val cacheKey = FileCacheKey(
            absolutePath = normalizedPath.toString(),
            lastModifiedMs = Files.getLastModifiedTime(normalizedPath).toMillis(),
            sizeBytes = Files.size(normalizedPath),
        )

        fileCache.keys.removeIf { key -> key.absolutePath == cacheKey.absolutePath && key != cacheKey }

        val commands = fileCache.computeIfAbsent(cacheKey) {
            YamlCommandReader.readCommands(normalizedPath)
        }
        val flowDigest = digest(
            listOf(
                cacheKey.absolutePath,
                cacheKey.lastModifiedMs.toString(),
                cacheKey.sizeBytes.toString(),
            ).joinToString(separator = "|"),
        )
        val compiledKey = compiledKey(
            flowHash = flowDigest,
            envHash = digestEnv(env),
            projectIndexVersion = projectIndexVersion,
        )
        return compiledFileCache.computeIfAbsent(compiledKey) {
            CompiledFlow(
                commands = commands,
                flowHash = flowDigest,
                envHash = digestEnv(env),
                projectIndexVersion = projectIndexVersion,
                compiledAtMs = System.currentTimeMillis(),
            )
        }
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
