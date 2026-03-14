package maestro.cli.daemon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString

private val daemonYamlMapper = ObjectMapper(YAMLFactory())
    .registerModule(KotlinModule.Builder().build())

private val FLOW_EXTENSIONS = setOf("yaml", "yml")
private val CODE_EXTENSIONS = setOf(
    "js",
    "jsx",
    "ts",
    "tsx",
    "mjs",
    "cjs",
    "swift",
    "kt",
    "kts",
    "java",
    "xml",
)
private val TEST_ID_PATTERNS = listOf(
    Regex("""\btestID\s*[:=]\s*\{\s*`([^`]+)`\s*\}"""),
    Regex("""\btestID\s*[:=]\s*["'`]([^"'`]+)["'`]"""),
    Regex("""\bdata-testid\s*[:=]\s*\{\s*`([^`]+)`\s*\}"""),
    Regex("""\bdata-testid\s*[:=]\s*["'`]([^"'`]+)["'`]"""),
    Regex("""\baccessibilityIdentifier\s*[:=]\s*\{\s*`([^`]+)`\s*\}"""),
    Regex("""\baccessibilityIdentifier\s*[:=]\s*["'`]([^"'`]+)["'`]"""),
    Regex("""\bandroid:id\s*=\s*["']@\+id/([^"']+)["']"""),
)
private val MAESTRO_HEADER_KEYS = setOf(
    "appId",
    "name",
    "tags",
    "env",
    "onFlowStart",
    "onFlowComplete",
    "jsEngine",
    "testOutputDir",
    "clearState",
    "launchApp",
    "includeTags",
    "excludeTags",
)
private val SELECTOR_KEYS = setOf(
    "id",
    "text",
    "containsText",
    "label",
    "accessibilityText",
    "contentDescription",
    "resourceId",
    "resource-id",
    "testID",
    "below",
    "above",
    "leftOf",
    "rightOf",
)
private val FILE_REFERENCE_KEYS = setOf(
    "runFlow",
    "file",
    "path",
    "flow",
    "source",
)
private val NESTED_COMMAND_KEYS = setOf("commands", "when", "runFlow", "retry", "repeat")
private val SCALAR_SELECTOR_COMMANDS = setOf(
    "tapOn",
    "doubleTapOn",
    "longPressOn",
    "assertVisible",
    "assertNotVisible",
    "scrollUntilVisible",
    "copyTextFrom",
)
private val IGNORE_DIRS = setOf(
    ".git",
    ".idea",
    ".next",
    ".expo",
    ".turbo",
    ".gradle",
    ".yarn",
    "node_modules",
    "Pods",
    "build",
    "dist",
    "coverage",
    "artifacts",
    "tmp",
    "temp",
    "vendor",
)
private const val CACHE_TTL_MS = 5_000L

private data class CacheEntry<T>(
    val value: T,
    val expiresAtMs: Long,
)

data class FlowSelectorInfo(
    val commandIndex: Int,
    val commandName: String,
    val strategy: String,
    val value: String,
    val location: String,
)

data class FlowReferenceInfo(
    val commandIndex: Int,
    val commandName: String,
    val key: String,
    val value: String,
    val location: String,
    val absolutePath: Path,
    val existsOnDisk: Boolean,
)

data class ParsedDaemonFlow(
    val header: Map<String, Any?>,
    val commands: List<Any?>,
    val commandCount: Int,
    val selectors: List<FlowSelectorInfo>,
    val selectorCounts: Map<String, Int>,
    val commandSummaries: List<String>,
    val warnings: List<String>,
    val referencedFiles: List<FlowReferenceInfo>,
)

data class FlowSummary(
    val path: Path,
    val relativePath: String,
    val appId: String?,
    val name: String,
    val tags: List<String>,
    val commandCount: Int,
    val selectorCount: Int,
    val selectorCounts: Map<String, Int>,
    val warnings: List<String>,
    val firstCommands: List<String>,
)

data class FlowAnalysis(
    val path: Path,
    val relativePath: String,
    val appId: String?,
    val name: String,
    val commandCount: Int,
    val selectors: List<FlowSelectorInfo>,
    val warnings: List<String>,
    val referencedFiles: List<FlowReferenceInfo>,
)

data class TestIdSummary(
    val value: String,
    val count: Int,
    val files: List<String>,
)

