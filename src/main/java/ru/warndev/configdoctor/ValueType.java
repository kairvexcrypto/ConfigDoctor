package ru.warndev.configdoctor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public enum ValueType {
    STRING,
    BOOLEAN,
    INTEGER,
    NUMBER,
    ARRAY,
    OBJECT;

    public boolean matches(Object value) {
        return switch (this) {
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case INTEGER -> value instanceof Number && decimal(value) != null
                    && decimal(value).stripTrailingZeros().scale() <= 0;
            case NUMBER -> value instanceof Number && decimal(value) != null;
            case ARRAY -> value instanceof List<?>;
            case OBJECT -> value instanceof Map<?, ?>;
        };
    }

    public static ValueType parse(String value) {
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Неизвестный тип правила");
        }
    }

    public static BigDecimal decimal(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    public static boolean same(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            BigDecimal first = decimal(left);
            BigDecimal second = decimal(right);
            return first != null && second != null && first.compareTo(second) == 0;
        }
        return java.util.Objects.equals(left, right);
    }

    public static boolean scalar(Object value) {
        return value == null || value instanceof String || value instanceof Boolean || decimal(value) != null;
    }
}
