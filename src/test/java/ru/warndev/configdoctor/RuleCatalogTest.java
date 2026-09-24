package ru.warndev.configdoctor;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleCatalogTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "{path: /x, type: integer, min: 4, max: 2}",
        "{path: /x, type: string, min: 1}",
        "{path: /x, type: integer, max-length: 4}",
        "{path: /x, type: string, unique: true}",
        "{path: /x, type: array, item-type: object, unique: true}",
        "{path: /x, type: string, typo: 1}",
        "{path: /x, type: unknown}",
        "{path: /x, type: string, required: null}",
        "{path: /x, type: number, allowed: [1, 1.0]}",
        "{path: /x, type: string, allowed: []}",
        "{path: /x, type: string, allowed: [1]}",
        "{path: /x, type: object, allowed-keys: [a, a]}",
        "{path: /x, type: string, min-length: 2.5}",
        "{path: /x, type: string, when: {path: /mode}}",
        "{path: /x, type: string, severity: critical}",
        "{path: /x~3, type: string}"
    })
    void rejectsBadRule(String rule) {
        assertEquals("RULE_SCHEMA", assertThrows(DoctorException.class,
                () -> Fixtures.profile("- " + rule)).code());
    }

    @Test
    void loadsBundledRules() throws Exception {
        try (var input = getClass().getResourceAsStream("/rules.yml")) {
            RuleCatalog catalog = RuleCatalog.parse(input.readAllBytes());
            assertEquals(2, catalog.profiles().size());
            assertTrue(catalog.profiles().containsKey("example"));
            assertThrows(UnsupportedOperationException.class, () -> catalog.profiles().clear());
        }
    }

    @Test
    void rejectsUnknownVersionAndEmptyCatalog() {
        assertThrows(DoctorException.class, () -> RuleCatalog.parse(Fixtures.bytes("version: 2\nprofiles: {}")));
        assertThrows(DoctorException.class, () -> RuleCatalog.parse(Fixtures.bytes("version: 1\nprofiles: {}")));
    }
}
