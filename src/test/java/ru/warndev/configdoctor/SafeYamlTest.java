package ru.warndev.configdoctor;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SafeYamlTest {
    @Test
    void loadsNestedDocument() throws Exception {
        var document = (Map<?, ?>) new SafeYaml().parse(Fixtures.bytes("world: {name: earth, limit: 20}"));
        assertEquals("earth", Pointer.parse("/world/name").resolve(document).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"key: 1\nkey: 2", "!!java.lang.ProcessBuilder {}", "a: &x [1]\nb: *x",
            "a: .nan", "a: 2020-01-01", "[a, b", "1: secret", "---\na: 1\n---\na: 2"})
    void rejectsUnsupportedOrUnsafeDocuments(String yaml) {
        assertThrows(DoctorException.class, () -> new SafeYaml().parse(Fixtures.bytes(yaml)));
    }

    @Test
    void rejectsMalformedUtf8() {
        DoctorException error = assertThrows(DoctorException.class,
                () -> new SafeYaml().parse(new byte[]{(byte) 0xc3, 0x28}));
        assertEquals("ENCODING", error.code());
    }

    @Test
    void rejectsOversizedInput() {
        assertEquals("FILE_SIZE", assertThrows(DoctorException.class,
                () -> new SafeYaml().parse(new byte[1048577])).code());
    }

    @Test
    void rejectsDeepDocument() {
        String yaml = "[".repeat(40) + "0" + "]".repeat(40);
        assertThrows(DoctorException.class, () -> new SafeYaml().parse(Fixtures.bytes(yaml)));
    }

    @Test
    void errorDoesNotIncludeSecrets() {
        DoctorException error = assertThrows(DoctorException.class,
                () -> new SafeYaml().parse(Fixtures.bytes("token: [SECRET_TOKEN,\nwrong")));
        assertFalse(error.getMessage().contains("SECRET_TOKEN"));
        assertTrue(error.line() > 0);
    }
}
