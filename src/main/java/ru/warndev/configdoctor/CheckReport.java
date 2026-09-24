package ru.warndev.configdoctor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CheckReport(UUID id, String profile, String file, Instant created, int checked,
                          List<Finding> findings, boolean truncated) {
    public CheckReport {
        findings = List.copyOf(findings);
    }

    public long errors() {
        return findings.stream().filter(finding -> finding.severity() == Rule.Severity.ERROR).count();
    }

    public long warnings() {
        return findings.stream().filter(finding -> finding.severity() == Rule.Severity.WARNING).count();
    }

    public boolean passed() {
        return errors() == 0 && !truncated;
    }

    public record Finding(Rule.Severity severity, String path, String code, String message) {
    }
}
