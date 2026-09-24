package ru.warndev.configdoctor;

import java.util.List;

public record Profile(String id, String file, List<Rule> rules) {
    public Profile {
        rules = List.copyOf(rules);
    }
}
