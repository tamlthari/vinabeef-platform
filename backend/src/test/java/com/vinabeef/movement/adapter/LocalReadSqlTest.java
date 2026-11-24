package com.vinabeef.movement.adapter;

import com.vinabeef.movement.adapter.LocalReadSql.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statements for USB reads and cross-route matching: every placeholder has exactly one argument, in the order the SQL
 * reads them. A swapped argument would still run and silently match the wrong rows, so the order is pinned here.
 */
class LocalReadSqlTest {

    private static final long T = 1_795_164_000_000L;

    private static long placeholders(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }

    private static void assertBalanced(Query q) {
        assertEquals(placeholders(q.sql()), (long) q.params().size(), q.sql());
    }

    @Test
    void everyStatementHasOneArgumentPerPlaceholder() {
        List<Query> all = List.of(
                LocalReadSql.cardByHistory("38"),
                LocalReadSql.cardByRegister("38"),
                LocalReadSql.lock("044E6602AD1490", "DrC0F8"),
                LocalReadSql.findTwin("E2", "DrC0F8", "044E6602AD1490", "LOCAL_USB", T, 10_000),
                LocalReadSql.markPrimary("E1", "E2"),
                LocalReadSql.markDuplicate("E2", "E1"),
                LocalReadSql.unmappedIds(200),
                LocalReadSql.unmappedRow("E3"),
                LocalReadSql.resolveRow("E3", "044E6602AD1490", "SAFETRUST_HISTORY"));
        for (Query q : all) {
            assertBalanced(q);
        }
    }

    @Test
    void findTwinReadsReaderCardOtherRouteThisEventTheWindowThenTheCentre() {
        Query q = LocalReadSql.findTwin("E2", "DrC0F8", "044E6602AD1490", "LOCAL_USB", T, 10_000);
        assertEquals(List.<Object>of("DrC0F8", "044E6602AD1490", "LOCAL_USB", "E2", T - 10_000, T + 10_000, T), q.params());
        assertTrue(q.sql().contains("read_source <> ?"), "only the OTHER route is a candidate");
        assertTrue(q.sql().contains("duplicate_of IS NULL AND twin_event_id IS NULL"), "a read is paired with at most one copy");
        assertTrue(q.sql().contains("event_id <> ?"), "a read is never its own copy");
        assertTrue(q.sql().contains("event_class = 'CARD_READ'"));
    }

    @Test
    void theHistoryLooksOnlyAtSafetrustReadsAndOnlyTheLatestOnes() {
        Query q = LocalReadSql.cardByHistory("38");
        assertEquals(List.<Object>of("38", LocalReadSql.HISTORY_READS), q.params());
        assertTrue(q.sql().contains("read_source = 'SAFETRUST'"), "a USB read must not teach the map");
        assertTrue(q.sql().contains("ORDER BY hardware_timestamp DESC"));
    }

    @Test
    void theLockKeyNamesTheTagAndTheReader() {
        assertEquals(List.<Object>of("xsrc|044E6602AD1490|DrC0F8"), LocalReadSql.lock("044E6602AD1490", "DrC0F8").params());
    }

    @Test
    void markingTheFirstCopyRefusesOneThatAlreadyHasACopy() {
        Query q = LocalReadSql.markPrimary("E1", "E2");
        assertEquals(List.<Object>of("E2", "E1"), q.params());     // twin_event_id = E2 WHERE event_id = E1
        assertTrue(q.sql().contains("twin_event_id IS NULL AND duplicate_of IS NULL"));
    }

    @Test
    void markingTheSecondCopyPointsAtTheFirst() {
        assertEquals(List.<Object>of("E1", "E2"), LocalReadSql.markDuplicate("E2", "E1").params());   // duplicate_of = E1 WHERE event_id = E2
    }
}
