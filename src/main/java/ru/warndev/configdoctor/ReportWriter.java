package ru.warndev.configdoctor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReportWriter {
    private final Path directory;

    public ReportWriter(Path directory) {
        this.directory = directory;
    }

    public Path write(List<CheckReport> reports) throws DoctorException {
        if (reports.isEmpty() || reports.size() > 32) {
            throw new DoctorException("REPORT_EMPTY", "Нет результатов для экспорта");
        }
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new DoctorException("REPORT_PATH", "Папка отчётов недоступна");
            }
            try (var files = Files.list(directory)) {
                if (files.limit(100).count() >= 100) {
                    throw new DoctorException("REPORT_LIMIT", "Папка содержит 100 файлов; архивируйте старые отчёты");
                }
            }
            String name = "report-" + UUID.randomUUID() + ".md";
            temporary = Files.createTempFile(directory, ".report-", ".tmp");
            Files.writeString(temporary, markdown(reports), StandardCharsets.UTF_8);
            Path result = directory.resolve(name);
            try {
                Files.move(temporary, result, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, result);
            }
            return result;
        } catch (IOException error) {
            throw new DoctorException("REPORT_IO", "Не удалось записать отчёт");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static String markdown(List<CheckReport> reports) {
        StringBuilder result = new StringBuilder("# ConfigDoctor\n\nЭкспорт: ").append(Instant.now()).append("\n\n");
        for (CheckReport report : reports) {
            result.append("## ").append(safe(report.profile())).append("\n\n")
                    .append("Файл: ").append(safe(report.file())).append("\n\n")
                    .append("Проверка: ").append(report.created()).append(" · ID: ").append(report.id()).append("\n\n")
                    .append("Правил: ").append(report.checked()).append(" · Ошибок: ").append(report.errors())
                    .append(" · Предупреждений: ").append(report.warnings()).append("\n\n")
                    .append("| Уровень | Путь | Код | Результат |\n|---|---|---|---|\n");
            for (var finding : report.findings()) {
                result.append("| ").append(finding.severity()).append(" | ").append(safe(finding.path()))
                        .append(" | ").append(safe(finding.code())).append(" | ")
                        .append(safe(finding.message())).append(" |\n");
            }
            if (report.findings().isEmpty()) {
                result.append("| OK | — | PASSED | Нарушений заданных правил не найдено |\n");
            }
            if (report.truncated()) {
                result.append("\nДостигнут предел 256 замечаний. Исправьте указанные ошибки и повторите проверку.\n");
            }
            result.append('\n');
        }
        result.append("Проверяются только заданные правила. Значения конфигурации в отчёт не включаются.\n");
        return result.toString();
    }

    public static String safe(String value) {
        StringBuilder result = new StringBuilder();
        for (int code : value.codePoints().toArray()) {
            if (Character.isISOControl(code) || Character.getType(code) == Character.FORMAT) {
                result.append(' ');
            } else if (code == '&') {
                result.append("&amp;");
            } else if (code == '<') {
                result.append("&lt;");
            } else if (code == '>') {
                result.append("&gt;");
            } else if ("|`*_[]\\".indexOf(code) >= 0) {
                result.append('\\').appendCodePoint(code);
            } else {
                result.appendCodePoint(code);
            }
        }
        return result.toString();
    }
}
