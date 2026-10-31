package com.ettlinger.wearrecorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Stable Drive IDs survive ambiguous POST responses. A 2xx response alone is never a receipt. */
class DriveUploader(
    private val store: RecordingStore,
    private val tokenProvider: suspend () -> String? = { AuthManager.getAccessToken() },
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }
) {
    data class VerifiedCopy(val id: String, val size: Long, val md5: String)
    private var folderId: String? = null

    private fun connection(url: String, token: String, method: String = "GET"): HttpURLConnection =
        openConnection(url).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = false
        }

    private fun response(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        if (code == 401) AuthManager.invalidateAccessToken()
        check(code in 200..299) { "Drive HTTP $code" }
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    private fun folder(token: String): String {
        folderId?.let { return it }
        val lookup = connection("https://www.googleapis.com/drive/v3/files?q=name%3D%27ClawHark%27+and+mimeType%3D%27application%2Fvnd.google-apps.folder%27+and+trashed%3Dfalse&fields=files(id)", token)
        val files = try { response(lookup).getJSONArray("files") } finally { lookup.disconnect() }
        if (files.length() > 0) return files.getJSONObject(0).getString("id").also { folderId = it }
        val create = connection("https://www.googleapis.com/drive/v3/files", token, "POST")
        return try {
            create.doOutput = true
            create.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            create.outputStream.use { it.write(JSONObject().put("name", "ClawHark")
                .put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray(Charsets.UTF_8)) }
            response(create).getString("id").also { folderId = it }
        } finally { create.disconnect() }
    }

    private fun allocateId(file: File, token: String): String {
        store.readDriveId(file)?.let { return it }
        val conn = connection("https://www.googleapis.com/drive/v3/files/generateIds?count=1&space=drive&type=files", token)
        val id = try { response(conn).getJSONArray("ids").getString(0) } finally { conn.disconnect() }
        store.saveDriveId(file, id) // synced before making the first mutating network request
        return id
    }

    private fun inspect(id: String, token: String): JSONObject? {
        val conn = connection("https://www.googleapis.com/drive/v3/files/$id?fields=id,size,md5Checksum,trashed", token)
        return try {
            if (conn.responseCode == 404) null else response(conn)
        } finally { conn.disconnect() }
    }

    suspend fun uploadFile(file: File): VerifiedCopy? = withContext(Dispatchers.IO) {
        val token = tokenProvider() ?: return@withContext null
        try {
            val account = connection("https://www.googleapis.com/drive/v3/about?fields=user(permissionId)", token)
            try { store.bindCloudOwner(response(account).getJSONObject("user").getString("permissionId")) }
            finally { account.disconnect() }
            check(file.isFile && file.length() > 0) { "Local recording missing or empty" }
            val localSize = file.length()
            val localMd5 = RecordingStore.digest(file, "MD5")
            val id = allocateId(file, token)
            var remote = inspect(id, token)
            if (remote == null) {
                val destination = folder(token)
                val boundary = "ClawHark${UUID.randomUUID()}"
                val conn = connection("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", token, "POST")
                try {
                    conn.doOutput = true
                    conn.setChunkedStreamingMode(32768)
                    conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                    val metadata = JSONObject().put("id", id).put("name", file.name)
                        .put("parents", JSONArray().put(destination))
                    val mime = if (file.extension == "wav") "audio/wav" else "audio/mp4"
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
        } catch (e: Exception) {
            AppLog.e("Drive", "Upload not verified; local recording retained", e)
            null
        }
    }
}
