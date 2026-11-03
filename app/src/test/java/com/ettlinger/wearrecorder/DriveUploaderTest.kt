package com.ettlinger.wearrecorder

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.ArrayDeque

class DriveUploaderTest {
    @Test fun workerCancellationInsideNetworkSetupPropagatesAndRetainsAudio() = runBlocking {
        val store = RecordingStore(Files.createTempDirectory("cancel-upload").toFile())
        val file = store.begin(1).let { it.append(ByteArray(640), 640); it.seal() }
        val uploader = DriveUploader(store, { "synthetic" }, { throw CancellationException("worker stopped") })
        try { uploader.uploadFile(file); fail("Cancellation was reported as a failed upload") }
        catch (_: CancellationException) { assertTrue(file.exists()) }
    }
    @Test fun pausedUploadsDoNotEvenRefreshATokenOrOpenAConnection() = runBlocking {
        val store = RecordingStore(Files.createTempDirectory("paused-upload").toFile())
        val file = store.begin(1).let { it.append(ByteArray(640), 640); it.seal() }
        var tokens = 0; var connections = 0
        val uploader = DriveUploader(store, { tokens++; "synthetic" }, { connections++; error("Must not connect") }, requestAllowed = { false })
        assertNull(uploader.uploadFile(file))
        assertEquals(0, tokens); assertEquals(0, connections)
        assertTrue(file.exists())
    }
    @Test fun policyChangeBeforePostRetainsStableIdAndOriginalWithoutPublishing() = runBlocking {
        val store = RecordingStore(Files.createTempDirectory("mid-upload-pause").toFile())
        val file = store.begin(1).let { it.append(ByteArray(640), 640); it.seal() }
        val replies = ArrayDeque<Reply>()
        replies.add(Reply("/about?",200,"""{"user":{"permissionId":"owner"}}"""))
        replies.add(Reply("generateIds",200,"""{"ids":["retained_id"]}"""))
        replies.add(Reply("/files/retained_id?",404,""))
        replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        val requests = mutableListOf<Fake>()
        val uploader = DriveUploader(store, { "synthetic" }, { url ->
            val reply = replies.removeFirst(); assertTrue(url.contains(reply.match))
            Fake(reply,url).also { requests.add(it) }
        }, requestAllowed = { requests.size < 4 })
        assertNull(uploader.uploadFile(file))
        assertEquals("retained_id", store.readDriveId(file))
        assertEquals(0, requests.count { it.requestMethod == "POST" })
        assertTrue(file.exists())
    }
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
    private class Fixture(batchSize: Int = 1, optimize: Boolean = false) {
        val store = RecordingStore(Files.createTempDirectory("upload-test").toFile())
        val audio = store.begin(123).let { it.append(ByteArray(3200) { n -> n.toByte() }, 3200); it.seal() }
        val replies = ArrayDeque<Reply>()
        val requests = mutableListOf<Fake>()
        var token = "synthetic-test-token"
        val uploader = DriveUploader(store, { token }, { url ->
            assertTrue("Unexpected request: $url", replies.isNotEmpty())
            val reply = replies.removeFirst()
            assertTrue("Expected ${reply.match}, got $url", url.contains(reply.match))
            Fake(reply, url).also { requests.add(it) }
        }, idBatchSize = batchSize, optimizeUploads = optimize)
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
        f.replies.add(Reply("/files/stable_id?",200,f.remote()))
        val copy = f.uploader.uploadFile(f.audio)
        assertNotNull(copy)
        assertEquals(1,f.requests.count { it.requestMethod=="POST" })
        assertTrue(f.audio.exists()) // uploader cannot delete; worker must persist the verified receipt
    }

