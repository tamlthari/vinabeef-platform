package com.vinabeef.movement.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAnimalRegistryAndCountersTest {

    @Test
    void anAnimalIsLiveWhenItExistsAndIsNotArchived() {
        ScriptedJdbc yes = new ScriptedJdbc().answerCount(1);
        assertTrue(new JdbcAnimalRegistry(null, yes).isLiveAnimal("A-9"));
        assertEquals("A-9", yes.lastArgs.get("LIVE_ANIMAL")[0]);
        assertFalse(new JdbcAnimalRegistry(null, new ScriptedJdbc().answerCount(0)).isLiveAnimal("A-9"));
    }

    @Test
    void theAssignedBarnIsTheFirstRowOrNothing() {
        assertEquals(Optional.of("BARN-2"), new JdbcAnimalRegistry(null, new ScriptedJdbc().answerList("BARN-2")).assignedBarn("A-1"));
        assertEquals(Optional.empty(), new JdbcAnimalRegistry(null, new ScriptedJdbc()).assignedBarn("A-1"));
    }

    @Test
    void theDailyCountersMoveTheRightColumnOfTheRightBarn() {
        ScriptedJdbc jdbc = new ScriptedJdbc();
        JdbcBarnCounters counters = new JdbcBarnCounters(null, jdbc);
        counters.countIn("BARN-1");
        counters.countOut("BARN-2");
        assertEquals(List.of("DAILY_IN", "DAILY_OUT"), jdbc.calls);
        assertEquals("BARN-1", jdbc.lastArgs.get("DAILY_IN")[0]);
        assertEquals("BARN-2", jdbc.lastArgs.get("DAILY_OUT")[0]);
    }
}
