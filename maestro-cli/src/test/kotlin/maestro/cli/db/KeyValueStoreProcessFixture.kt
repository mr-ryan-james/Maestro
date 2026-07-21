package maestro.cli.db

import java.io.File

object KeyValueStoreProcessFixture {

    @JvmStatic
    fun main(args: Array<String>) {
        val store = KeyValueStore(File(args[0]))
        when (args[1]) {
            "hold" -> store.update { database ->
                println("locked")
                System.out.flush()
                Thread.sleep(args[2].toLong())
                database["first"] = "1"
            }

            "write" -> store.update { database ->
                database["second"] = "2"
            }

            else -> error("Unknown mode ${args[1]}")
        }
    }
}