data class TestIdMatch(
    val value: String,
    val files: List<String>,
)

data class TestIdValidationResult(
    val value: String,
    val status: String,
    val matches: List<TestIdMatch>,
)

object DaemonProjectCatalog {

    private val flowDiscoveryCache = ConcurrentHashMap<String, CacheEntry<List<FlowSummary>>>()
    private val flowAnalysisCache = ConcurrentHashMap<String, CacheEntry<FlowAnalysis>>()
    private val testIdCatalogCache = ConcurrentHashMap<String, CacheEntry<List<TestIdSummary>>>()
    private val projectIndexVersionCache = ConcurrentHashMap<String, CacheEntry<String>>()

    fun discoverFlows(projectRoot: Path): List<FlowSummary> {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val cacheKey = normalizedRoot.pathString
        getCacheEntry(flowDiscoveryCache, cacheKey)?.let { return it }

        val summaries = Files.walk(normalizedRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> Files.isRegularFile(path) }
                .filter { path -> path.extension.lowercase() in FLOW_EXTENSIONS }
                .filter { path -> isMaestroOwnedPath(normalizedRoot, path) }
                .filterNot { path -> path.anyAncestorIgnored(normalizedRoot) }
                .sortedWith(compareBy<Path> { preferredDirectoryPriority(it) }.thenBy { it.pathString })
                .mapNotNull { flowPath ->
                    runCatching {
                        val parsed = parseFlowText(normalizedRoot, flowPath)
                        if (parsed.commandCount == 0) {
                            null
                        } else {
                            val warnings = parsed.warnings.toMutableList()
                            val missingReferences = parsed.referencedFiles.filterNot { it.existsOnDisk }.map { it.value }
                            if (missingReferences.isNotEmpty()) {
                                warnings += "Missing referenced flows/files: ${missingReferences.joinToString(", ")}"
                            }
                            FlowSummary(
                                path = flowPath,
                                relativePath = normalizedRoot.relativize(flowPath).pathString.ifBlank { "." },
                                appId = normalizeHeaderScalar(parsed.header["appId"]),
                                name = normalizeHeaderScalar(parsed.header["name"]) ?: flowPath.name,
                                tags = (parsed.header["tags"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty(),
                                commandCount = parsed.commandCount,
                                selectorCount = parsed.selectors.size,
                                selectorCounts = parsed.selectorCounts,
                                warnings = warnings,
                                firstCommands = parsed.commandSummaries.take(5),
                            )
                        }
                    }.getOrNull()
                }
                .toList()
        }

        return setCacheEntry(flowDiscoveryCache, cacheKey, summaries)
    }

    fun analyzeFlow(projectRoot: Path, flowPath: String): FlowAnalysis {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val absolutePath = normalizedRoot.resolve(flowPath).normalize().takeIf { !Paths.get(flowPath).isAbsolute }
            ?: Paths.get(flowPath).toAbsolutePath().normalize()
        val stats = Files.readAttributes(absolutePath, "basic:size,lastModifiedTime")
        val cacheKey = "${absolutePath.pathString}:${stats["size"]}:${stats["lastModifiedTime"]}"
        getCacheEntry(flowAnalysisCache, cacheKey)?.let { return it }

        val parsed = parseFlowText(normalizedRoot, absolutePath)
        val analysis = FlowAnalysis(
            path = absolutePath,
            relativePath = normalizedRoot.relativize(absolutePath).pathString.ifBlank { "." },
            appId = normalizeHeaderScalar(parsed.header["appId"]),
            name = normalizeHeaderScalar(parsed.header["name"]) ?: absolutePath.fileName.toString(),
            commandCount = parsed.commandCount,
            selectors = parsed.selectors,
            warnings = parsed.warnings,
            referencedFiles = parsed.referencedFiles,
        )
        return setCacheEntry(flowAnalysisCache, cacheKey, analysis)
    }

    fun scanTestIds(projectRoot: Path): List<TestIdSummary> {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val cacheKey = normalizedRoot.pathString
        getCacheEntry(testIdCatalogCache, cacheKey)?.let { return it }

        val matches = linkedMapOf<String, MutableSet<String>>()
        Files.walk(normalizedRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> Files.isRegularFile(path) }
                .filter { path -> path.extension.lowercase() in CODE_EXTENSIONS }
                .filterNot { path -> path.anyAncestorIgnored(normalizedRoot) }
                .forEach { filePath ->
                    val text = runCatching { Files.readString(filePath) }.getOrNull() ?: return@forEach
                    TEST_ID_PATTERNS.forEach { pattern ->
                        pattern.findAll(text).forEach { match ->
                            val value = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@forEach
                            matches.getOrPut(value) { linkedSetOf() }.add(normalizedRoot.relativize(filePath).pathString.ifBlank { "." })
                        }
                    }
                }
        }

        val catalog = matches.entries
            .map { (value, files) ->
                TestIdSummary(
                    value = value,
                    count = files.size,
                    files = files.toList().sorted(),
                )
            }
            .sortedBy { it.value }

        return setCacheEntry(testIdCatalogCache, cacheKey, catalog)
    }

