package com.vinabeef.field

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class CachedAnimal(
    val animalId: String,
    val barnId: String,
    val barnCode: String,
    val breed: String,
    val weightKg: Double,
    val healthStatus: String,
    val tagEpc: String
)

class OfflineFieldDbHelper(context: Context) :
    SQLiteOpenHelper(context, "vinabeef_field_offline.db", null, 1) {

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
                created_at TEXT NOT NULL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cached_cattle")
        db.execSQL("DROP TABLE IF EXISTS health_outbox")
        onCreate(db)
    }

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
                    put("tag_epc", obj.optString("tag_epc").uppercase())
                }
                db.insertWithOnConflict("cached_cattle", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun findAnimalByTagOrId(query: String): CachedAnimal? {
        val clean = query.trim().uppercase()
        if (clean.isEmpty()) return null

        val cursor = readableDatabase.rawQuery(
            """
            SELECT animal_id, barn_id, barn_code, breed, weight_kg, health_status, tag_epc
            FROM cached_cattle
            WHERE UPPER(tag_epc) = ? OR UPPER(animal_id) = ?
            LIMIT 1
            """,
            arrayOf(clean, clean)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return CachedAnimal(
                    animalId = it.getString(0),
                    barnId = it.getString(1),
                    barnCode = it.getString(2),
                    breed = it.getString(3),
                    weightKg = it.getDouble(4),
                    healthStatus = it.getString(5),
                    tagEpc = it.getString(6)
                )
            }
        }
        return null
    }

    fun getRandomCachedAnimal(): CachedAnimal? {
        val cursor = readableDatabase.rawQuery(
            "SELECT animal_id, barn_id, barn_code, breed, weight_kg, health_status, tag_epc FROM cached_cattle ORDER BY RANDOM() LIMIT 1",
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return CachedAnimal(
                    animalId = it.getString(0),
                    barnId = it.getString(1),
                    barnCode = it.getString(2),
                    breed = it.getString(3),
                    weightKg = it.getDouble(4),
                    healthStatus = it.getString(5),
                    tagEpc = it.getString(6)
                )
            }
        }
        return null
    }

    fun getCachedCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM cached_cattle", null)
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun enqueueHealthRecord(clientEventId: String, payloadJson: JSONObject, createdAt: String) {
        val cv = ContentValues().apply {
            put("client_event_id", clientEventId)
            put("payload_json", payloadJson.toString())
            put("created_at", createdAt)
        }
        writableDatabase.insertWithOnConflict("health_outbox", null, cv, SQLiteDatabase.CONFLICT_REPLACE)

        // Immediately update local cached health_status so subsequent scans reflect it offline
        val animalId = payloadJson.optString("animalId")
        val newStatus = payloadJson.optString("healthStatus")
        if (animalId.isNotEmpty() && newStatus.isNotEmpty()) {
            val statusCv = ContentValues().apply { put("health_status", newStatus) }
            writableDatabase.update("cached_cattle", statusCv, "animal_id = ?", arrayOf(animalId))
        }
    }

    fun getPendingOutbox(): JSONArray {
        val arr = JSONArray()
        val cursor = readableDatabase.rawQuery("SELECT payload_json FROM health_outbox ORDER BY created_at ASC", null)
        cursor.use {
            while (it.moveToNext()) {
                arr.put(JSONObject(it.getString(0)))
            }
        }
        return arr
    }

    fun getPendingCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM health_outbox", null)
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun deleteAcknowledgedIds(ids: JSONArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until ids.length()) {
                db.delete("health_outbox", "client_event_id = ?", arrayOf(ids.getString(i)))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}