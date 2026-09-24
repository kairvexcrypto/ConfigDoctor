package ru.warndev.configdoctor;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorageTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"../secret.yml", "/etc/secret.yml", "Plugin/../secret.yml", "Plugin\\config.yml", "config.yml",
            "Plugin/config.txt", "Plugin//config.yml", "Plugin/./config.yml"})
    void rejectsPaths(String value) {
        assertThrows(IllegalArgumentException.class, () -> TargetFiles.validate(value));
    }

    @Test
    void readsFileAndRefusesSymbolicLink() throws Exception {
        Files.createDirectory(root.resolve("Demo"));
        Files.writeString(root.resolve("Demo/config.yml"), "x: 1");
        TargetFiles files = new TargetFiles(root);
        assertEquals("x: 1", new String(files.read("Demo/config.yml"), java.nio.charset.StandardCharsets.UTF_8));
        Files.createSymbolicLink(root.resolve("Demo/link.yml"), root.resolve("Demo/config.yml"));
        assertEquals("SYMLINK", assertThrows(DoctorException.class, () -> files.read("Demo/link.yml")).code());
        assertEquals("NOT_FOUND", assertThrows(DoctorException.class, () -> files.read("Demo/missing.yml")).code());
    }

    @Test
    void refusesOversizedFilesAndDirectories() throws Exception {
        Files.createDirectory(root.resolve("Demo"));
        Files.write(root.resolve("Demo/large.yml"), new byte[1048577]);
        Files.createDirectory(root.resolve("Demo/directory.yml"));
        TargetFiles files = new TargetFiles(root);
        assertEquals("FILE_SIZE", assertThrows(DoctorException.class, () -> files.read("Demo/large.yml")).code());
        assertEquals("FILE_TYPE", assertThrows(DoctorException.class, () -> files.read("Demo/directory.yml")).code());
    }

    @Test
    void reloadFailurePreservesCatalogAndChecksFilesReadOnly() throws Exception {
        Path data = Files.createDirectory(root.resolve("ConfigDoctor"));
        byte[] original = Fixtures.bytes("value: 10");
        Files.write(data.resolve("example.yml"), original);
        String rules = "version: 1\nprofiles:\n  demo:\n    file: ConfigDoctor/example.yml\n    rules:\n      - {path: /value, type: integer, min: 1}";
        Files.writeString(data.resolve("rules.yml"), rules);
        try (DoctorService service = new DoctorService(new TargetFiles(root), "ConfigDoctor/rules.yml", data.resolve("reports"))) {
            assertTrue(service.check("demo").get(5, TimeUnit.SECONDS).getFirst().passed());
            Files.writeString(data.resolve("rules.yml"), "broken: [");
            assertThrows(ExecutionException.class, () -> service.reload().get(5, TimeUnit.SECONDS));
            assertEquals(List.of("demo"), service.profiles());
            assertTrue(service.check("demo").get(5, TimeUnit.SECONDS).getFirst().passed());
            assertArrayEquals(original, Files.readAllBytes(data.resolve("example.yml")));
            Files.writeString(data.resolve("rules.yml"), rules.replace("min: 1", "min: 20"));
            assertEquals(1, service.reload().get(5, TimeUnit.SECONDS));
            assertFalse(service.check("demo").get(5, TimeUnit.SECONDS).getFirst().passed());
            assertThrows(ExecutionException.class, () -> service.check("missing").get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void exportsWithoutSecretsAndCapsDiskFiles() throws Exception {
        var report = Fixtures.check("- {path: /x, type: integer}", "x: SECRET_TOKEN");
        Path dir = root.resolve("reports");
        ReportWriter writer = new ReportWriter(dir);
        Path exported = writer.write(List.of(report));
        assertTrue(Files.isRegularFile(exported));
        assertFalse(Files.readString(exported).contains("SECRET_TOKEN"));
        for (int i = 1; i < 100; i++) {
            Files.writeString(dir.resolve("old-" + i + ".md"), "old");
        }
        assertEquals("REPORT_LIMIT", assertThrows(DoctorException.class, () -> writer.write(List.of(report))).code());
    }

    @Test
    void vaultSeparatesOwnersAndEvictsOldEntries() throws Exception {
        var report = Fixtures.check("- {path: /x, type: string}", "{}");
        ReportVault vault = new ReportVault(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        UUID first = UUID.randomUUID();
        vault.put(first, List.of(report));
        assertEquals(List.of(report), vault.get(first));
        assertTrue(vault.get(UUID.randomUUID()).isEmpty());
        for (int i = 0; i < 32; i++) {
            vault.put(UUID.randomUUID(), List.of(report));
        }
        assertTrue(vault.get(first).isEmpty());
    }

    @Test
    void vaultExpiresAfterTenMinutes() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        ReportVault vault = new ReportVault(clock);
        UUID owner = UUID.randomUUID();
        vault.put(owner, List.of(Fixtures.check("- {path: /x, type: string}", "{}")));
        now.set(Instant.EPOCH.plus(Duration.ofMinutes(10)));
        assertTrue(vault.get(owner).isEmpty());
    }
}
