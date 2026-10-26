@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.mihon.puente.backup
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

private val proto = ProtoBuf { encodeDefaults = false }

fun encodeTachibk(backup: Backup): ByteArray {
    val payload = proto.encodeToByteArray(backup)
    if (payload.isEmpty()) {
        error("El backup quedó vacío. Mihon rechaza un protobuf vacío.")
    }
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(payload) }
    return out.toByteArray()
}

fun decodeTachibk(bytes: ByteArray): Backup {
    val payload = ungzip(bytes)
    return proto.decodeFromByteArray<Backup>(payload)
}

fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private val nameTimestamp = Regex("""(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})""")

/** Fecha embebida en nombres como `app.mihon_2026-10-26_13-45.tachibk`, en la zona local. */
fun backupTakenAtMillis(name: String, fallbackMillis: Long): Long {
    val match = nameTimestamp.find(name) ?: return fallbackMillis
    val (year, month, day, hour, minute) = match.destructured
    return LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt())
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}

private fun ungzip(bytes: ByteArray): ByteArray {
    return try {
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    } catch (_: ZipException) {
        bytes
    }
}
