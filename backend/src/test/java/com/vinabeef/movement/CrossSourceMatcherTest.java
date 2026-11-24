package com.vinabeef.movement;

import com.vinabeef.movement.port.ReadTwinStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order of operations the matcher runs and what it does with each answer. The SQL is tested with the adapter. */
class CrossSourceMatcherTest {

    private static final long T = 1_795_164_000_000L;

    private static final class Twins implements ReadTwinStore {
        final List<String> calls = new ArrayList<>();
        List<String> found = List.of();
        boolean primaryAccepted = true;
        long searchedWindow;
        String searchedRoute;

        @Override
        public void lock(String card, String readerName) {
            calls.add("LOCK");
        }

        @Override
        public List<String> findTwin(String eventId, String readerName, String card, String readSource, long hardwareMs, long windowMs) {
            calls.add("FIND_TWIN");
            searchedWindow = windowMs;
            searchedRoute = readSource;
            return found;
        }

        @Override
        public boolean markPrimary(String first, String eventId) {
            calls.add("MARK_PRIMARY:" + first + ">" + eventId);
            return primaryAccepted;
        }

        @Override
        public void markDuplicate(String eventId, String first) {
            calls.add("MARK_DUPLICATE:" + eventId + ">" + first);
        }
    }

    private static CrossSourceMatcher matcher(Twins twins, boolean enabled, long window) {
        return new CrossSourceMatcher(twins, enabled, window);
    }

    @Test
    void aTapSeenTwiceMarksTheSecondReadAsTheCopyOfTheFirst() {
        Twins twins = new Twins();
        twins.found = List.of("FIRST");
        Optional<String> first = matcher(twins, true, 10_000).claimFirstCopy("SECOND", "DrC0F8", "044E6602AD1490", "SAFETRUST", T);
        assertEquals(Optional.of("FIRST"), first);
        assertEquals(List.of("LOCK", "FIND_TWIN", "MARK_PRIMARY:FIRST>SECOND", "MARK_DUPLICATE:SECOND>FIRST"), twins.calls);
    }

    @Test
    void aReadWithNoCopyIsLeftAloneAndLooksInTheWindowAroundItsTime() {
        Twins twins = new Twins();
        Optional<String> first = matcher(twins, true, 7_000).claimFirstCopy("E1", "DrC0F8", "044E6602AD1490", "LOCAL_USB", T);
        assertFalse(first.isPresent());
        assertEquals(List.of("LOCK", "FIND_TWIN"), twins.calls);
        assertEquals(7_000L, twins.searchedWindow);
        assertEquals("LOCAL_USB", twins.searchedRoute);          // the candidate must come from the other route
    }

    @Test
    void ifTheFirstCopyWasPairedMeanwhileTheReadIsNotMarkedAsACopy() {
        Twins twins = new Twins();
        twins.found = List.of("FIRST");
        twins.primaryAccepted = false;
        assertFalse(matcher(twins, true, 10_000).claimFirstCopy("SECOND", "DrC0F8", "044E6602AD1490", "SAFETRUST", T).isPresent());
        assertEquals(List.of("LOCK", "FIND_TWIN", "MARK_PRIMARY:FIRST>SECOND"), twins.calls);
    }

    @Test
    void switchedOffOrIncompleteReadsTouchNothingAtAll() {
        Twins off = new Twins();
        off.found = List.of("FIRST");
        assertFalse(matcher(off, false, 10_000).claimFirstCopy("E", "DrC0F8", "CARD", "SAFETRUST", T).isPresent());
        assertTrue(off.calls.isEmpty());

        Twins incomplete = new Twins();
        assertFalse(matcher(incomplete, true, 10_000).claimFirstCopy("E", "DrC0F8", null, "SAFETRUST", T).isPresent());
        assertFalse(matcher(incomplete, true, 10_000).claimFirstCopy("E", null, "CARD", "SAFETRUST", T).isPresent());
        assertTrue(incomplete.calls.isEmpty());
    }

    @Test
    void aNegativeWindowIsTreatedAsZero() {
        assertEquals(0L, matcher(new Twins(), true, -5).windowMs());
    }
}
