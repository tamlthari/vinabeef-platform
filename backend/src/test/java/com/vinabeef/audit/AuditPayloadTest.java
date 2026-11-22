package com.vinabeef.audit;

import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPayloadTest {

    /** Stand-in for Jackson's convertValue: knows the one bean used below, throws for anything else. */
    private static final Function<Object, Map<String, Object>> TO_MAP = bean -> {
        if (bean instanceof CreateUser u) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("username", u.username);
            m.put("password", u.password);
            m.put("nested", Map.of("newPassword", "x", "role", "FARM_MANAGER"));
            return m;
        }
        throw new IllegalArgumentException("not a bean");
    };

    record CreateUser(String username, String password) {
    }

    private static final class FakeAuth implements Principal {
        @Override public String getName() { return "admin"; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(List<Object> sanitized) {
        return (Map<String, Object>) sanitized.get(0);
    }

    @Test
    void passwordIsMaskedWhetherItIsTopLevelOrNested() {
        List<Object> out = AuditPayload.sanitize(new Object[]{new CreateUser("maria", "s3cret!")}, TO_MAP);

        Map<String, Object> body = first(out);
        assertEquals("maria", body.get("username"));
        assertEquals(AuditPayload.MASK, body.get("password"));
        assertEquals(AuditPayload.MASK, ((Map<?, ?>) body.get("nested")).get("newPassword"));
        assertEquals("FARM_MANAGER", ((Map<?, ?>) body.get("nested")).get("role"));
        assertFalse(out.toString().contains("s3cret!"));
    }

    @Test
    void secretLookingKeysInAPlainMapAreMaskedToo() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("apiKey", "abc");
        raw.put("Authorization", "Bearer abc");
        raw.put("access_token", "abc");
        raw.put("animalId", "VB-000001");

        Map<String, Object> out = first(AuditPayload.sanitize(new Object[]{raw}, TO_MAP));

        assertEquals(AuditPayload.MASK, out.get("apiKey"));
        assertEquals(AuditPayload.MASK, out.get("Authorization"));
        assertEquals(AuditPayload.MASK, out.get("access_token"));
        assertEquals("VB-000001", out.get("animalId"));
    }

    @Test
    void credentialIdentifierIsARfidFieldNotASecret() {
        assertFalse(AuditPayload.isSecretName("credentialIdentifier"));
        assertTrue(AuditPayload.isSecretName("password"));
        assertTrue(AuditPayload.isSecretName("newPassword"));
        assertTrue(AuditPayload.isSecretName("jwtToken"));
    }

    @Test
    void principalsAreDroppedAndPlainValuesKept() {
        List<Object> out = AuditPayload.sanitize(new Object[]{"FARM-1", 42, true, new FakeAuth(), null}, TO_MAP);

        assertEquals(List.of("FARM-1", 42, true), out);
    }

    @Test
    void longTextAndLongListsAreBounded() {
        String huge = "x".repeat(5000);
        List<Object> many = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) many.add("item-" + i);

        List<Object> out = AuditPayload.sanitize(new Object[]{huge, many}, TO_MAP);

        assertTrue(((String) out.get(0)).length() < 260);
        List<?> cut = (List<?>) out.get(1);
        assertEquals(AuditPayload.MAX_ITEMS + 1, cut.size());
        assertTrue(String.valueOf(cut.get(cut.size() - 1)).contains("100 items"));
    }

    @Test
    void somethingThatIsNotABeanIsReducedToItsTypeName() {
        List<Object> out = AuditPayload.sanitize(new Object[]{new Object()}, TO_MAP);

        assertEquals("Object", out.get(0));
    }

    @Test
    void deepStructuresStopAtTheDepthLimit() {
        Map<String, Object> deep = new LinkedHashMap<>();
        Map<String, Object> cursor = deep;
        for (int i = 0; i < 10; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put("n", next);
            cursor = next;
        }

        String text = AuditPayload.sanitize(new Object[]{deep}, TO_MAP).toString();

        assertTrue(text.contains("..."));
    }

    @Test
    void nullArgumentArrayGivesAnEmptyList() {
        assertTrue(AuditPayload.sanitize(null, TO_MAP).isEmpty());
    }
}
