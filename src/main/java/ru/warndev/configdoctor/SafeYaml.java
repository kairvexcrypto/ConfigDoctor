package ru.warndev.configdoctor;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

public final class SafeYaml {
    public Object parse(byte[] bytes) throws DoctorException {
        if (bytes.length > 1048576) {
            throw new DoctorException("FILE_SIZE", "Файл превышает 1 МиБ");
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new DoctorException("ENCODING", "Ожидается UTF-8");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(32);
        options.setCodePointLimit(1048576);
        options.setProcessComments(false);
        try {
            Object document = new Yaml(new SafeConstructor(options)).load(content);
            inspect(document, 0, new IdentityHashMap<>(), new int[]{0});
            return document;
        } catch (MarkedYAMLException error) {
            Mark mark = error.getProblemMark();
            throw new DoctorException("YAML_SYNTAX", "Ошибка структуры YAML",
                    mark == null ? 0 : mark.getLine() + 1, mark == null ? 0 : mark.getColumn() + 1);
        } catch (YAMLException | IllegalArgumentException error) {
            throw new DoctorException("YAML_FORMAT", "Недопустимая структура или тег YAML");
        }
    }

    private void inspect(Object value, int depth, IdentityHashMap<Object, Boolean> seen, int[] count) throws DoctorException {
        if (++count[0] > 50000 || depth > 32) {
            throw new DoctorException("DOCUMENT_LIMIT", "Превышен предел сложности документа");
        }
        if (value instanceof Map<?, ?> map) {
            if (seen.put(value, Boolean.TRUE) != null) {
                throw new DoctorException("ALIASES", "Повторные ссылки на коллекции запрещены");
            }
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.length() > 256) {
                    throw new DoctorException("KEY_TYPE", "Ключи должны быть строками длиной до 256 символов");
                }
                inspect(entry.getValue(), depth + 1, seen, count);
            }
        } else if (value instanceof List<?> list) {
            if (seen.put(value, Boolean.TRUE) != null) {
                throw new DoctorException("ALIASES", "Повторные ссылки на коллекции запрещены");
            }
            for (Object child : list) {
                inspect(child, depth + 1, seen, count);
            }
        } else if (!ValueType.scalar(value)) {
            throw new DoctorException("VALUE_TYPE", "Тип значения не поддерживается");
        }
    }
}