    fun projectIndexVersion(
        projectRoot: Path,
        forceRefresh: Boolean = false,
    ): String {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val cacheKey = normalizedRoot.pathString
        if (!forceRefresh) {
            getCacheEntry(projectIndexVersionCache, cacheKey)?.let { return it }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var fileCount = 0

        Files.walk(normalizedRoot).use { paths ->
            paths.iterator().asSequence()
                .filter { path -> Files.isRegularFile(path) }
                .filterNot { path -> path.anyAncestorIgnored(normalizedRoot) }
                .filter { path ->
                    isMaestroOwnedPath(normalizedRoot, path) || path.extension.lowercase() in CODE_EXTENSIONS
                }
                .sortedBy { path ->
                    runCatching { normalizedRoot.relativize(path).pathString }.getOrElse { path.pathString }
                }
                .forEach { filePath ->
                    val relativePath = runCatching { normalizedRoot.relativize(filePath).pathString }.getOrElse { filePath.pathString }
                    val attributes = runCatching {
                        Files.readAttributes(filePath, "basic:size,lastModifiedTime")
                    }.getOrNull() ?: return@forEach
                    fileCount += 1
                    digest.update(relativePath.toByteArray())
                    digest.update(0)
                    digest.update(attributes["size"].toString().toByteArray())
                    digest.update(0)
                    digest.update(attributes["lastModifiedTime"].toString().toByteArray())
                    digest.update(0)
                }
        }

        val digestHex = digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        val version = "project:${normalizedRoot}:$fileCount:$digestHex"
        return if (forceRefresh) {
            projectIndexVersionCache[cacheKey] = CacheEntry(
                value = version,
                expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS,
            )
            version
        } else {
            setCacheEntry(
                projectIndexVersionCache,
                cacheKey,
                version,
            )
        }
    }

    fun validateTestIds(
        projectRoot: Path,
        requestedIds: List<String>,
        flowPath: String? = null,
    ): Pair<List<String>, List<TestIdValidationResult>> {
        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val requested = linkedSetOf<String>()
        requestedIds.filter { it.isNotBlank() }.forEach { requested += it }
        if (!flowPath.isNullOrBlank()) {
            analyzeFlow(normalizedRoot, flowPath).selectors
                .filter { selector ->
                    selector.strategy.equals("id", ignoreCase = true) ||
                        selector.strategy.equals("resourceId", ignoreCase = true) ||
                        selector.strategy.equals("resource-id", ignoreCase = true) ||
                        selector.strategy.equals("testID", ignoreCase = true)
                }
                .mapTo(requested) { it.value }
        }

        val catalog = scanTestIds(normalizedRoot)
        val catalogMap = catalog.associateBy { it.value }
        val results = requested.toList().sorted().map { candidate ->
            val exact = catalogMap[candidate]
            if (exact != null) {
                return@map TestIdValidationResult(
                    value = candidate,
                    status = "found",
                    matches = listOf(TestIdMatch(exact.value, exact.files)),
                )
            }

            val candidatePattern = compileDynamicSelectorPattern(candidate)
            val patternMatches = catalog
                .filter { entry ->
                    candidatePattern?.matches(entry.value) == true ||
                        compileDynamicSelectorPattern(entry.value)?.matches(candidate) == true
                }
                .map { entry -> TestIdMatch(entry.value, entry.files) }
                .sortedBy { it.value }

            if (patternMatches.isNotEmpty()) {
                return@map TestIdValidationResult(
                    value = candidate,
                    status = "pattern_match",
                    matches = patternMatches,
                )
            }

            val suggestions = catalog
                .map { entry ->
                    entry to computeSimilarity(candidate, entry.value)
                }
                .filter { (_, similarity) -> similarity >= 0.35 }
                .sortedWith(compareByDescending<Pair<TestIdSummary, Double>> { it.second }.thenBy { it.first.value })
                .take(5)
                .map { (entry, _) -> TestIdMatch(entry.value, entry.files) }

            TestIdValidationResult(
                value = candidate,
                status = "missing",
                matches = suggestions,
            )
        }

        return requested.toList().sorted() to results
    }

    private fun parseFlowText(projectRoot: Path, flowPath: Path): ParsedDaemonFlow {
        val text = Files.readString(flowPath)
        val documents = readYamlDocuments(text)
        val warnings = mutableListOf<String>()
        if (documents.isEmpty()) {
            return ParsedDaemonFlow(
                header = emptyMap(),
                commands = emptyList(),
                commandCount = 0,
                selectors = emptyList(),
                selectorCounts = emptyMap(),
                commandSummaries = emptyList(),
                warnings = warnings,
                referencedFiles = emptyList(),
            )
        }

        val (header, commands) = extractHeaderAndCommands(documents)
        val selectors = mutableListOf<FlowSelectorInfo>()
        val references = mutableListOf<FlowReferenceInfo>()
        val commandSummaries = mutableListOf<String>()
        val flowDir = flowPath.parent ?: projectRoot

        commands.forEachIndexed { index, command ->
            val descriptor = walkCommandNode(
                command = command,
                commandIndex = index,
                location = "commands[$index]",
                selectors = selectors,
                references = references,
                flowDir = flowDir,
            )
            descriptor?.let(commandSummaries::add)
        }

        val selectorCounts = selectors.groupingBy { it.strategy }.eachCount()

        return ParsedDaemonFlow(
            header = header,
            commands = commands,
            commandCount = commands.size,
            selectors = selectors,
            selectorCounts = selectorCounts,
            commandSummaries = commandSummaries,
            warnings = warnings,
            referencedFiles = references,
        )
    }

    private fun normalizeText(value: String): String {
        return value
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun computeSimilarity(query: String, candidate: String): Double {
        val normalizedQuery = normalizeText(query)
        val normalizedCandidate = normalizeText(candidate)
        if (normalizedQuery.isBlank() || normalizedCandidate.isBlank()) {
            return 0.0
        }
        if (normalizedQuery == normalizedCandidate) {
            return 1.0
        }
        if (normalizedCandidate.contains(normalizedQuery) || normalizedQuery.contains(normalizedCandidate)) {
            return 0.85
        }
        val queryTerms = normalizedQuery.split(' ').filter { it.isNotBlank() }.toSet()
        val candidateTerms = normalizedCandidate.split(' ').filter { it.isNotBlank() }.toSet()
        if (queryTerms.isEmpty() || candidateTerms.isEmpty()) {
            return 0.0
        }
        val overlap = queryTerms.count { candidateTerms.contains(it) }
        return overlap.toDouble() / maxOf(queryTerms.size, candidateTerms.size, 1)
    }

    private fun compileDynamicSelectorPattern(candidate: String): Regex? {
        val hasTemplate = candidate.contains("\${")
        val hasWildcard = candidate.contains(".*")
        if (!hasTemplate && !hasWildcard) {
            return null
        }

        val source = buildString {
            append("^")
            var index = 0
            while (index < candidate.length) {
                when {
                    candidate.startsWith("\${", index) -> {
                        val closingIndex = candidate.indexOf('}', index + 2)
                        if (closingIndex == -1) {
                            append(Regex.escape(candidate.substring(index)))
                            index = candidate.length
                        } else {
                            append("(.+?)")
                            index = closingIndex + 1
                        }
                    }
                    candidate.startsWith(".*", index) -> {
                        append("(.+?)")
                        index += 2
                    }
                    else -> {
                        append(Regex.escape(candidate[index].toString()))
                        index += 1
                    }
                }
            }
            append("$")
        }
        return Regex(source)
    }

    private fun readYamlDocuments(text: String): List<Any?> {
        val reader = daemonYamlMapper.readerFor(Any::class.java)
        val iterator = reader.readValues<Any?>(text)
        val documents = mutableListOf<Any?>()
        while (iterator.hasNext()) {
            documents += iterator.next()
        }
        return documents
    }

    private fun extractHeaderAndCommands(documents: List<Any?>): Pair<Map<String, Any?>, List<Any?>> {
        val first = documents.getOrNull(0)
        val second = documents.getOrNull(1)

        if (first is List<*>) {
            return emptyMap<String, Any?>() to first
        }
        if (looksLikeHeader(first) && second is List<*>) {
            return (first as? Map<*, *>)?.toStringKeyMap().orEmpty() to second
        }
        if (looksLikeHeader(first) && first is Map<*, *>) {
            val commands = (first["commands"] as? List<*>) ?: emptyList<Any?>()
            return first.toStringKeyMap() to commands
        }
        if (second is List<*>) {
            return (first as? Map<*, *>)?.toStringKeyMap().orEmpty() to second
        }
        return (first as? Map<*, *>)?.toStringKeyMap().orEmpty() to emptyList()
    }

    private fun walkCommandNode(
        command: Any?,
        commandIndex: Int,
        location: String,
        selectors: MutableList<FlowSelectorInfo>,
        references: MutableList<FlowReferenceInfo>,
        flowDir: Path,
    ): String? {
        when (command) {
            is Map<*, *> -> {
                val commandMap = command.toStringKeyMap()
                if (commandMap.size == 1) {
                    val (commandName, value) = commandMap.entries.first()
                    walkCommandValue(
                        value = value,
                        commandIndex = commandIndex,
                        commandName = commandName,
                        location = "$location.$commandName",
                        selectors = selectors,
                        references = references,
                        flowDir = flowDir,
                    )
                    return commandName
                }

                commandMap.forEach { (key, value) ->
                    walkCommandValue(
                        value = value,
                        commandIndex = commandIndex,
                        commandName = key,
                        location = "$location.$key",
                        selectors = selectors,
                        references = references,
                        flowDir = flowDir,
                    )
                }
                return commandMap.keys.firstOrNull()
            }

            is List<*> -> {
                command.forEachIndexed { nestedIndex, nested ->
                    walkCommandNode(
                        command = nested,
                        commandIndex = commandIndex,
                        location = "$location[$nestedIndex]",
                        selectors = selectors,
                        references = references,
                        flowDir = flowDir,
                    )
                }
                return null
            }

            else -> return null
        }
    }

    private fun walkCommandValue(
        value: Any?,
        commandIndex: Int,
        commandName: String,
        location: String,
        selectors: MutableList<FlowSelectorInfo>,
        references: MutableList<FlowReferenceInfo>,
        flowDir: Path,
    ) {
        collectSelectorEntries(commandName, value, selectors, commandIndex, location)
        collectReferencedFiles(commandName, value, references, flowDir, commandIndex, location)

        when (value) {
            is List<*> -> value.forEachIndexed { index, nested ->
                walkCommandNode(
                    command = nested,
                    commandIndex = commandIndex,
                    location = "$location[$index]",
                    selectors = selectors,
                    references = references,
                    flowDir = flowDir,
                )
            }

            is Map<*, *> -> value.toStringKeyMap().forEach { (key, nestedValue) ->
                if (key in NESTED_COMMAND_KEYS) {
                    walkCommandNode(
                        command = nestedValue,
                        commandIndex = commandIndex,
                        location = "$location.$key",
                        selectors = selectors,
                        references = references,
                        flowDir = flowDir,
                    )
                }
            }
        }
    }

    private fun collectSelectorEntries(
        commandName: String,
        value: Any?,
        selectors: MutableList<FlowSelectorInfo>,
        commandIndex: Int,
        location: String,
    ) {
        when (value) {
            is String -> {
                if (commandName in SCALAR_SELECTOR_COMMANDS) {
                    selectors += FlowSelectorInfo(
                        commandIndex = commandIndex,
                        commandName = commandName,
                        strategy = "text",
                        value = value,
                        location = location,
                    )
                }
            }

            is List<*> -> value.forEachIndexed { index, nested ->
                collectSelectorEntries(
                    commandName = commandName,
                    value = nested,
                    selectors = selectors,
                    commandIndex = commandIndex,
                    location = "$location[$index]",
                )
            }

            is Map<*, *> -> value.toStringKeyMap().forEach { (key, nestedValue) ->
                if (key in SELECTOR_KEYS) {
                    normalizeHeaderScalar(nestedValue)?.let { normalized ->
                        selectors += FlowSelectorInfo(
                            commandIndex = commandIndex,
                            commandName = commandName,
                            strategy = key,
                            value = normalized,
                            location = "$location.$key",
                        )
                    }
                } else {
                    collectSelectorEntries(
                        commandName = commandName,
                        value = nestedValue,
                        selectors = selectors,
                        commandIndex = commandIndex,
                        location = "$location.$key",
                    )
                }
            }
        }
    }

    private fun collectReferencedFiles(
        commandName: String,
        value: Any?,
        references: MutableList<FlowReferenceInfo>,
        flowDir: Path,
        commandIndex: Int,
        location: String,
    ) {
        when (value) {
            is String -> {
                if (commandName == "runFlow" && location.endsWith(".runFlow")) {
                    val resolved = flowDir.resolve(value).normalize()
                    references += FlowReferenceInfo(
                        commandIndex = commandIndex,
                        commandName = commandName,
                        key = "runFlow",
                        value = value,
                        location = location,
                        absolutePath = resolved,
                        existsOnDisk = Files.exists(resolved),
                    )
                }
            }

            is List<*> -> value.forEachIndexed { index, nested ->
                collectReferencedFiles(
                    commandName = commandName,
                    value = nested,
                    references = references,
                    flowDir = flowDir,
                    commandIndex = commandIndex,
                    location = "$location[$index]",
                )
            }

            is Map<*, *> -> value.toStringKeyMap().forEach { (key, nestedValue) ->
                if (key in FILE_REFERENCE_KEYS && nestedValue is String) {
                    val resolved = flowDir.resolve(nestedValue).normalize()
                    references += FlowReferenceInfo(
                        commandIndex = commandIndex,
                        commandName = commandName,
                        key = key,
                        value = nestedValue,
                        location = "$location.$key",
                        absolutePath = resolved,
                        existsOnDisk = Files.exists(resolved),
                    )
                } else {
                    collectReferencedFiles(
                        commandName = commandName,
                        value = nestedValue,
                        references = references,
                        flowDir = flowDir,
                        commandIndex = commandIndex,
                        location = "$location.$key",
                    )
                }
            }
        }
    }

    private fun normalizeHeaderScalar(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> runCatching { daemonYamlMapper.writeValueAsString(value).trim() }.getOrNull()
    }

    private fun looksLikeHeader(candidate: Any?): Boolean {
        val header = candidate as? Map<*, *> ?: return false
        return header.keys.any { key -> key?.toString() in MAESTRO_HEADER_KEYS }
    }

    private fun isMaestroOwnedPath(projectRoot: Path, filePath: Path): Boolean {
        val relative = runCatching { projectRoot.relativize(filePath).pathString }.getOrElse { return false }
        if (relative.startsWith("..")) {
            return false
        }
        val segments = relative.split('/').map { it.lowercase() }
        return "maestro" in segments || ".maestro" in segments
    }

    private fun Path.anyAncestorIgnored(projectRoot: Path): Boolean {
        val relative = runCatching { projectRoot.relativize(this).toList() }.getOrDefault(emptyList())
        return relative.any { segment -> segment.name in IGNORE_DIRS }
    }

    private fun preferredDirectoryPriority(filePath: Path): Int {
        val normalized = filePath.pathString.lowercase()
        return when {
            "/.maestro/" in normalized -> 0
            "/maestro/" in normalized -> 1
            "/e2e/" in normalized -> 2
            else -> 3
        }
    }

    private fun <T> getCacheEntry(cache: ConcurrentHashMap<String, CacheEntry<T>>, key: String): T? {
        val entry = cache[key] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            cache.remove(key)
            return null
        }
        return entry.value
    }

    private fun <T> setCacheEntry(cache: ConcurrentHashMap<String, CacheEntry<T>>, key: String, value: T): T {
        cache[key] = CacheEntry(value = value, expiresAtMs = System.currentTimeMillis() + CACHE_TTL_MS)
        return value
    }
}

private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> =
    entries.associate { (key, value) -> key.toString() to value }
