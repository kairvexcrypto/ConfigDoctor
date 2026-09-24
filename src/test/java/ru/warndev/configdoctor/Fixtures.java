package ru.warndev.configdoctor;

import java.nio.charset.StandardCharsets;

final class Fixtures {
    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static Profile profile(String rules) throws DoctorException {
        String source = "version: 1\nprofiles:\n  demo:\n    file: Demo/config.yml\n    rules:\n"
                + rules.indent(6);
        return RuleCatalog.parse(bytes(source)).profiles().get("demo");
    }

    static CheckReport check(String rules, String document) throws DoctorException {
        return new Validator().check(profile(rules), new SafeYaml().parse(bytes(document)));
    }
}
