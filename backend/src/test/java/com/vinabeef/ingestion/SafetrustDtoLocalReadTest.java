package com.vinabeef.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the event DTO says about a read that a barn gateway took from its own USB reader. */
class SafetrustEventDtoLocalReadTest {

    private static SafetrustEventDto local(String credential, String card) {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setEdgeSource("LOCAL_USB");
        d.setEdgeDevice("USB-C0F8");
        d.setCredentialIdentifier(credential);
        d.setCardUuid(card);
        d.setReaderName("DrC0F8");
        return d;
    }

    @Test
    void aReadFromTheGatewaysUsbReaderIsMarkedLocalAndEverythingElseIsSafetrust() {
        assertTrue(local("38", null).isLocalUsb());
        assertEquals("LOCAL_USB", local("38", null).getReadSource());

        SafetrustEventDto safetrust = new SafetrustEventDto();
        safetrust.setCardUuid("044e6602ad1490");
        assertFalse(safetrust.isLocalUsb());
        assertEquals("SAFETRUST", safetrust.getReadSource());

        SafetrustEventDto odd = new SafetrustEventDto();
        odd.setEdgeSource("something else");
        assertFalse(odd.isLocalUsb(), "only the exact value LOCAL_USB counts");
    }

    @Test
    void aUsbReadWithoutACardIsUnmappedAndWithACardIsAnOrdinaryCardRead() {
        assertEquals("LOCAL_UNMAPPED", local("38", null).getEventClass());
        assertEquals("CARD_READ", local("38", "044e6602ad1490").getEventClass());
        assertEquals("044E6602AD1490", local("38", "044e6602ad1490").getCardUuid());
    }

    @Test
    void aUsbReadWithNeitherCardNorNumberIsNotAnUnmappedRead() {
        assertEquals("OTHER", local(null, null).getEventClass());
        assertEquals("OTHER", local("  ", null).getEventClass());
    }

    @Test
    void theCredentialNumberIsTrimmedAndABlankOneIsNull() {
        assertEquals("1005", local(" 1005 ", null).getCredentialNumber());
        assertNull(local("", null).getCredentialNumber());
        assertNull(local(null, null).getCredentialNumber());
    }

    @Test
    void aSafetrustReadWithoutACardIsNeverUnmapped() {
        SafetrustEventDto d = new SafetrustEventDto();
        d.setCredentialIdentifier("38");
        assertEquals("OTHER", d.getEventClass());
    }
}
