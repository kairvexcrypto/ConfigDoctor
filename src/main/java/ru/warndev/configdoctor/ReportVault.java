package ru.warndev.configdoctor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReportVault {
    private final Clock clock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public ReportVault(Clock clock) {
        this.clock = clock;
    }

    public void put(UUID owner, List<CheckReport> reports) {
        expire();
        entries.remove(owner);
        if (reports.size() > 32) {
            throw new IllegalArgumentException("Слишком много отчётов");
        }
        while (entries.size() >= 32) {
            entries.remove(entries.keySet().iterator().next());
        }
        entries.put(owner, new Entry(clock.instant(), List.copyOf(reports)));
    }

    public List<CheckReport> get(UUID owner) {
        expire();
        Entry entry = entries.get(owner);
        return entry == null ? List.of() : entry.reports();
    }

    public void remove(UUID owner) {
        entries.remove(owner);
    }

    public void clear() {
        entries.clear();
    }

    private void expire() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(10));
        entries.values().removeIf(entry -> !entry.created().isAfter(cutoff));
    }

    private record Entry(Instant created, List<CheckReport> reports) {
    }
}
