package ru.warndev.configdoctor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Validator {
    public CheckReport check(Profile profile, Object document) {
        Collector collector = new Collector();
        int checked = 0;
        for (Rule rule : profile.rules()) {
            if (rule.condition() != null && !rule.condition().matches(document)) {
                continue;
            }
            checked++;
            validate(rule, document, collector);
        }
        return new CheckReport(UUID.randomUUID(), profile.id(), profile.file(), Instant.now(),
                checked, collector.findings, collector.truncated);
    }

    public CheckReport failure(Profile profile, DoctorException error) {
        String location = error.line() > 0 ? " (строка " + error.line() + ", столбец " + error.column() + ")" : "";
        var finding = new CheckReport.Finding(Rule.Severity.ERROR, "", error.code(), error.getMessage() + location);
        return new CheckReport(UUID.randomUUID(), profile.id(), profile.file(), Instant.now(), 0, List.of(finding), false);
    }

    private void validate(Rule rule, Object document, Collector output) {
        Pointer.Resolution resolved = rule.path().resolve(document);
        if (!resolved.present()) {
            if (rule.required()) {
                output.add(rule, "REQUIRED", "Отсутствует обязательное поле");
            }
            return;
        }
        Object value = resolved.value();
        if (value == null) {
            if (!rule.nullable()) {
                output.add(rule, "NULL", "Пустое значение не допускается");
            } else if (rule.allowed() != null && rule.allowed().stream().noneMatch(item -> item == null)) {
                output.add(rule, "ALLOWED", "Значение отсутствует в разрешённом списке");
            }
            return;
        }
        if (!rule.type().matches(value)) {
            output.add(rule, "TYPE", "Ожидаемый тип: " + rule.type().name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        if (rule.allowed() != null && rule.allowed().stream().noneMatch(item -> ValueType.same(item, value))) {
            output.add(rule, "ALLOWED", "Значение отсутствует в разрешённом списке");
        }
        if (value instanceof Number) {
            BigDecimal number = ValueType.decimal(value);
            if (rule.min() != null && number.compareTo(rule.min()) < 0) {
                output.add(rule, "MIN", "Число меньше допустимого минимума");
            }
            if (rule.max() != null && number.compareTo(rule.max()) > 0) {
                output.add(rule, "MAX", "Число больше допустимого максимума");
            }
        }
        if (value instanceof String string) {
            int length = string.codePointCount(0, string.length());
            if (rule.minLength() != null && length < rule.minLength()) {
                output.add(rule, "MIN_LENGTH", "Строка короче допустимой длины");
            }
            if (rule.maxLength() != null && length > rule.maxLength()) {
                output.add(rule, "MAX_LENGTH", "Строка длиннее допустимой длины");
            }
        }
        if (value instanceof List<?> list) {
            validateList(rule, list, output);
        }
        if (value instanceof Map<?, ?> map && rule.allowedKeys() != null) {
            if (!rule.allowedKeys().containsAll(map.keySet())) {
                output.add(rule, "UNKNOWN_KEYS", "Объект содержит неразрешённые ключи");
            }
        }
    }

    private void validateList(Rule rule, List<?> list, Collector output) {
        if (rule.minItems() != null && list.size() < rule.minItems()) {
            output.add(rule, "MIN_ITEMS", "Недостаточно элементов массива");
        }
        if (rule.maxItems() != null && list.size() > rule.maxItems()) {
            output.add(rule, "MAX_ITEMS", "Слишком много элементов массива");
        }
        if (rule.itemType() != null && list.stream().anyMatch(item -> !rule.itemType().matches(item))) {
            output.add(rule, "ITEM_TYPE", "Массив содержит элемент неверного типа");
        }
        if (!rule.unique()) {
            return;
        }
        Set<Object> seen = new HashSet<>();
        for (Object item : list) {
            if (!ValueType.scalar(item)) {
                output.add(rule, "UNIQUE_TYPE", "Проверка уникальности требует скалярных элементов");
                return;
            }
            Object canonical = item instanceof Number ? ValueType.decimal(item).stripTrailingZeros() : item;
            if (!seen.add(canonical)) {
                output.add(rule, "DUPLICATE", "Массив содержит повторные значения");
                return;
            }
        }
    }

    private static final class Collector {
        private final List<CheckReport.Finding> findings = new ArrayList<>();
        private boolean truncated;

        private void add(Rule rule, String code, String message) {
            if (findings.size() >= 256) {
                truncated = true;
                return;
            }
            findings.add(new CheckReport.Finding(rule.severity(), rule.path().text(), code, message));
        }
    }
}
