package com.vinabeef.movement.adapter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcCredentialLookupTest {

    @Test
    void theHistoryIsAskedWithTheNumberAndHowManyReadsToLookAt() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("044E6602AD1490");
        List<String> cards = new JdbcCredentialLookup(jdbc).cardsByHistory("38");
        assertEquals(List.of("044E6602AD1490"), cards);
        assertEquals(List.<Object>of("38", LocalReadSql.HISTORY_READS), List.of(jdbc.lastArgs.get("HISTORY")));
    }

    @Test
    void theRegisterIsAskedWithTheNumber() {
        ScriptedJdbc jdbc = new ScriptedJdbc().answerList("E280116060000204");
        assertEquals(List.of("E280116060000204"), new JdbcCredentialLookup(jdbc).cardsByRegister("1005"));
        assertEquals(List.<Object>of("1005"), List.of(jdbc.lastArgs.get("REGISTER")));
    }
}
