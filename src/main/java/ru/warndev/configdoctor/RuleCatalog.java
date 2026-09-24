package ru.warndev.configdoctor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RuleCatalog {
    private static final Set<String> RULE_KEYS = Set.of("path", "type", "required", "nullable", "min", "max",
            "min-length", "max-length", "min-items", "max-items", "allowed", "allowed-keys", "item-type",
            "unique", "when", "severity");
    private final Map<String, Profile> profiles;

    private RuleCatalog(Map<String, Profile> profiles) {
        this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
    }

    public Map<String, Profile> profiles() {
        return profiles;
    }

    public static RuleCatalog parse(byte[] bytes) throws DoctorException {
        Object document = new SafeYaml().parse(bytes);
        try {
            Map<String, Object> root = object(document);
            keys(root, Set.of("version", "profiles"));
            if (!ValueType.same(root.get("version"), 1)) {
                throw invalid("Поддерживается версия правил 1");
            }
            Map<String, Object> definitions = object(root.get("profiles"));
            if (definitions.isEmpty() || definitions.size() > 32) {
                throw invalid("Допускается от 1 до 32 профилей");
            }
            Map<String, Profile> result = new LinkedHashMap<>();
            for (var entry : definitions.entrySet()) {
                String id = entry.getKey();
                if (!id.matches("[a-z][a-z0-9-]{0,47}")) {
                    throw invalid("Идентификатор профиля: строчные латинские буквы, цифры, дефис");
                }
                Map<String, Object> definition = object(entry.getValue());
                keys(definition, Set.of("file", "rules"));
                String file = TargetFiles.validate(string(definition, "file"));
                Object rawRules = definition.get("rules");
                if (!(rawRules instanceof List<?> list) || list.isEmpty() || list.size() > 256) {
                    throw invalid("Профиль должен содержать от 1 до 256 правил");
                }
                List<Rule> rules = new ArrayList<>();
                for (Object rawRule : list) {
                    rules.add(rule(object(rawRule)));
                }
                result.put(id, new Profile(id, file, rules));
            }
            return new RuleCatalog(result);
        } catch (IllegalArgumentException error) {
            throw new DoctorException("RULE_SCHEMA", error.getMessage());
        }
    }

    private static Rule rule(Map<String, Object> map) {
        keys(map, RULE_KEYS);
        Pointer path = Pointer.parse(string(map, "path"));
        ValueType type = ValueType.parse(string(map, "type"));
        boolean required = bool(map, "required", false);
        boolean nullable = bool(map, "nullable", false);
        BigDecimal min = number(map, "min");
        BigDecimal max = number(map, "max");
        Integer minLength = size(map, "min-length");
        Integer maxLength = size(map, "max-length");
        Integer minItems = size(map, "min-items");
        Integer maxItems = size(map, "max-items");
        if ((min != null || max != null) && type != ValueType.INTEGER && type != ValueType.NUMBER) {
            throw invalid("min и max применимы только к числам");
        }
        if ((minLength != null || maxLength != null) && type != ValueType.STRING) {
            throw invalid("Ограничения длины применимы только к строкам");
        }
        if ((minItems != null || maxItems != null || map.containsKey("unique") || map.containsKey("item-type"))
                && type != ValueType.ARRAY) {
            throw invalid("Ограничения элементов применимы только к массивам");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw invalid("Минимум не может превышать максимум");
        }
        ordered(minLength, maxLength);
        ordered(minItems, maxItems);
        List<Object> allowed = null;
        if (map.containsKey("allowed")) {
            if (type == ValueType.ARRAY || type == ValueType.OBJECT) {
                throw invalid("allowed применимо только к скалярным значениям");
            }
            Object raw = map.get("allowed");
            if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 128) {
                throw invalid("allowed должен содержать от 1 до 128 значений");
            }
            allowed = new ArrayList<>();
            for (Object value : values) {
                if (value == null ? !nullable : !type.matches(value)) {
                    throw invalid("Тип allowed должен соответствовать типу правила");
                }
                for (Object prior : allowed) {
                    if (ValueType.same(prior, value)) {
                        throw invalid("allowed содержит повторное значение");
                    }
                }
                allowed.add(value);
            }
        }
        Set<String> allowedKeys = null;
        if (map.containsKey("allowed-keys")) {
            if (type != ValueType.OBJECT || !(map.get("allowed-keys") instanceof List<?> values)
                    || values.size() > 256) {
                throw invalid("allowed-keys должен быть списком ключей объекта");
            }
            allowedKeys = new LinkedHashSet<>();
            for (Object value : values) {
                if (!(value instanceof String key) || key.length() > 256 || !allowedKeys.add(key)) {
                    throw invalid("allowed-keys содержит недопустимый или повторный ключ");
                }
            }
        }
        ValueType itemType = map.containsKey("item-type") ? ValueType.parse(string(map, "item-type")) : null;
        boolean unique = bool(map, "unique", false);
        if (unique && (itemType == ValueType.ARRAY || itemType == ValueType.OBJECT)) {
            throw invalid("unique поддерживает только скалярные элементы");
        }
        Rule.Condition condition = null;
        if (map.containsKey("when")) {
            Map<String, Object> when = object(map.get("when"));
            keys(when, Set.of("path", "equals"));
            if (!when.containsKey("equals") || !ValueType.scalar(when.get("equals"))) {
                throw invalid("when.equals должен быть скалярным значением");
            }
            condition = new Rule.Condition(Pointer.parse(string(when, "path")), when.get("equals"));
        }
        Rule.Severity severity = Rule.Severity.ERROR;
        if (map.containsKey("severity")) {
            String value = string(map, "severity");
            severity = switch (value) {
                case "error" -> Rule.Severity.ERROR;
                case "warning" -> Rule.Severity.WARNING;
                default -> throw invalid("severity: error или warning");
            };
        }
        return new Rule(path, type, required, nullable, min, max, minLength, maxLength,
                minItems, maxItems, allowed, allowedKeys, itemType, unique, condition, severity);
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid("Ожидается YAML-объект");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid("Ключи правил должны быть строками");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void keys(Map<String, Object> map, Set<String> accepted) {
        if (!accepted.containsAll(map.keySet())) {
            throw invalid("Неизвестный параметр схемы правил");
        }
    }

    private static String string(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String value)) {
            throw invalid("Обязательный строковый параметр: " + key);
        }
        return value;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        if (!(map.get(key) instanceof Boolean value)) {
            throw invalid("Ожидается boolean: " + key);
        }
        return value;
    }

    private static BigDecimal number(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) {
            return null;
        }
        BigDecimal result = ValueType.decimal(map.get(key));
        if (result == null) {
            throw invalid("Ожидается конечное число: " + key);
        }
        return result;
    }

    private static Integer size(Map<String, Object> map, String key) {
        BigDecimal value = number(map, key);
        if (value == null) {
            return null;
        }
        try {
            int result = value.intValueExact();
            if (result < 0 || result > 1048576) {
                throw invalid("Размер должен находиться в пределах 0..1048576");
            }
            return result;
        } catch (ArithmeticException error) {
            throw invalid("Размер должен быть целым числом");
        }
    }

    private static void ordered(Integer min, Integer max) {
        if (min != null && max != null && min > max) {
            throw invalid("Минимальный размер превышает максимальный");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
