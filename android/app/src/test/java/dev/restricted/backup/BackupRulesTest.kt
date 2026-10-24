package dev.restricted.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BackupRulesTest {
    private val id = "123e4567-e89b-42d3-a456-426614174000"
    @Test fun ownNamespaceIsFixed() {
        assertEquals("/DeviceBackup/$id/", ScopePolicy.namespace(id))
        assertEquals("backup/files/$id/download", ScopePolicy.downloadPath(id, false))
    }
    @Test fun allowedDownloadsUseDedicatedEndpoint() {
        assertEquals("allowed/$id/download", ScopePolicy.downloadPath(id, true))
    }
    @Test fun pathAndIdManipulationAreRejected() {
        for (value in listOf("../private", "$id/../other", "%2fprivate", "private", "1-1-1-1-1", "")) {
            assertThrows(IllegalArgumentException::class.java) { ScopePolicy.downloadPath(value, false) }
        }
    }
    @Test fun sha256MatchesStandardVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentDigest.sha256(ByteArrayInputStream("abc".toByteArray())))
    }
    @Test fun duplicateBytesHaveTheSameDigest() {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        assertEquals(ContentDigest.sha256(bytes.inputStream()), ContentDigest.sha256(bytes.copyOf().inputStream()))
        bytes[70_000] = 0
        assertNotEquals(ContentDigest.sha256(bytes.inputStream()), ContentDigest.sha256(ByteArray(150_000) { (it % 251).toByte() }.inputStream()))
    }
    @Test fun incrementalKeyChangesWhenMediaChanges() {
        val original = LocalMedia("content://images/1", "photo.jpg", "image/jpeg", 20, 1)
        assertEquals(original.sourceKey, original.copy().sourceKey)
        assertNotEquals(original.sourceKey, original.copy(modified = 2).sourceKey)
        assertNotEquals(original.sourceKey, original.copy(size = 30).sourceKey)
    }
    @Test fun retryOnlyTransientHttpFailures() {
        for (status in listOf(408, 429, 500, 502, 503)) assertTrue(BackupRules.retryable(status))
        for (status in listOf(200, 400, 401, 403, 404, 413, 422)) assertFalse(BackupRules.retryable(status))
    }
    @Test fun selectedDocumentRevisionDetectsSameSizeEdits() {
        val original = LocalMedia("content://docs/1", "file.txt", "text/plain", 3, 0, "hash-one")
        assertNotEquals(original.sourceKey, original.copy(revision = "hash-two").sourceKey)
        assertEquals(original.sourceKey, original.copy().sourceKey)
    }
    @Test fun progressIsBoundedAndSupportsEmptyFiles() {
        assertEquals(50, BackupRules.progress(5, 10))
        assertEquals(100, BackupRules.progress(0, 0))
        assertEquals(100, BackupRules.progress(100, 10))
        assertEquals(0, BackupRules.progress(-2, 10))
    }
    @Test fun fileLimitIsEnforced() {
        assertTrue(BackupRules.validSize(0))
        assertTrue(BackupRules.validSize(BackupRules.MAX_BYTES))
        assertFalse(BackupRules.validSize(-1))
        assertFalse(BackupRules.validSize(BackupRules.MAX_BYTES + 1))
    }
}
