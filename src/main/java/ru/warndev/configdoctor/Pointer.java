package ru.warndev.configdoctor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Pointer(String text, List<String> tokens) {
    public Pointer {
        tokens = List.copyOf(tokens);
    }

    public static Pointer parse(String text) {
        Objects.requireNonNull(text);
        if (text.length() > 2048) {
            throw new IllegalArgumentException("Слишком длинный путь правила");
        }
        if (text.isEmpty()) {
            return new Pointer(text, List.of());
        }
        if (!text.startsWith("/")) {
            throw new IllegalArgumentException("Путь должен начинаться с / или быть пустым");
        }
        String[] parts = text.substring(1).split("/", -1);
        if (parts.length > 32) {
            throw new IllegalArgumentException("В пути больше 32 уровней");
        }
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part.length() > 256 || part.matches(".*~(?:[^01]|$).*")) {
                throw new IllegalArgumentException("Неверное экранирование пути");
            }
            tokens.add(part.replace("~1", "/").replace("~0", "~"));
        }
        return new Pointer(text, tokens);
    }

    public Resolution resolve(Object document) {
        Object current = document;
        for (String token : tokens) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    return new Resolution(false, null);
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                if (!token.matches("0|[1-9][0-9]{0,8}")) {
                    return new Resolution(false, null);
                }
                int index = Integer.parseInt(token);
                if (index >= list.size()) {
                    return new Resolution(false, null);
                }
                current = list.get(index);
            } else {
                return new Resolution(false, null);
            }
        }
        return new Resolution(true, current);
    }

    public record Resolution(boolean present, Object value) {
    }
}
