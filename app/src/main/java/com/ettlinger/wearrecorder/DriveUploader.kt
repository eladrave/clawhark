package com.ettlinger.wearrecorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.ArrayDeque

/** Stable Drive IDs survive ambiguous POST responses. A 2xx response alone is never a receipt. */
class DriveUploader(
    private val store: RecordingStore,
    private val tokenProvider: suspend () -> String? = { AuthManager.getAccessToken() },
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val ownerStore: RecordingStore = store,
    idBatchSize: Int = 1,
    private val requestAllowed: () -> Boolean = { true }
) {
    data class VerifiedCopy(val id: String, val size: Long, val md5: String)
    private val folders = mutableMapOf<String, String>()
    private val idCount = idBatchSize.coerceIn(1, 32)
    private val availableIds = ArrayDeque<String>()
    private var verifiedToken: String? = null
    var requestCount = 0
        private set

    private class UploadPaused : java.io.IOException()
    private fun connection(url: String, token: String, method: String = "GET"): HttpURLConnection {
        if (!requestAllowed()) throw UploadPaused()
        return openConnection(url).apply {
            requestCount++
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = false
        }
    }

    private fun response(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        if (code == 401) AuthManager.invalidateAccessToken()
        if (code !in 200..299) AppLog.w("Drive", "Request failed HTTP $code")
        check(code in 200..299) { "Drive HTTP $code" }
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    private fun folder(token: String, name: String = "Rave AI", parent: String = "root"): String {
        val key = "$parent/$name"
        folders[key]?.let { return it }
        val query = "name='$name' and mimeType='application/vnd.google-apps.folder' and trashed=false and '$parent' in parents"
        val lookup = connection("https://www.googleapis.com/drive/v3/files?q=${URLEncoder.encode(query, "UTF-8")}&fields=files(id)", token)
        val files = try { response(lookup).getJSONArray("files") } finally { lookup.disconnect() }
        if (files.length() > 0) return files.getJSONObject(0).getString("id").also { folders[key] = it }
        val create = connection("https://www.googleapis.com/drive/v3/files", token, "POST")
        return try {
            create.doOutput = true
            create.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val metadata = JSONObject().put("name", name).put("mimeType", "application/vnd.google-apps.folder")
            if (parent != "root") metadata.put("parents", JSONArray().put(parent))
            create.outputStream.use { it.write(metadata.toString().toByteArray(Charsets.UTF_8)) }
            response(create).getString("id").also { folders[key] = it }
        } finally { create.disconnect() }
    }

    private fun allocateId(file: File, token: String): String {
        store.readDriveId(file)?.let { store.saveDriveId(file, it); return it }
        if (availableIds.isEmpty()) {
            val conn = connection("https://www.googleapis.com/drive/v3/files/generateIds?count=$idCount&space=drive&type=files", token)
            val ids = try { response(conn).getJSONArray("ids") } finally { conn.disconnect() }
            check(ids.length() in 1..idCount) { "Invalid Drive ID batch" }
            val batch = (0 until ids.length()).map { ids.getString(it) }
            check(batch.all { it.isNotBlank() } && batch.distinct().size == batch.size) { "Invalid Drive IDs" }
            availableIds.addAll(batch)
        }
        val id = availableIds.removeFirst()
        store.saveDriveId(file, id) // synced before making the first mutating network request
        return id
    }

    private fun verifyOwner(token: String) {
        if (verifiedToken == token) return
        // The cache is scoped to this finite upload pass AND exact access token. A refreshed or
        // changed token must prove its account before any stored ID or cached folder is reused.
        verifiedToken = null
        availableIds.clear()
        folders.clear()
        val account = connection("https://www.googleapis.com/drive/v3/about?fields=user(permissionId)", token)
        try {
            val owner = response(account).getJSONObject("user").getString("permissionId")
            ownerStore.bindCloudOwner(owner)
            store.bindCloudOwner(owner)
            verifiedToken = token
        } finally { account.disconnect() }
    }

    private fun inspect(id: String, token: String): JSONObject? {
        val conn = connection("https://www.googleapis.com/drive/v3/files/$id?fields=id,size,md5Checksum,trashed", token)
        return try {
            if (conn.responseCode == 404) null else response(conn)
        } finally { conn.disconnect() }
    }

    suspend fun uploadFile(file: File, logFile: Boolean = false): VerifiedCopy? = withContext(Dispatchers.IO) {
        if (!requestAllowed()) return@withContext null
        val token = tokenProvider() ?: return@withContext null
        try {
            verifyOwner(token)
            check(file.isFile && file.length() > 0) { "Local recording missing or empty" }
            val localSize = file.length()
            val localMd5 = RecordingStore.digest(file, "MD5")
            val id = allocateId(file, token)
            var remote = inspect(id, token)
            if (remote == null) {
                val root = folder(token)
                val destination = if (logFile) folder(token, "logs", root) else root
                val boundary = "ClawHark${UUID.randomUUID()}"
                val conn = connection("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", token, "POST")
                try {
                    conn.doOutput = true
                    conn.setChunkedStreamingMode(32768)
                    conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                    val metadata = JSONObject().put("id", id).put("name", file.name)
                        .put("parents", JSONArray().put(destination))
                    val mime = if (logFile) "text/plain; charset=UTF-8" else if (file.extension == "wav") "audio/wav" else "audio/mp4"
                    conn.outputStream.buffered().use { out ->
                        fun write(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
                        write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata")
                        write("\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n")
                        file.inputStream().use { it.copyTo(out, 32768) }
                        write("\r\n--$boundary--\r\n")
                    }
                    // 409 can mean an earlier attempt succeeded. Always read and verify that object.
                    if (conn.responseCode != 409) response(conn)
                } finally { conn.disconnect() }
                remote = inspect(id, token)
            }
            val copy = remote ?: error("Cloud copy not yet visible")
            check(!copy.optBoolean("trashed", false)) { "Cloud copy is in Trash; local audio retained" }
            check(copy.getString("id") == id && copy.getLong("size") == localSize
                && copy.getString("md5Checksum").equals(localMd5, ignoreCase = true)) {
                "Cloud copy does not match; local audio retained"
            }
            VerifiedCopy(id, localSize, localMd5)
        } catch (_: UploadPaused) {
            AppLog.i("Upload", "Upload paused by settings; local file retained")
            null
        } catch (e: Exception) {
            AppLog.e("Drive", "Upload not verified; local recording retained", e)
            null
        }
    }
}
