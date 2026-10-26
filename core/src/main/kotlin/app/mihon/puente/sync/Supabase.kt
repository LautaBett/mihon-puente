package app.mihon.puente.sync

import app.mihon.puente.backup.Backup
import app.mihon.puente.backup.decodeTachibk
import app.mihon.puente.backup.encodeTachibk
import app.mihon.puente.backup.sha256
import app.mihon.puente.merge.Clocks
import app.mihon.puente.merge.MergeState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Copia canónica en Supabase. Los archivos se suben a una ruta nueva y
 * `advance_head` recién ahí apunta la fila. Si otra app ganó la versión,
 * la ruta nueva queda huérfana y no pisa la que ya estaba publicada.
 */
class SupabaseCanonicalStore(
    private val config: PuenteConfig,
    private val deviceKey: String,
    private val platform: String,
) : CanonicalStore {
    private val base = config.supabaseUrl.trimEnd('/')
    private var accessToken: String? = null
    private var userId: String? = null

    override fun load(): LoadedLibrary {
        val token = token()
        val user = userId ?: error("La sesión no trajo el id del usuario.")
        registerDevice(token, user)
        val rows = getJson<List<HeadRow>>(
            "$base/rest/v1/library_head?select=*",
            token,
        )
        val head = rows.firstOrNull()
            ?: return LoadedLibrary(0, 0, null, MergeState(), null)
        val backup = head.libraryPath?.let { path ->
            val bytes = download(path, token) ?: return@let Backup()
            decodeTachibk(bytes)
        } ?: Backup()
        val clocks = head.clocksPath?.let { path ->
            val bytes = download(path, token) ?: return@let Clocks()
            json.decodeFromString<Clocks>(bytes.decodeToString())
        } ?: Clocks()
        return LoadedLibrary(
            version = head.stateVersion,
            libraryGeneration = head.libraryGeneration,
            librarySha256 = head.librarySha256,
            state = MergeState(backup, clocks),
            libraryPath = head.libraryPath,
        )
    }

    override fun save(state: MergeState, libraryChanged: Boolean, expectedVersion: Long): LoadedLibrary {
        val token = token()
        val user = userId ?: error("La sesión no trajo el id del usuario.")
        val current = load()
        if (current.version != expectedVersion) throw ConflictException()
        val nextVersion = current.version + 1
        val encoded = encodeTachibk(state.backup)
        val libraryPath = if (libraryChanged) {
            val path = "$user/library/$nextVersion-$deviceKey.tachibk"
            upload(path, encoded, token)
            path
        } else {
            current.libraryPath ?: error("No hay archivo de biblioteca para conservar.")
        }
        val clocksPath = "$user/clocks/$nextVersion-$deviceKey.json"
        upload(clocksPath, json.encodeToString(state.clocks).encodeToByteArray(), token)
        val librarySha = if (libraryChanged) sha256(encoded) else current.librarySha256
        val generation = if (libraryChanged) current.libraryGeneration + 1 else current.libraryGeneration
        val advanced = postJson<List<HeadRow>>(
            "$base/rest/v1/rpc/advance_head",
            token,
            json.encodeToString(
                AdvanceHead(
                    expectedVersion = expectedVersion,
                    nextVersion = nextVersion,
                    nextLibraryGeneration = generation,
                    nextLibraryPath = libraryPath,
                    nextLibrarySha = librarySha,
                    nextClocksPath = clocksPath,
                ),
            ).encodeToByteArray(),
        )
        if (advanced.isEmpty()) throw ConflictException()
        return load()
    }

    private fun token(): String {
        accessToken?.let { return it }
        val response = postJson<AuthResponse>(
            "$base/auth/v1/token?grant_type=password",
            bearer = config.publishableKey,
            body = json.encodeToString(AuthRequest(config.email, config.password)).encodeToByteArray(),
        )
        accessToken = response.accessToken
        userId = response.user.id
        return response.accessToken
    }

    private fun registerDevice(token: String, user: String) {
        val name = config.deviceName.ifBlank { platform }
        postJson<Unit>(
            "$base/rest/v1/devices?on_conflict=user_id,device_key",
            token,
            json.encodeToString(
                DeviceRow(user, deviceKey, name, platform),
            ).encodeToByteArray(),
            extra = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
            emptyOk = true,
        )
    }

    private fun upload(path: String, bytes: ByteArray, token: String) {
        val response = http(
            url = objectUrl(path),
            method = "POST",
            headers = authHeaders(token) + mapOf(
                "Content-Type" to "application/octet-stream",
                "x-upsert" to "true",
            ),
            body = bytes,
        )
        if (response.code !in 200..299) {
            error("Storage respondió ${response.code}: ${response.text()}")
        }
    }

    private fun download(path: String, token: String): ByteArray? {
        val response = http(
            url = objectUrl(path),
            method = "GET",
            headers = authHeaders(token),
            body = null,
        )
        if (response.code == 404) return null
        if (response.code !in 200..299) error("Storage respondió ${response.code}: ${response.text()}")
        return response.bytes
    }

    private inline fun <reified T> getJson(url: String, token: String): T {
        val response = http(url, "GET", authHeaders(token) + mapOf("Accept" to "application/json"), null)
        if (response.code == 401) {
            accessToken = null
        }
        if (response.code !in 200..299) error("Supabase respondió ${response.code}: ${response.text()}")
        if (T::class == Unit::class) return Unit as T
        return json.decodeFromString(response.text())
    }

    private inline fun <reified T> postJson(
        url: String,
        bearer: String,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
        emptyOk: Boolean = false,
    ): T {
        val response = http(
            url,
            "POST",
            authHeaders(bearer) + mapOf("Content-Type" to "application/json", "Accept" to "application/json") + extra,
            body,
        )
        if (response.code == 401 && bearer != config.publishableKey) accessToken = null
        if (response.code !in 200..299) error("Supabase respondió ${response.code}: ${response.text()}")
        if (emptyOk || response.bytes.isEmpty() || T::class == Unit::class) return Unit as T
        return json.decodeFromString(response.text())
    }

    private fun authHeaders(bearer: String) = mapOf(
        "apikey" to config.publishableKey,
        "Authorization" to "Bearer $bearer",
    )

    private fun objectUrl(path: String): String {
        val encoded = path.split("/").joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")
        }
        return "$base/storage/v1/object/puente/$encoded"
    }
}

