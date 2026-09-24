package ru.warndev.configdoctor;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidatorTest {
    @Test
    void distinguishesAbsentFromNull() throws Exception {
        assertEquals("REQUIRED", Fixtures.check("- {path: /x, type: string, required: true}", "{}").findings().getFirst().code());
        assertEquals("NULL", Fixtures.check("- {path: /x, type: string}", "x: null").findings().getFirst().code());
        assertTrue(Fixtures.check("- {path: /x, type: string, nullable: true}", "x: null").passed());
        assertTrue(Fixtures.check("- {path: /x, type: string}", "{}").passed());
    }

    @Test
    void resolvesEscapedKeysAndLists() {
        Object value = Map.of("a/b", Map.of("~c", List.of("one", "two")));
        assertEquals("two", Pointer.parse("/a~1b/~0c/1").resolve(value).value());
        assertFalse(Pointer.parse("/a~1b/~0c/01").resolve(value).present());
        assertFalse(Pointer.parse("/a~1b/~0c/9").resolve(value).present());
        assertEquals(value, Pointer.parse("").resolve(value).value());
    }

    @Test
    void validatesNumberBoundariesAndTypes() throws Exception {
        String rules = "- {path: /x, type: integer, min: 1, max: 10}";
        assertTrue(Fixtures.check(rules, "x: 1").passed());
        assertTrue(Fixtures.check(rules, "x: 10.0").passed());
        assertEquals("TYPE", Fixtures.check(rules, "x: 1.5").findings().getFirst().code());
        assertEquals("MIN", Fixtures.check(rules, "x: 0").findings().getFirst().code());
        assertEquals("MAX", Fixtures.check(rules, "x: 11").findings().getFirst().code());
        assertEquals("TYPE", Fixtures.check(rules, "x: '5'").findings().getFirst().code());
    }

    @Test
    void countsUnicodeCodePoints() throws Exception {
        assertTrue(Fixtures.check("- {path: /x, type: string, max-length: 1}", "x: '😀'").passed());
        assertFalse(Fixtures.check("- {path: /x, type: string, max-length: 1}", "x: '😀😀'").passed());
    }

    @Test
    void conditionsActivateRequiredFields() throws Exception {
        String rule = "- {path: /world, type: string, required: true, when: {path: /mode, equals: arena}}";
        assertTrue(Fixtures.check(rule, "mode: survival").passed());
        assertEquals(0, Fixtures.check(rule, "mode: survival").checked());
        assertEquals("REQUIRED", Fixtures.check(rule, "mode: arena").findings().getFirst().code());
    }

    @Test
    void comparesEnumsNumericallyAndHonorsNullEnum() throws Exception {
        assertTrue(Fixtures.check("- {path: /x, type: number, allowed: [1]}", "x: 1.0").passed());
        assertFalse(Fixtures.check("- {path: /x, type: string, nullable: true, allowed: [a]}", "x: null").passed());
        assertTrue(Fixtures.check("- {path: /x, type: string, nullable: true, allowed: [null]}", "x: null").passed());
    }

    @Test
    void findsListDuplicatesWithCanonicalNumbers() throws Exception {
        var report = Fixtures.check("- {path: /x, type: array, unique: true}", "x: [1, 1.0]");
        assertEquals("DUPLICATE", report.findings().getFirst().code());
        assertTrue(Fixtures.check("- {path: /x, type: array, unique: true}", "x: [1, '1']").passed());
    }

    @Test
    void validatesListAndObjectConstraintsWithoutEchoingKeys() throws Exception {
        var report = Fixtures.check("- {path: /x, type: array, min-items: 3, item-type: string}", "x: [1]");
        assertEquals(List.of("MIN_ITEMS", "ITEM_TYPE"), report.findings().stream().map(CheckReport.Finding::code).toList());
        var object = Fixtures.check("- {path: /x, type: object, allowed-keys: [a]}", "x: {SECRET: token}");
        assertEquals("UNKNOWN_KEYS", object.findings().getFirst().code());
        assertFalse(ReportWriter.markdown(List.of(object)).contains("SECRET"));
    }

    @Test
    void warningDoesNotFailReport() throws Exception {
        var report = Fixtures.check("- {path: /x, type: string, required: true, severity: warning}", "{}");
        assertTrue(report.passed());
        assertEquals(1, report.warnings());
        assertEquals(0, report.errors());
    }

    @Test
    void boundsFindingsAndMarksTruncation() throws Exception {
        String rule = "- {path: /x, type: array, min-items: 2, item-type: string}\n";
        var report = Fixtures.check(rule.repeat(256), "x: [1]");
        assertEquals(256, report.findings().size());
        assertTrue(report.truncated());
        assertFalse(report.passed());
    }

    @Test
    void doesNotEchoSecretValues() throws Exception {
        var report = Fixtures.check("- {path: /token, type: string, max-length: 3}", "token: SECRET-12345");
        assertFalse(ReportWriter.markdown(List.of(report)).contains("SECRET-12345"));
    }
}
