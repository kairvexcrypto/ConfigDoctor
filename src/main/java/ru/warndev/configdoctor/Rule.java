package ru.warndev.configdoctor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record Rule(
        Pointer path,
        ValueType type,
        boolean required,
        boolean nullable,
        BigDecimal min,
        BigDecimal max,
        Integer minLength,
        Integer maxLength,
        Integer minItems,
        Integer maxItems,
        List<Object> allowed,
        Set<String> allowedKeys,
        ValueType itemType,
        boolean unique,
        Condition condition,
        Severity severity) {

    public Rule {
        allowed = allowed == null ? null : Collections.unmodifiableList(new ArrayList<>(allowed));
        allowedKeys = allowedKeys == null ? null : Set.copyOf(allowedKeys);
    }

    public enum Severity {
        ERROR,
        WARNING
    }

    public record Condition(Pointer path, Object expected) {
        public boolean matches(Object document) {
            Pointer.Resolution result = path.resolve(document);
            return result.present() && ValueType.same(result.value(), expected);
        }
    }
}
