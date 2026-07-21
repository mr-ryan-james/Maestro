package maestro.cli.db

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class KeyValueStore(private val dbFile: File) {
    private val lock = locks.computeIfAbsent(dbFile.canonicalPath) { ReentrantLock() }

    init {
        lock.withLock {
            dbFile.parentFile?.mkdirs()
            dbFile.createNewFile()
        }
    }

    fun get(key: String): String? = read { it[key] }

    fun set(key: String, value: String) {
        update { db ->
            db[key] = value
        }
    }

    fun delete(key: String) {
        update { db ->
            db.remove(key)
        }
    }

    fun keys(): List<String> = read { it.keys.toList() }

    fun entries(): Map<String, String> = read { it.toMap() }

    fun <T> update(block: (MutableMap<String, String>) -> T): T {
        return withLockedDatabase(write = true, block)
    }

    private fun <T> read(block: (MutableMap<String, String>) -> T): T {
        return withLockedDatabase(write = false, block)
    }

    private fun readDatabase(randomAccessFile: RandomAccessFile): MutableMap<String, String> {
        val length = randomAccessFile.length()
        if (length == 0L) return mutableMapOf()
        require(length <= Int.MAX_VALUE) { "Key-value store is too large" }
        val bytes = ByteArray(length.toInt())
        randomAccessFile.seek(0L)
        randomAccessFile.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
            .lineSequence()
            .filter { it.contains("=") }
            .associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key to value
            }
            .toMutableMap()
    }

    private fun writeDatabase(randomAccessFile: RandomAccessFile, database: MutableMap<String, String>) {
        val bytes = database
            .map { (key, value) -> "$key=$value" }
            .joinToString("\n")
            .toByteArray(StandardCharsets.UTF_8)
        randomAccessFile.setLength(0L)
        randomAccessFile.seek(0L)
        randomAccessFile.write(bytes)
        randomAccessFile.channel.force(true)
    }

    private fun <T> withLockedDatabase(
        write: Boolean,
        block: (MutableMap<String, String>) -> T,
    ): T {
        return lock.withLock {
            RandomAccessFile(dbFile, "rw").use { randomAccessFile ->
                randomAccessFile.channel.lock().use {
                    val database = readDatabase(randomAccessFile)
                    val result = block(database)
                    if (write) {
                        writeDatabase(randomAccessFile, database)
                    }
                    result
                }
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
