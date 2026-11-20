package com.vinabeef.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class FieldRulesTest {

    private fun form(
        type: String = "VACCINATION",
        title: String = "Foot-and-Mouth Disease (FMD)",
        batch: String = "LOT-7781",
        dose: String = "2",
        route: String = "SC"
    ) = RecordForm(type, "HEALTHY", title, batch, dose, route, "")

    @Test
    fun aCompleteVaccinationIsValid() {
        assertNull(FieldRules.validate(form()))
    }

    @Test
    fun aVaccinationNeedsTheBatchOfTheVial() {
        assertNotNull(FieldRules.validate(form(batch = "  ")))
    }

    @Test
    fun otherRecordTypesDoNotNeedABatch() {
        assertNull(FieldRules.validate(form(type = "HEALTH_CHECK", title = "Routine Pen Clinical Check", batch = "")))
    }

    @Test
    fun theVaccineOrDiagnosisIsRequired() {
        assertNotNull(FieldRules.validate(form(title = "")))
    }

    @Test
    fun theDoseAcceptsACommaAndRejectsNonsense() {
        assertEquals(2.5, FieldRules.parseDose("2,5")!!, 0.0)
        assertNull(FieldRules.parseDose("two"))
        assertNotNull(FieldRules.validate(form(dose = "two")))
        assertNotNull(FieldRules.validate(form(dose = "0")))
        assertNotNull(FieldRules.validate(form(dose = "-1")))
        assertNotNull(FieldRules.validate(form(dose = "51")))
        assertNull(FieldRules.validate(form(dose = "")))
        assertNull(FieldRules.validate(form(dose = "50")))
    }

    @Test
    fun aRepeatWithin24HoursIsADuplicateAndALaterOneIsNot() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        assertTrue(FieldRules.withinDuplicateWindow(Instant.parse("2026-11-20T08:00:00Z"), now))
        assertTrue(FieldRules.withinDuplicateWindow(Instant.parse("2026-11-19T10:00:01Z"), now))
        assertFalse(FieldRules.withinDuplicateWindow(Instant.parse("2026-11-19T09:59:59Z"), now))
        assertFalse(FieldRules.withinDuplicateWindow(null, now))
    }

    @Test
    fun theNextDueDateIsOnlySetWhenTheProtocolHasAnInterval() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        assertEquals("2027-05-19", FieldRules.nextDueDate(now, 180, ZoneOffset.UTC))
        assertNull(FieldRules.nextDueDate(now, null, ZoneOffset.UTC))
        assertNull(FieldRules.nextDueDate(now, 0, ZoneOffset.UTC))
    }

    @Test
    fun theHistoryLineSaysWhenTheVaccineWasLastGiven() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        assertEquals("FMD: no earlier dose on record for this animal.", FieldRules.historyLine("FMD", null, now, ZoneOffset.UTC))
        assertEquals("FMD: last given 3 days ago (2026-11-17).",
            FieldRules.historyLine("FMD", Instant.parse("2026-11-17T09:00:00Z"), now, ZoneOffset.UTC))
        assertEquals("FMD: last given today (2026-11-20).",
            FieldRules.historyLine("FMD", Instant.parse("2026-11-20T01:00:00Z"), now, ZoneOffset.UTC))
        assertEquals("FMD: last given yesterday (2026-11-19).",
            FieldRules.historyLine("FMD", Instant.parse("2026-11-19T09:00:00Z"), now, ZoneOffset.UTC))
    }

    @Test
    fun thePayloadHasTheFieldsTheServerReads() {
        val p = FieldRules.buildPayload(
            "MOB-1", "CI 356278", "044E6602AD1490", form(), "VAC-FMD", "2027-05-19", "BLE_RFID",
            "2026-11-20T09:59:50Z", "2026-11-20T10:00:00Z", "dev-1", "1.1"
        )
        assertEquals("MOB-1", p["clientEventId"])
        assertEquals("LOT-7781", p["batchNumber"])
        assertEquals("LOT-7781", p["medication"])
        assertEquals(2.0, p["doseMl"])
        assertEquals("SC", p["route"])
        assertEquals("VAC-FMD", p["protocolId"])
        assertEquals("BLE_RFID", p["scanMethod"])
    }

    @Test
    fun anEmptyRouteAndProtocolAreSentAsNull() {
        val p = FieldRules.buildPayload(
            "MOB-2", "", "AA", form(route = "", dose = ""), "", null, "MANUAL", null, "2026-11-20T10:00:00Z", "d", "1.1"
        )
        assertNull(p["route"])
        assertNull(p["protocolId"])
        assertNull(p["doseMl"])
    }

    @Test
    fun aTagIsTheSameWithColonsSpacesAndLowerCase() {
        assertEquals("044E6602AD1490", TagCodes.normalise(" 04:4e:66:02:ad:14:90 "))
    }

    @Test
    fun aHexUidIsAlsoTriedWithItsBytesReversed() {
        assertEquals(listOf("044E6602AD1490", "9014AD02664E04"), TagCodes.candidates("044e6602ad1490"))
        assertEquals(listOf("COW-0002"), TagCodes.candidates("cow-0002"))
        assertEquals(listOf("AABBCC"), TagCodes.candidates("aabbcc"))
    }

    @Test
    fun aTypedCodeIsATagOnlyWhenItIsLongHexadecimal() {
        assertTrue(TagCodes.looksLikeTag("044E6602AD1490"))
        assertTrue(TagCodes.looksLikeTag("04:4e:66:02:ad:14:90"))
        assertFalse(TagCodes.looksLikeTag("CI 356278"))
        assertFalse(TagCodes.looksLikeTag("TEST-0002"))
        assertFalse(TagCodes.looksLikeTag("ABC"))
    }

    @Test
    fun aHeldTriggerIsOneScan() {
        val d = ScanDebouncer(2000)
        assertTrue(d.accept("AA", 0))
        assertFalse(d.accept("AA", 500))
        assertFalse(d.accept("AA", 1900))
        assertFalse(d.accept("AA", 3500))   // still being read: the window moves with each repeat
        assertTrue(d.accept("BB", 3600))
        assertTrue(d.accept("AA", 3700))
        assertTrue(d.accept("AA", 9000))
    }

    @Test
    fun theServerReasonIsExplainedToTheSupervisor() {
        assertTrue(FieldRules.friendlyReason("UNKNOWN_ANIMAL", "No animal.").contains("web app"))
        assertEquals("Odd.", FieldRules.friendlyReason("SOMETHING_ELSE", "Odd."))
    }
}
