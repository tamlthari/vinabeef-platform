package com.vinabeef.field

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The rules of recording a vaccination or health event in the field. Plain Kotlin with no Android classes,
 * so it can be unit tested on a laptop. The server applies the same checks and rejects what does not pass.
 */

/** A vaccination, treatment or check the farm has defined (downloaded with the barn bundle). */
data class Protocol(
    val protocolId: String,
    val recordType: String,
    val title: String,
    val resultingStatus: String,
    val defaultDoseMl: Double?,
    val route: String?,
    val intervalDays: Int?
)

/** What the supervisor entered on the form. */
data class RecordForm(
    val recordType: String,
    val healthStatus: String,
    val title: String,
    val batch: String,
    val doseText: String,
    val route: String,
    val notes: String
)

object FieldRules {
    /** "" means the route was not recorded. */
    val ROUTES = listOf("", "SC", "IM", "ORAL", "TOPICAL", "OTHER")
    const val MAX_DOSE_ML = 50.0
    const val DUPLICATE_WINDOW_HOURS = 24L

    /** Accepts "2", "2.5" and "2,5". Returns null for empty or unreadable text. */
    fun parseDose(text: String): Double? {
        val t = text.trim().replace(',', '.')
        return if (t.isEmpty()) null else t.toDoubleOrNull()
    }

    /** Returns null when the form can be saved, otherwise a message for the supervisor. */
    fun validate(form: RecordForm): String? {
        if (form.title.isBlank()) return "Choose the vaccine or write the diagnosis."
        if (form.recordType == "VACCINATION" && form.batch.isBlank()) {
            return "Enter the batch / lot number from the vial."
        }
        if (form.doseText.isNotBlank()) {
            val dose = parseDose(form.doseText) ?: return "The dose must be a number, for example 2 or 2.5."
            if (dose <= 0.0 || dose > MAX_DOSE_ML) {
                return "The dose must be above 0 and at most $MAX_DOSE_ML ml."
            }
        }
        return null
    }

    /** True when the same vaccine was already recorded for this animal very recently (a double scan or a second person). */
    fun withinDuplicateWindow(last: Instant?, now: Instant): Boolean =
        last != null && Duration.between(last, now).abs() < Duration.ofHours(DUPLICATE_WINDOW_HOURS)

    /** yyyy-MM-dd, or null when the protocol has no booster interval. */
    fun nextDueDate(now: Instant, intervalDays: Int?, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (intervalDays == null || intervalDays <= 0) return null
        return now.atZone(zone).toLocalDate().plusDays(intervalDays.toLong()).toString()
    }

    /** One line for the animal card: when this animal was last given this vaccine. */
    fun historyLine(title: String, last: Instant?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        if (last == null) return "$title: no earlier dose on record for this animal."
        val days = Duration.between(last, now).toDays()
        val date = last.atZone(zone).toLocalDate()
        val ago = when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
        return "$title: last given $ago ($date)."
    }

    /** The JSON fields of one record, in the shape /mobile/health-sync reads. Nulls are left out by the caller. */
    fun buildPayload(
        clientEventId: String,
        animalId: String,
        tagEpc: String,
        form: RecordForm,
        protocolId: String,
        nextDueDate: String?,
        scanMethod: String,
        scannedAt: String?,
        recordedAt: String,
        deviceId: String,
        appVersion: String,
        recordedBy: String? = null
    ): Map<String, Any?> {
        val batch = form.batch.trim()
        val dose = parseDose(form.doseText)
        val map = LinkedHashMap<String, Any?>()
        map["clientEventId"] = clientEventId
        map["animalId"] = animalId
        map["tagEpc"] = tagEpc
        map["recordType"] = form.recordType
        map["healthStatus"] = form.healthStatus
        map["title"] = form.title.trim()
        map["medication"] = batch              // older servers read the batch from here
        map["batchNumber"] = batch
        map["doseMl"] = dose
        map["route"] = form.route.ifBlank { null }
        map["protocolId"] = protocolId.ifBlank { null }
        map["nextDueDate"] = nextDueDate
        map["notes"] = form.notes.trim()
        map["scanMethod"] = scanMethod
        map["scannedAt"] = scannedAt
        map["recordedAt"] = recordedAt
        map["deviceId"] = deviceId
        map["appVersion"] = appVersion
        map["recordedBy"] = recordedBy?.trim()?.ifBlank { null }   // who was signed in on the phone; the server checks it against the account
        return map
    }

    /** Text for a record the server did not accept. */
    fun friendlyReason(code: String, serverMessage: String): String = when (code) {
        "UNKNOWN_ANIMAL" -> "$serverMessage Register the animal and its tag in the web app, then sync again."
        "MISSING_BATCH", "INVALID_DOSE", "INVALID_RECORD" -> "$serverMessage Discard this record and enter it again."
        "OUT_OF_SCOPE" -> "$serverMessage Ask a farm manager if this is the wrong barn."
        else -> serverMessage
    }
}

object TagCodes {
    private val HEX = Regex("^[0-9A-F]+$")

    /** Upper case without spaces or colons ("04:4e:66" and "044E66" are the same tag). */
    fun normalise(raw: String): String = raw.trim().replace(" ", "").replace(":", "").uppercase()

    /** A long hexadecimal code is a tag (UID or EPC); anything else typed is taken as an animal id. */
    fun looksLikeTag(code: String): Boolean {
        val c = normalise(code)
        return c.length >= 8 && HEX.matches(c)
    }

    /**
     * Codes to try, in order. Some NFC readers report the card UID with its bytes reversed, so the reversed
     * form is tried second for hexadecimal codes of at least four bytes.
     */
    fun candidates(code: String): List<String> {
        val c = normalise(code)
        if (c.length >= 8 && c.length % 2 == 0 && HEX.matches(c)) {
            val reversed = c.chunked(2).reversed().joinToString("")
            if (reversed != c) return listOf(c, reversed)
        }
        return listOf(c)
    }
}

/** A held trigger makes a wand report the same tag again and again: the same code within the window is one scan. */
class ScanDebouncer(private val windowMs: Long = 2000L) {
    private var lastCode: String = ""
    private var lastAtMs: Long = 0L

    fun accept(code: String, nowMs: Long): Boolean {
        val repeat = code == lastCode && nowMs - lastAtMs < windowMs
        lastCode = code
        lastAtMs = nowMs
        return !repeat
    }
}
