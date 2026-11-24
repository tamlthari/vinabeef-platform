package com.vinabeef.movement.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each alert statement has its own argument order; a swap would put one value in another's place and still run. */
class JdbcAlertSinkTest {

    private static void assertAlertId(Object id) {
        assertTrue(String.valueOf(id).matches("ALT-[0-9A-F]{8}"), String.valueOf(id));
    }

    @Test
    void anUnexpectedTagCarriesBarnTagUserReaderGateAndEventKey() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcAlertSink(jdbc).raiseUnexpectedTag("BARN-1", "TAG-X", "UNKNOWN", "DrC0F8", "G-1", 42L);
        Object[] a = jdbc.lastArgs.get("ALERT_UNEXPECTED_TAG");
        assertAlertId(a[0]);
        assertEquals(java.util.List.<Object>of("BARN-1", "TAG-X", "UNKNOWN", "DrC0F8", "G-1", 42L), java.util.List.of(a).subList(1, 7));
    }

    @Test
    void anUnmappedCredentialCarriesBarnNumberReaderGateAndEventKey() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcAlertSink(jdbc).raiseUnmappedCredential("BARN-1", "999", "DrC0F8", "G-1", -3L);
        Object[] a = jdbc.lastArgs.get("ALERT_UNMAPPED");
        assertAlertId(a[0]);
        assertEquals(java.util.List.<Object>of("BARN-1", "999", "DrC0F8", "G-1", -3L), java.util.List.of(a).subList(1, 6));
    }

    @Test
    void aWrongBarnIsFiledUnderTheBarnWhereTheAnimalWasDetected() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcAlertSink(jdbc).raiseWrongBarn("A-1", "BARN-2", "BARN-1", "DrC0F8", "IN");
        Object[] a = jdbc.lastArgs.get("ALERT_WRONG_BARN");
        assertAlertId(a[0]);
        // alert_id, animal_id, barn_id (= detected), then the evidence: assigned, detected, reader, direction
        assertEquals(java.util.List.<Object>of("A-1", "BARN-1", "BARN-2", "BARN-1", "DrC0F8", "IN"), java.util.List.of(a).subList(1, 7));
    }

    @Test
    void anUnregisteredReaderCarriesReaderTagAndEventKey() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcAlertSink(jdbc).raiseUnregisteredReader("GHOST-R", "TAG-1", 9L);
        Object[] a = jdbc.lastArgs.get("ALERT_UNREGISTERED");
        assertAlertId(a[0]);
        assertEquals(java.util.List.<Object>of("GHOST-R", "TAG-1", 9L), java.util.List.of(a).subList(1, 4));
    }

    @Test
    void anAnimalComingBackClosesItsOpenMissingAlertsByNoteThenAnimal() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        new JdbcAlertSink(jdbc).resolveMissingCattle("A-1", "DrC0F8");
        assertEquals(java.util.List.<Object>of("DrC0F8", "A-1"), java.util.List.of(jdbc.lastArgs.get("RESOLVE_MISSING")));
    }
}
