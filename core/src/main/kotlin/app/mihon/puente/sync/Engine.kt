package app.mihon.puente.sync

import app.mihon.puente.backup.backupTakenAtMillis
import app.mihon.puente.backup.decodeTachibk
import app.mihon.puente.backup.encodeTachibk
import app.mihon.puente.backup.sha256
import app.mihon.puente.merge.merge
import app.mihon.puente.merge.unsafeReasons
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class PuenteConfig(
    val watchFolder: String = "",
    val supabaseUrl: String = "",
    val publishableKey: String = "",
    val email: String = "",
    val password: String = "",
    val deviceName: String = "",
)

@Serializable
data class Memory(
    val ingestedSha: Set<String> = emptySet(),
    val failedSha: Set<String> = emptySet(),
    val offeredLibrarySha: String? = null,
    val lastNotice: String? = null,
    val deviceKey: String = "",
)

data class NamedBackup(
    val name: String,
    val bytes: ByteArray,
    val modifiedAtMillis: Long,
)

data class SyncTick(
    val lines: List<String>,
    val importName: String?,
    val importBytes: ByteArray?,
    val importSha: String? = null,
)

class PuenteEngine(
    private val config: PuenteConfig,
    private val directory: File,
    private val platform: String,
) {
    private val lock = Any()
    private val memoryFile = File(directory, "memory.json")

    fun synchronize(files: List<NamedBackup>): SyncTick = synchronized(lock) {
        val lines = mutableListOf<String>()
        var memory = loadMemory()
        val store = try {
            openStore(memory)
        } catch (error: Exception) {
            saveMemory(memory)
            return@synchronized SyncTick(listOf(error.message ?: "No pude abrir la copia."), null, null)
        }
        val pending = files
            .filter { it.name.endsWith(".tachibk") && !it.name.startsWith("puente-") }
            .filter { sha256(it.bytes) !in memory.ingestedSha && sha256(it.bytes) !in memory.failedSha }
            .sortedBy { backupTakenAtMillis(it.name, it.modifiedAtMillis) }
        for (file in pending) {
            if (file.modifiedAtMillis > System.currentTimeMillis() - 2_000) continue
            val sha = sha256(file.bytes)
            if (file.bytes.isEmpty()) {
                memory = memory.copy(failedSha = memory.failedSha + sha)
                lines += "«${file.name}» está vacío."
                continue
            }
            val backup = try {
                decodeTachibk(file.bytes)
            } catch (error: Exception) {
                memory = memory.copy(failedSha = memory.failedSha + sha)
                lines += "No pude leer «${file.name}»: ${error.message}"
                continue
            }
            val takenAt = backupTakenAtMillis(file.name, file.modifiedAtMillis)
            try {
                val saved = ingest(store, backup, takenAt)
                memory = memory.copy(ingestedSha = memory.ingestedSha + sha)
                lines += saved
            } catch (error: Exception) {
                lines += "No pude incorporar «${file.name}»: ${error.message}"
            }
        }
        val newest = files
            .filter { it.name.endsWith(".tachibk") && !it.name.startsWith("puente-") }
            .maxByOrNull { backupTakenAtMillis(it.name, it.modifiedAtMillis) }
        var importName: String? = null
        var importBytes: ByteArray? = null
        var importSha: String? = null
        if (newest != null) {
            val decision = decideImport(store, memory, newest)
            if (decision.bytes != null) {
                importName = "puente-importar.tachibk"
                importBytes = decision.bytes
                importSha = decision.librarySha
                lines += decision.line
                memory = memory.copy(lastNotice = null)
            } else if (decision.line != memory.lastNotice && decision.line !in STEADY_NOTICES) {
                lines += decision.line
                memory = memory.copy(lastNotice = decision.line)
            }
        }
        saveMemory(memory)
        SyncTick(lines, importName, importBytes, importSha)
    }

    fun markOffered(librarySha: String) = synchronized(lock) {
        saveMemory(loadMemory().copy(offeredLibrarySha = librarySha))
    }

    private fun ingest(store: CanonicalStore, backup: app.mihon.puente.backup.Backup, takenAt: Long): String {
        var attempt = 0
        while (true) {
            val loaded = store.load()
            val outcome = merge(loaded.state, backup, takenAt)
            if (!outcome.libraryChanged && !outcome.clocksChanged) {
                return "Sin cambios nuevos. La copia ya tenía este backup."
            }
            try {
                val saved = store.save(outcome.state, outcome.libraryChanged, loaded.version)
                return "Incorporado. Series nuevas: ${outcome.mangaAdded}. " +
                    "Series actualizadas: ${outcome.mangaUpdated}. " +
                    "Capítulos nuevos: ${outcome.chaptersAdded}. " +
                    "Con más progreso: ${outcome.chaptersProgressed}. " +
                    "Versión ${saved.version}."
            } catch (_: ConflictException) {
                if (++attempt == 3) throw ConflictException("Otra app escribió la copia tres veces seguidas.")
            }
        }
    }

    private fun decideImport(store: CanonicalStore, memory: Memory, newest: NamedBackup): ImportDecision {
        val sha = sha256(newest.bytes)
        if (sha !in memory.ingestedSha) {
            return ImportDecision("El backup más nuevo todavía no se incorporó.", null, null)
        }
        val loaded = store.load()
        if (loaded.librarySha256 == null) {
            return ImportDecision("Todavía no hay una copia compartida.", null, null)
        }
        if (loaded.librarySha256 == memory.offeredLibrarySha) {
            return ImportDecision("La importación ya está en salida/.", null, null)
        }
        val local = decodeTachibk(newest.bytes)
        val reasons = unsafeReasons(local, loaded.state.backup)
        if (reasons.isNotEmpty()) {
            return ImportDecision(reasons.joinToString(" "), null, null)
        }
        val bytes = encodeTachibk(loaded.state.backup)
        return ImportDecision(
            "Copia lista para importar (${loaded.libraryGeneration}).",
            bytes,
            loaded.librarySha256,
        )
    }

    private fun openStore(memory: Memory): CanonicalStore {
        if (config.supabaseUrl.isBlank()) return LocalCanonicalStore(File(directory, "local"))
        val missing = listOf(
            "URL" to config.supabaseUrl,
            "clave publicable" to config.publishableKey,
            "email" to config.email,
            "contraseña" to config.password,
        ).filter { it.second.isBlank() }.map { it.first }
        if (missing.isNotEmpty()) {
            error("Falta ${missing.joinToString(", ")} de Supabase.")
        }
        return SupabaseCanonicalStore(
            config = config,
            deviceKey = memory.deviceKey,
            platform = platform,
        )
    }

    private fun loadMemory(): Memory {
        val memory = if (memoryFile.isFile) {
            json.decodeFromString<Memory>(memoryFile.readText())
        } else {
            Memory()
        }
        if (memory.deviceKey.isNotBlank()) return memory
        val created = memory.copy(deviceKey = java.util.UUID.randomUUID().toString())
        saveMemory(created)
        return created
    }

    private fun saveMemory(memory: Memory) {
        directory.mkdirs()
        memoryFile.writeText(json.encodeToString(memory))
    }
}

private val STEADY_NOTICES = setOf(
    "La importación ya está en salida/.",
    "Todavía no hay una copia compartida.",
    "El backup más nuevo todavía no se incorporó.",
)

private data class ImportDecision(
    val line: String,
    val bytes: ByteArray?,
    val librarySha: String?,
)

fun readConfig(file: File): PuenteConfig {
    if (!file.isFile) return PuenteConfig()
    return json.decodeFromString(file.readText())
}

fun writeConfig(file: File, config: PuenteConfig) {
    file.parentFile?.mkdirs()
    file.writeText(json.encodeToString(config))
}
