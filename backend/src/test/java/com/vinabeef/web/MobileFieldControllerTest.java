package com.vinabeef.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobileFieldControllerTest {

    @Test
    void aRecordIsAcceptedFromTheAccountThatSavedIt() {
        assertTrue(MobileFieldController.fromRightAccount("supervisor_barn1", "supervisor_barn1"));
    }

    @Test
    void upperCaseAndSpacesInTheNameDoNotMatter() {
        assertTrue(MobileFieldController.fromRightAccount(" Supervisor_Barn1 ", "supervisor_barn1"));
    }

    @Test
    void aRecordIsRefusedFromAnotherAccount() {
        assertFalse(MobileFieldController.fromRightAccount("supervisor_barn1", "supervisor_barn2"));
    }

    @Test
    void aRecordWithoutAnAuthorIsAcceptedFromAnyAccount() {
        assertTrue(MobileFieldController.fromRightAccount(null, "supervisor_barn2"));
        assertTrue(MobileFieldController.fromRightAccount("", "supervisor_barn2"));
        assertTrue(MobileFieldController.fromRightAccount("   ", "supervisor_barn2"));
    }
}
