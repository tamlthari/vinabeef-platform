package com.vinabeef.field

/** One record the server refused, with the reason code and the message it gave. */
data class Rejection(val clientEventId: String, val code: String, val message: String)

/** What the server said to one upload. */
sealed interface UploadReply {
    /** The server read the batch: [acknowledged] are stored (a duplicate counts), [rejected] are refused for a reason. */
    data class Accepted(val acknowledged: List<String>, val rejected: List<Rejection>) : UploadReply

    /** The sign-in token is no longer accepted (HTTP 401 or 403). */
    object SessionEnded : UploadReply

    /** Any other answer that is not a success. */
    data class ServerError(val httpCode: Int) : UploadReply
}

/** The part of the server the syncer talks to. A failed connection is thrown as an exception. */
interface RecordUploader {
    fun upload(baseUrl: String, token: String, payloads: List<String>): UploadReply
}

/** The part of the phone's database the syncer uses. */
interface OutboxStore {
    /** The JSON of the records waiting to be sent for [user], oldest first, at most [limit]. */
    fun pendingPayloads(user: String, limit: Int): List<String>

    /** Removes records the server has stored. */
    fun deleteAcknowledged(ids: List<String>)

    /** Keeps a record the server refused, with the reason, so the supervisor can see it. */
    fun markRejected(clientEventId: String, reason: String)
}

/** What one round of sending did. [note] is empty when nothing needs saying. */
data class SyncResult(val sent: Int, val refused: Int, val sessionEnded: Boolean, val note: String)

/**
 * Sends what is waiting, oldest first. A record leaves the phone only when the server lists its id as acknowledged;
 * one the server refuses is kept as rejected with the reason; a failed request changes nothing, so everything is
 * simply tried again later. It knows neither Android nor HTTP: the database and the server come in through the two
 * small interfaces, so it can be tested on a laptop with fakes.
 */
class OutboxSyncer(
    private val store: OutboxStore,
    private val uploader: RecordUploader,
    private val batchSize: Int = 100
) {
    fun send(baseUrl: String, token: String, user: String): SyncResult {
        var sent = 0
        var refused = 0
        try {
            while (true) {
                val batch = store.pendingPayloads(user, batchSize)
                if (batch.isEmpty()) break
                when (val reply = uploader.upload(baseUrl, token, batch)) {
                    is UploadReply.SessionEnded -> return SyncResult(sent, refused, true, "")
                    is UploadReply.ServerError -> return SyncResult(
                        sent, refused, false,
                        "The server answered HTTP ${reply.httpCode}. The records stay on the phone and are tried again."
                    )
                    is UploadReply.Accepted -> {
                        store.deleteAcknowledged(reply.acknowledged)
                        var marked = 0
                        for (r in reply.rejected) {
                            if (r.clientEventId.isNotEmpty()) {
                                store.markRejected(r.clientEventId, FieldRules.friendlyReason(r.code, r.message))
                                marked++
                            }
                        }
                        sent += reply.acknowledged.size
                        refused += reply.rejected.size
                        // an answer that settles no record here would be asked again for ever
                        if (reply.acknowledged.size + marked == 0) break
                    }
                }
            }
        } catch (e: Exception) {
            return SyncResult(
                sent, refused, false,
                "No connection to the server. The records stay on the phone and are sent when it returns."
            )
        }
        return SyncResult(sent, refused, false, "")
    }
}
