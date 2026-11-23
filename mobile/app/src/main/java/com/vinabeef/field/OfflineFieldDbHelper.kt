package com.vinabeef.field

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class CachedAnimal(
    val animalId: String,
    val barnId: String,
    val barnCode: String,
    val breed: String,
    val weightKg: Double,
    val healthStatus: String,
    val tagEpc: String
)

/** A record the server did not accept; it stays on the phone until the supervisor retries or discards it. */
data class RejectedRecord(
    val clientEventId: String,
    val label: String,
    val title: String,
    val reason: String
)

/**
 * The records of one signed-in user: the ones they saved, plus any saved before the phone knew a user.
 * One bind argument: the username. The comparison ignores upper and lower case.
 */
private const val MINE = "(recorded_by IS NULL OR recorded_by = '' OR recorded_by = ? COLLATE NOCASE)"

class OfflineFieldDbHelper(context: Context) :
    SQLiteOpenHelper(context, "vinabeef_field_offline.db", null, 3), OutboxStore {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE cached_cattle (
                animal_id TEXT PRIMARY KEY,
                barn_id TEXT,
                barn_code TEXT,
                breed TEXT,
                weight_kg REAL,
                health_status TEXT,
                tag_epc TEXT
            )
        """)
        db.execSQL("CREATE INDEX idx_cached_tag_epc ON cached_cattle(tag_epc)")

        db.execSQL("""
            CREATE TABLE health_outbox (
                client_event_id TEXT PRIMARY KEY,
                payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                last_error TEXT,
                label TEXT,
                title TEXT,
                animal_id TEXT,
                record_type TEXT,
                recorded_at TEXT,
                recorded_by TEXT
            )
        """)
        createProtocolTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Keep the records that are still waiting to be sent: they are the only copy.
            for (column in listOf(
                "status TEXT NOT NULL DEFAULT 'PENDING'", "last_error TEXT", "label TEXT", "title TEXT",
                "animal_id TEXT", "record_type TEXT", "recorded_at TEXT"
            )) {
                db.execSQL("ALTER TABLE health_outbox ADD COLUMN $column")
            }
            createProtocolTables(db)
        }
        if (oldVersion < 3) {
            // Records already waiting have no author; they are sent by whoever signs in (as before).
            db.execSQL("ALTER TABLE health_outbox ADD COLUMN recorded_by TEXT")
        }
    }

    private fun createProtocolTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS protocols (
                protocol_id TEXT PRIMARY KEY,
                record_type TEXT NOT NULL,
                title TEXT NOT NULL,
                resulting_status TEXT NOT NULL,
                default_dose_ml REAL,
                route TEXT,
                interval_days INTEGER,
                sort_order INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vaccination_history (
                animal_id TEXT NOT NULL,
                title TEXT NOT NULL,
                last_at TEXT NOT NULL,
                PRIMARY KEY (animal_id, title)
            )
        """)
    }

    // ---------------------------------------------------------------- herd cache

    fun replaceCachedCattle(cattleArray: JSONArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("cached_cattle", null, null)
            for (i in 0 until cattleArray.length()) {
                val obj = cattleArray.getJSONObject(i)
                val cv = ContentValues().apply {
                    put("animal_id", obj.optString("animal_id"))
                    put("barn_id", obj.optString("barn_id"))
                    put("barn_code", obj.optString("barn_code"))
                    put("breed", obj.optString("breed", "Brahman Cross"))
                    put("weight_kg", obj.optDouble("weight_kg", 420.0))
                    put("health_status", obj.optString("health_status", "HEALTHY"))
                    put("tag_epc", TagCodes.normalise(obj.optString("tag_epc")))
                }
                db.insertWithOnConflict("cached_cattle", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun replaceProtocols(protocolArray: JSONArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("protocols", null, null)
            for (i in 0 until protocolArray.length()) {
                val obj = protocolArray.getJSONObject(i)
                val cv = ContentValues().apply {
                    put("protocol_id", obj.optString("protocol_id"))
                    put("record_type", obj.optString("type", "VACCINATION"))
                    put("title", obj.optString("title"))
                    put("resulting_status", obj.optString("status", "HEALTHY"))
                    if (obj.isNull("default_dose_ml")) putNull("default_dose_ml") else put("default_dose_ml", obj.optDouble("default_dose_ml"))
                    if (obj.isNull("route")) putNull("route") else put("route", obj.optString("route"))
                    if (obj.isNull("interval_days")) putNull("interval_days") else put("interval_days", obj.optInt("interval_days"))
                    put("sort_order", i)
                }
                db.insertWithOnConflict("protocols", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getProtocols(): List<Protocol> {
        val list = ArrayList<Protocol>()
        val cursor = readableDatabase.rawQuery(
            "SELECT protocol_id, record_type, title, resulting_status, default_dose_ml, route, interval_days " +
                    "FROM protocols ORDER BY sort_order ASC", null
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Protocol(
                        protocolId = it.getString(0),
                        recordType = it.getString(1),
                        title = it.getString(2),
                        resultingStatus = it.getString(3),
                        defaultDoseMl = if (it.isNull(4)) null else it.getDouble(4),
                        route = if (it.isNull(5)) null else it.getString(5),
                        intervalDays = if (it.isNull(6)) null else it.getInt(6)
                    )
                )
            }
        }
        return list
    }

    fun replaceVaccinationHistory(rows: JSONArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("vaccination_history", null, null)
            for (i in 0 until rows.length()) {
                val obj = rows.getJSONObject(i)
                val title = obj.optString("title")
                val lastAt = obj.optString("last_at")
                if (title.isEmpty() || lastAt.isEmpty()) continue
                val cv = ContentValues().apply {
                    put("animal_id", obj.optString("animal_id"))
                    put("title", normaliseTitle(title))
                    put("last_at", lastAt)
                }
                db.insertWithOnConflict("vaccination_history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * When this animal was last given this vaccine: the newest of the server history and the records that
     * were made on this phone and are not synced yet, so a second dose is noticed even without network.
     */
    fun lastVaccination(animalId: String, title: String): Instant? {
        val wanted = normaliseTitle(title)
        val times = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT last_at FROM vaccination_history WHERE animal_id = ? AND title = ?",
            arrayOf(animalId, wanted)
        ).use { while (it.moveToNext()) times.add(it.getString(0)) }
        readableDatabase.rawQuery(
            "SELECT recorded_at FROM health_outbox WHERE animal_id = ? AND record_type = 'VACCINATION' " +
                    "AND UPPER(TRIM(title)) = ?",
            arrayOf(animalId, wanted)
        ).use { while (it.moveToNext()) times.add(it.getString(0)) }
        return times.mapNotNull { parseInstantOrNull(it) }.maxOrNull()
    }

    private fun normaliseTitle(title: String) = title.trim().uppercase()

    private fun parseInstantOrNull(text: String?): Instant? =
        try {
            if (text.isNullOrEmpty()) null else Instant.parse(text)
        } catch (e: Exception) {
            null
        }

    fun findAnimalByTagOrId(query: String): CachedAnimal? {
        for (candidate in TagCodes.candidates(query)) {
            if (candidate.isEmpty()) continue
            readableDatabase.rawQuery(
                """
                SELECT animal_id, barn_id, barn_code, breed, weight_kg, health_status, tag_epc
                FROM cached_cattle
                WHERE UPPER(tag_epc) = ? OR REPLACE(UPPER(animal_id), ' ', '') = ?
                LIMIT 1
                """,
                arrayOf(candidate, candidate)
            ).use {
                if (it.moveToFirst()) return readAnimal(it)
            }
        }
        return null
    }

    fun getRandomCachedAnimal(): CachedAnimal? {
        readableDatabase.rawQuery(
            "SELECT animal_id, barn_id, barn_code, breed, weight_kg, health_status, tag_epc FROM cached_cattle ORDER BY RANDOM() LIMIT 1",
            null
        ).use {
            if (it.moveToFirst()) return readAnimal(it)
        }
        return null
    }

    private fun readAnimal(c: android.database.Cursor) = CachedAnimal(
        animalId = c.getString(0),
        barnId = c.getString(1),
        barnCode = c.getString(2),
        breed = c.getString(3),
        weightKg = c.getDouble(4),
        healthStatus = c.getString(5),
        tagEpc = c.getString(6)
    )

    fun getCachedCount(): Int = count("SELECT COUNT(*) FROM cached_cattle")

    // ---------------------------------------------------------------- outbox

    /** Stores the record on the phone. It stays here until the server acknowledges it. */
    fun enqueueHealthRecord(payloadJson: JSONObject, label: String) {
        val clientEventId = payloadJson.getString("clientEventId")
        val recordType = payloadJson.optString("recordType")
        val cv = ContentValues().apply {
            put("client_event_id", clientEventId)
            put("payload_json", payloadJson.toString())
            put("created_at", Instant.now().toString())
            put("status", "PENDING")
            put("label", label)
            put("title", payloadJson.optString("title"))
            put("animal_id", payloadJson.optString("animalId"))
            put("record_type", recordType)
            put("recorded_at", payloadJson.optString("recordedAt"))
            put("recorded_by", payloadJson.optString("recordedBy"))
        }
        writableDatabase.insertWithOnConflict("health_outbox", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        // A vaccination says what was given, not how the animal is; the server leaves the status alone too.
        val animalId = payloadJson.optString("animalId")
        val newStatus = payloadJson.optString("healthStatus")
        if (recordType != "VACCINATION" && animalId.isNotEmpty() && newStatus.isNotEmpty()) {
            val statusCv = ContentValues().apply { put("health_status", newStatus) }
            writableDatabase.update("cached_cattle", statusCv, "animal_id = ?", arrayOf(animalId))
        }
    }

    /**
     * The signed-in user's records waiting to be sent, oldest first. A record saved by someone else stays
     * on the phone until that person signs in, so it is never sent under another name.
     * Rejected records are not sent again until retried.
     */
    override fun pendingPayloads(user: String, limit: Int): List<String> {
        val payloads = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT payload_json FROM health_outbox WHERE status = 'PENDING' AND $MINE ORDER BY created_at ASC LIMIT ?",
            arrayOf(user.trim(), limit.toString())
        ).use {
            while (it.moveToNext()) payloads.add(it.getString(0))
        }
        return payloads
    }

    /** Every record waiting to be sent, whoever saved it. */
    fun getPendingCount(): Int = count("SELECT COUNT(*) FROM health_outbox WHERE status = 'PENDING'")

    /** Waiting records that belong to someone else: they go out when that person signs in. */
    fun getPendingOfOthersCount(user: String): Int =
        count("SELECT COUNT(*) FROM health_outbox WHERE status = 'PENDING' AND NOT $MINE", arrayOf(user.trim()))

    fun getRejectedCount(user: String): Int =
        count("SELECT COUNT(*) FROM health_outbox WHERE status = 'REJECTED' AND $MINE", arrayOf(user.trim()))

    fun getRejected(user: String): List<RejectedRecord> {
        val list = ArrayList<RejectedRecord>()
        readableDatabase.rawQuery(
            "SELECT client_event_id, label, title, last_error FROM health_outbox " +
                    "WHERE status = 'REJECTED' AND $MINE ORDER BY created_at ASC",
            arrayOf(user.trim())
        ).use {
            while (it.moveToNext()) {
                list.add(RejectedRecord(it.getString(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getString(3) ?: ""))
            }
        }
        return list
    }

    override fun deleteAcknowledged(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.delete("health_outbox", "client_event_id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun markRejected(clientEventId: String, reason: String) {
        val cv = ContentValues().apply {
            put("status", "REJECTED")
            put("last_error", reason)
        }
        writableDatabase.update("health_outbox", cv, "client_event_id = ?", arrayOf(clientEventId))
    }

    /** Puts rejected records back in the queue, for example after the animal was registered in the web app. */
    fun retryRejected(user: String) {
        val cv = ContentValues().apply {
            put("status", "PENDING")
            putNull("last_error")
        }
        writableDatabase.update("health_outbox", cv, "status = 'REJECTED' AND $MINE", arrayOf(user.trim()))
    }

    fun discardRejected(user: String) {
        writableDatabase.delete("health_outbox", "status = 'REJECTED' AND $MINE", arrayOf(user.trim()))
    }

    private fun count(sql: String, args: Array<String>? = null): Int =
        readableDatabase.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }
}