@Serializable
private data class AuthRequest(val email: String, val password: String)

@Serializable
private data class AuthResponse(
    @SerialName("access_token") val accessToken: String,
    val user: AuthUser,
)

@Serializable
private data class AuthUser(val id: String)

@Serializable
private data class HeadRow(
    @SerialName("user_id") val userId: String,
    @SerialName("state_version") val stateVersion: Long,
    @SerialName("library_generation") val libraryGeneration: Long = 0,
    @SerialName("library_path") val libraryPath: String? = null,
    @SerialName("library_sha256") val librarySha256: String? = null,
    @SerialName("clocks_path") val clocksPath: String? = null,
)

@Serializable
private data class AdvanceHead(
    @SerialName("expected_version") val expectedVersion: Long,
    @SerialName("next_version") val nextVersion: Long,
    @SerialName("next_library_generation") val nextLibraryGeneration: Long,
    @SerialName("next_library_path") val nextLibraryPath: String,
    @SerialName("next_library_sha") val nextLibrarySha: String?,
    @SerialName("next_clocks_path") val nextClocksPath: String,
)

@Serializable
private data class DeviceRow(
    @SerialName("user_id") val userId: String,
    @SerialName("device_key") val deviceKey: String,
    val name: String,
    val platform: String,
)

private data class HttpResponse(val code: Int, val bytes: ByteArray) {
    fun text() = bytes.decodeToString().take(500)
}

private fun http(url: String, method: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.requestMethod = method
    connection.connectTimeout = 15_000
    connection.readTimeout = 120_000
    connection.doInput = true
    headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
    if (body != null) {
        connection.doOutput = true
        connection.outputStream.use { it.write(body) }
    }
    val code = connection.responseCode
    val stream = if (code >= 400) connection.errorStream else connection.inputStream
    val bytes = stream?.use { it.readBytes() } ?: byteArrayOf()
    connection.disconnect()
    return HttpResponse(code, bytes)
}
