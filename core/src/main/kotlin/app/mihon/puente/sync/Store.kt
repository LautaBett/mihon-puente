package app.mihon.puente.sync

import app.mihon.puente.backup.Backup
import app.mihon.puente.backup.decodeTachibk
import app.mihon.puente.backup.encodeTachibk
import app.mihon.puente.backup.sha256
import app.mihon.puente.merge.Clocks
import app.mihon.puente.merge.MergeState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ConflictException(message: String = "La copia cambió mientras se guardaba.") : Exception(message)

data class LoadedLibrary(
    val version: Long,
    val libraryGeneration: Long,
    val librarySha256: String?,
    val state: MergeState,
    val libraryPath: String? = null,
)

interface CanonicalStore {
    fun load(): LoadedLibrary

    fun save(state: MergeState, libraryChanged: Boolean, expectedVersion: Long): LoadedLibrary
}

@Serializable
private data class LocalHead(
    val version: Long = 0,
    val libraryGeneration: Long = 0,
    val librarySha256: String? = null,
)

class LocalCanonicalStore(private val directory: File) : CanonicalStore {
    private val lock = Any()
    private val libraryFile = File(directory, "library.tachibk")
    private val clocksFile = File(directory, "clocks.json")
    private val headFile = File(directory, "head.json")

    override fun load(): LoadedLibrary = synchronized(lock) { read() }

    override fun save(state: MergeState, libraryChanged: Boolean, expectedVersion: Long): LoadedLibrary {
        synchronized(lock) {
            val current = read()
            if (current.version != expectedVersion) throw ConflictException()
            directory.mkdirs()
            val bytes = encodeTachibk(state.backup)
            val sha = sha256(bytes)
            if (libraryChanged) libraryFile.writeBytes(bytes)
            clocksFile.writeText(json.encodeToString(state.clocks))
            val head = LocalHead(
                version = current.version + 1,
                libraryGeneration = if (libraryChanged) current.libraryGeneration + 1 else current.libraryGeneration,
                librarySha256 = if (libraryChanged) sha else current.librarySha256,
            )
            headFile.writeText(json.encodeToString(head))
            return read()
        }
    }

    private fun read(): LoadedLibrary {
        val head = if (headFile.isFile) json.decodeFromString<LocalHead>(headFile.readText()) else LocalHead()
        val backup = if (libraryFile.isFile) decodeTachibk(libraryFile.readBytes()) else Backup()
        val clocks = if (clocksFile.isFile) json.decodeFromString<Clocks>(clocksFile.readText()) else Clocks()
        return LoadedLibrary(head.version, head.libraryGeneration, head.librarySha256, MergeState(backup, clocks))
    }
}

internal val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}
