package com.ettlinger.wearrecorder

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.ArrayDeque

class DriveUploaderTest {
    private class Reply(val match: String, val code: Int, val body: String, val timeout: Boolean = false)
    private class Fake(val reply: Reply, url: String) : HttpURLConnection(URL(url)) {
        val sent = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): OutputStream = sent
        override fun getResponseCode(): Int { if (reply.timeout) throw SocketTimeoutException("response lost"); return reply.code }
        override fun getInputStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray())
    }
    private class Fixture {
        val store = RecordingStore(Files.createTempDirectory("upload-test").toFile())
        val audio = store.begin(123).let { it.append(ByteArray(3200) { n -> n.toByte() }, 3200); it.seal() }
        val replies = ArrayDeque<Reply>()
        val requests = mutableListOf<Fake>()
        val uploader = DriveUploader(store, { "synthetic-test-token" }, { url ->
            assertTrue("Unexpected request: $url", replies.isNotEmpty())
            val reply = replies.removeFirst()
            assertTrue("Expected ${reply.match}, got $url", url.contains(reply.match))
            Fake(reply, url).also { requests.add(it) }
        })
        fun account() { replies.add(Reply("/about?", 200, """{"user":{"permissionId":"owner"}}""")) }
        fun remote(md5: String = RecordingStore.digest(audio, "MD5")): String =
            """{"id":"stable_id","size":"${audio.length()}","md5Checksum":"$md5","trashed":false}"""
    }

    @Test fun lostUploadResponseReusesDurableIdAndDoesNotPostAgain() = runBlocking {
        val f = Fixture()
        f.account()
        f.replies.add(Reply("generateIds", 200, """{"ids":["stable_id"]}"""))
        f.replies.add(Reply("/files/stable_id?",404,""))
        f.replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        f.replies.add(Reply("uploadType=multipart",200,"",true))
        assertNull(f.uploader.uploadFile(f.audio))
        assertTrue(f.audio.exists())
        assertEquals("stable_id",f.store.readDriveId(f.audio))
        f.account()
        f.replies.add(Reply("/files/stable_id?",200,f.remote()))
        val copy = f.uploader.uploadFile(f.audio)
        assertNotNull(copy)
        assertEquals(1,f.requests.count { it.requestMethod=="POST" })
        assertTrue(f.audio.exists()) // uploader cannot delete; worker must persist the verified receipt
    }

    @Test fun sameSizeWrongChecksumIsNotAcknowledged() = runBlocking {
        val f=Fixture(); f.store.saveDriveId(f.audio,"stable_id"); f.account()
        f.replies.add(Reply("/files/stable_id?",200,f.remote("0".repeat(32))))
        assertNull(f.uploader.uploadFile(f.audio)); assertTrue(f.audio.exists())
    }

    @Test fun successResponseWithoutRemoteMetadataKeepsLocalFile() = runBlocking {
        val f=Fixture(); f.store.saveDriveId(f.audio,"stable_id"); f.account()
        f.replies.add(Reply("/files/stable_id?",404,""))
        f.replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        f.replies.add(Reply("uploadType=multipart",200,"""{"id":"stable_id"}"""))
        f.replies.add(Reply("/files/stable_id?",404,""))
        assertNull(f.uploader.uploadFile(f.audio)); assertTrue(f.audio.exists())
    }

    @Test fun conflictAfterAmbiguousAttemptRequiresMatchingRemoteCopy() = runBlocking {
        val f=Fixture(); f.store.saveDriveId(f.audio,"stable_id"); f.account()
        f.replies.add(Reply("/files/stable_id?",404,""))
        f.replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        f.replies.add(Reply("uploadType=multipart",409,""))
        f.replies.add(Reply("/files/stable_id?",200,f.remote()))
        assertNotNull(f.uploader.uploadFile(f.audio)); assertTrue(f.audio.exists())
    }

    @Test fun mismatchedAccountMakesNoUploadRequest() = runBlocking {
        val f=Fixture(); f.store.bindCloudOwner("another_owner"); f.account()
        assertNull(f.uploader.uploadFile(f.audio)); assertTrue(f.audio.exists())
        assertEquals(1,f.requests.size)
    }
}
