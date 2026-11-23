package com.vinabeef.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OutboxSyncerTest {

    private fun payload(id: String) = """{"clientEventId":"$id"}"""
    private fun idOf(payload: String) = Regex("\"clientEventId\":\"([^\"]*)\"").find(payload)!!.groupValues[1]

    /** The phone's outbox: records leave it when acknowledged, and stop being "pending" when rejected, like the real one. */
    private class FakeStore(ids: List<String>) : OutboxStore {
        val waiting = ids.toMutableList()
        val rejected = LinkedHashMap<String, String>()
        val askedForUsers = ArrayList<String>()

        override fun pendingPayloads(user: String, limit: Int): List<String> {
            askedForUsers.add(user)
            return waiting.take(limit).map { """{"clientEventId":"$it"}""" }
        }

        override fun deleteAcknowledged(ids: List<String>) {
            waiting.removeAll(ids.toSet())
        }

        override fun markRejected(clientEventId: String, reason: String) {
            rejected[clientEventId] = reason
            waiting.remove(clientEventId)
        }
    }

    private class FakeUploader(val answer: (List<String>) -> UploadReply) : RecordUploader {
        val batches = ArrayList<List<String>>()
        override fun upload(baseUrl: String, token: String, payloads: List<String>): UploadReply {
            batches.add(payloads)
            return answer(payloads)
        }
    }

    private fun ackAll(): (List<String>) -> UploadReply =
        { batch -> UploadReply.Accepted(batch.map { idOf(it) }, emptyList()) }

    @Test
    fun everyAcknowledgedRecordLeavesThePhone() {
        val store = FakeStore(listOf("A", "B", "C"))
        val result = OutboxSyncer(store, FakeUploader(ackAll())).send("u", "t", "sup1")
        assertTrue(store.waiting.isEmpty())
        assertEquals(SyncResult(3, 0, false, ""), result)
    }

    @Test
    fun recordsGoOutOldestFirstInBatchesOfTheGivenSize() {
        val store = FakeStore(listOf("A", "B", "C", "D", "E"))
        val uploader = FakeUploader(ackAll())
        val result = OutboxSyncer(store, uploader, batchSize = 2).send("u", "t", "sup1")
        assertEquals(listOf(listOf("A", "B"), listOf("C", "D"), listOf("E")), uploader.batches.map { b -> b.map { idOf(it) } })
        assertEquals(5, result.sent)
        assertTrue(store.waiting.isEmpty())
    }

    @Test
    fun aRefusedRecordIsKeptWithTheReasonAndStopsBeingPending() {
        val store = FakeStore(listOf("A", "B"))
        val uploader = FakeUploader {
            UploadReply.Accepted(listOf("A"), listOf(Rejection("B", "UNKNOWN_ANIMAL", "No animal is registered for tag 'X'.")))
        }
        val result = OutboxSyncer(store, uploader).send("u", "t", "sup1")
        assertEquals(1, result.sent)
        assertEquals(1, result.refused)
        assertTrue(store.rejected.getValue("B").startsWith("No animal is registered for tag 'X'."))
        assertTrue(store.rejected.getValue("B").contains("Register the animal"))
        assertTrue(store.waiting.isEmpty())
    }

    @Test
    fun aRefusalWithoutAnIdIsCountedButCannotKeepTheLoopGoing() {
        val store = FakeStore(listOf("A"))
        val uploader = FakeUploader { UploadReply.Accepted(emptyList(), listOf(Rejection("", "MISSING_CLIENT_EVENT_ID", "no id"))) }
        val result = OutboxSyncer(store, uploader).send("u", "t", "sup1")
        assertEquals(1, uploader.batches.size)
        assertEquals(1, result.refused)
        assertTrue(store.rejected.isEmpty())
        assertEquals(listOf("A"), store.waiting)
    }

    @Test
    fun anEndedSessionStopsAtOnceAndKeepsWhatIsLeft() {
        val store = FakeStore(listOf("A", "B", "C"))
        var calls = 0
        val uploader = FakeUploader { batch -> if (++calls == 1) UploadReply.Accepted(batch.map { idOf(it) }, emptyList()) else UploadReply.SessionEnded }
        val result = OutboxSyncer(store, uploader, batchSize = 1).send("u", "t", "sup1")
        assertTrue(result.sessionEnded)
        assertEquals(1, result.sent)
        assertEquals(listOf("B", "C"), store.waiting)
    }

    @Test
    fun aServerErrorKeepsEveryRecordAndSaysWhy() {
        val store = FakeStore(listOf("A", "B"))
        val result = OutboxSyncer(store, FakeUploader { UploadReply.ServerError(500) }).send("u", "t", "sup1")
        assertFalse(result.sessionEnded)
        assertEquals(0, result.sent)
        assertTrue(result.note.contains("HTTP 500"))
        assertEquals(listOf("A", "B"), store.waiting)
    }

    @Test
    fun aLostConnectionKeepsEveryRecordAndRemembersWhatWasAlreadySent() {
        val store = FakeStore(listOf("A", "B"))
        var calls = 0
        val uploader = FakeUploader { batch ->
            if (++calls == 1) UploadReply.Accepted(batch.map { idOf(it) }, emptyList()) else throw IOException("no route to host")
        }
        val result = OutboxSyncer(store, uploader, batchSize = 1).send("u", "t", "sup1")
        assertEquals(1, result.sent)
        assertTrue(result.note.startsWith("No connection to the server"))
        assertEquals(listOf("B"), store.waiting)
    }

    @Test
    fun anAnswerThatSettlesNothingEndsTheRoundInsteadOfAskingAgain() {
        val store = FakeStore(listOf("A"))
        val uploader = FakeUploader { UploadReply.Accepted(emptyList(), emptyList()) }
        val result = OutboxSyncer(store, uploader).send("u", "t", "sup1")
        assertEquals(1, uploader.batches.size)
        assertEquals(0, result.sent)
        assertEquals(listOf("A"), store.waiting)
    }

    @Test
    fun nothingWaitingMeansNoCallToTheServer() {
        val uploader = FakeUploader(ackAll())
        val result = OutboxSyncer(FakeStore(emptyList()), uploader).send("u", "t", "sup1")
        assertTrue(uploader.batches.isEmpty())
        assertEquals(SyncResult(0, 0, false, ""), result)
    }

    @Test
    fun onlyTheSignedInUsersRecordsAreAskedFor() {
        val store = FakeStore(listOf("A"))
        OutboxSyncer(store, FakeUploader(ackAll())).send("u", "t", "supervisor_a")
        assertEquals(setOf("supervisor_a"), store.askedForUsers.toSet())
    }
}