    @Test fun efficientFreshUploadSkipsOnlyTheUnnecessaryPreflightAndVerifiesAfterPost() = runBlocking {
        val f = Fixture(optimize = true); f.account()
        f.replies.add(Reply("generateIds",200,"""{"ids":["stable_id"]}"""))
        f.replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        f.replies.add(Reply("multipart",200,"""{"id":"stable_id"}"""))
        f.replies.add(Reply("/files/stable_id?",200,f.remote()))
        assertNotNull(f.uploader.uploadFile(f.audio))
        assertEquals(5,f.uploader.requestCount)
        assertEquals(1,f.requests.count { it.url.toString().contains("/files/stable_id?") })
        assertEquals("stable_id", f.store.readDriveId(f.audio))
        assertTrue(f.requests.indexOfFirst { it.requestMethod == "POST" } <
            f.requests.indexOfFirst { it.url.toString().contains("/files/stable_id?") })
        assertTrue(f.audio.exists())
        assertTrue(f.replies.isEmpty())
    }

    @Test fun efficientUploadAfterLostPostResponseInspectsPersistedIdBeforeRetrying() = runBlocking {
        val f = Fixture(optimize = true); f.account()
        f.replies.add(Reply("generateIds",200,"""{"ids":["stable_id"]}"""))
        f.replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        f.replies.add(Reply("multipart",200,"",true))
        assertNull(f.uploader.uploadFile(f.audio))
        assertEquals("stable_id",f.store.readDriveId(f.audio))
        f.replies.add(Reply("/files/stable_id?",200,f.remote()))
        assertNotNull(f.uploader.uploadFile(f.audio))
        assertEquals(1,f.requests.count { it.requestMethod == "POST" })
        assertTrue(f.audio.exists())
        assertTrue(f.replies.isEmpty())
    }

    @Test fun efficientFreshUploadStillRejectsTheWrongRemoteChecksum() = runBlocking {
        val f = Fixture(optimize = true); f.account()
        f.replies.add(Reply("generateIds",200,"""{"ids":["stable_id"]}"""))
        f.replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        f.replies.add(Reply("multipart",200,"""{"id":"stable_id"}"""))
        f.replies.add(Reply("/files/stable_id?",200,f.remote("0".repeat(32))))
        assertNull(f.uploader.uploadFile(f.audio))
        assertTrue(f.audio.exists())
        assertTrue(f.replies.isEmpty())
    }

