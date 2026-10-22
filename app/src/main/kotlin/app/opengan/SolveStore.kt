package app.opengan

import app.opengan.core.timer.Solve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Solves live in one plain-text file on the device: easy to export, nothing leaves the phone. */
class SolveStore(private val file: File) {

    suspend fun load(): List<Solve> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull(Solve::parse)
    }

    suspend fun append(solve: Solve) = withContext(Dispatchers.IO) {
        file.appendText(solve.serialize() + "\n")
    }

    suspend fun replaceAll(solves: List<Solve>) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(solves.joinToString("") { it.serialize() + "\n" })
        tmp.renameTo(file)
    }
}