    @Test fun efficientAllocationIsDurableBeforeTheFirstPost() = runBlocking {
        val store = RecordingStore(Files.createTempDirectory("efficient-durable-id").toFile())
        val audio = store.begin(1).let { it.append(ByteArray(640),640); it.seal() }
        val replies = ArrayDeque<Reply>()
        replies.add(Reply("/about?",200,"""{"user":{"permissionId":"owner"}}"""))
        replies.add(Reply("generateIds",200,"""{"ids":["stable_id"]}"""))
        replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        replies.add(Reply("multipart",200,"",true))
        val uploader = DriveUploader(store, { "synthetic" }, { url ->
            val reply = replies.removeFirst(); assertTrue(url.contains(reply.match))
            if (url.contains("multipart")) {
                // A new store sees the identity even if this POST never returns.
                assertEquals("stable_id",RecordingStore(store.directory()).readDriveId(audio))
            }
            Fake(reply,url)
        }, optimizeUploads = true)
        assertNull(uploader.uploadFile(audio))
        assertTrue(audio.exists())
        assertTrue(replies.isEmpty())
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
    @Test fun onePassSharesAccountCheckAndIdAllocationWithoutSkippingVerification() = runBlocking {
        val f = Fixture(2)
        val second = f.store.begin(124).let { it.append(ByteArray(3200) { n -> n.toByte() }, 3200); it.seal() }
        f.account()
        f.replies.add(Reply("generateIds?count=2",200,"""{"ids":["stable_id","second_id"]}"""))
        f.replies.add(Reply("/files/stable_id?",404,""))
        f.replies.add(Reply("/files?q=",200,"""{"files":[{"id":"folder"}]}"""))
        f.replies.add(Reply("multipart",200,"""{"id":"stable_id"}"""))
        f.replies.add(Reply("/files/stable_id?",200,f.remote()))
        f.replies.add(Reply("/files/second_id?",404,""))
        f.replies.add(Reply("multipart",200,"""{"id":"second_id"}"""))
        f.replies.add(Reply("/files/second_id?",200,f.remote().replace("stable_id", "second_id")))
        assertNotNull(f.uploader.uploadFile(f.audio))
        assertNotNull(f.uploader.uploadFile(second))
        assertEquals(1,f.requests.count { it.url.toString().contains("/about?") })
        assertEquals(1,f.requests.count { it.url.toString().contains("generateIds") })
        assertEquals(4,f.requests.count { it.requestMethod == "GET" && Regex("/files/(stable_id|second_id)\\?").containsMatchIn(it.url.toString()) })
        assertEquals(9,f.uploader.requestCount)
        assertTrue(f.audio.exists() && second.exists())
    }
    @Test fun aChangedTokenMustProveOwnerBeforeAnyCachedIdentityIsUsed() = runBlocking {
        val f = Fixture(); f.store.saveDriveId(f.audio,"stable_id"); f.account()
        f.replies.add(Reply("/files/stable_id?",200,f.remote()))
        assertNotNull(f.uploader.uploadFile(f.audio))
        f.token = "synthetic-other-account"
        f.replies.add(Reply("/about?",200,"""{"user":{"permissionId":"different_owner"}}"""))
        assertNull(f.uploader.uploadFile(f.audio))
        assertEquals(3,f.requests.size)
        assertTrue(f.audio.exists())
    }
    @Test fun malformedIdPoolCannotPublishTwoRecordingsUnderOneId() = runBlocking {
        val f = Fixture(2); f.account()
        f.replies.add(Reply("generateIds",200,"""{"ids":["duplicate","duplicate"]}"""))
        assertNull(f.uploader.uploadFile(f.audio))
        assertEquals(0,f.requests.count { it.requestMethod == "POST" })
        assertNull(f.store.readDriveId(f.audio))
        assertTrue(f.audio.exists())
    }
    @Test fun diagnosticsAreCreatedInsideTheLogsChildAndRemainUntilAcknowledged() = runBlocking {
        val store = RecordingStore(Files.createTempDirectory("log-upload-test").toFile())
        val log = File(store.directory(), "RaveAI_log_test.log").apply { writeText("battery=90\n") }
        val replies = ArrayDeque<Reply>()
        replies.add(Reply("/about?",200,"""{"user":{"permissionId":"owner"}}"""))
        replies.add(Reply("generateIds",200,"""{"ids":["log_id"]}"""))
        replies.add(Reply("/files/log_id?",404,""))
        replies.add(Reply("/files?q=",200,"""{"files":[{"id":"rave_root"}]}"""))
        replies.add(Reply("/files?q=",200,"""{"files":[]}"""))
        replies.add(Reply("/drive/v3/files",200,"""{"id":"logs_child"}"""))
        replies.add(Reply("uploadType=multipart",200,"""{"id":"log_id"}"""))
        replies.add(Reply("/files/log_id?",200,"""{"id":"log_id","size":"${log.length()}","md5Checksum":"${RecordingStore.digest(log,"MD5")}"}"""))
        val requests = mutableListOf<Fake>()
        val uploader = DriveUploader(store, { "synthetic" }, { url ->
            val reply = replies.removeFirst(); assertTrue(url.contains(reply.match))
            Fake(reply,url).also { requests.add(it) }
        })
        assertNotNull(uploader.uploadFile(log, logFile = true))
        val folder = requests.first { it.requestMethod == "POST" && !it.url.toString().contains("multipart") }.sent.toString("UTF-8")
        assertTrue(folder.contains("rave_root")); assertTrue(folder.contains("logs"))
        val multipart = requests.first { it.url.toString().contains("multipart") }.sent.toString("UTF-8")
        assertTrue(multipart.contains("logs_child")); assertTrue(multipart.contains("text/plain"))
        assertTrue(log.exists())
    }

}
