package ru.warndev.configdoctor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

public final class TargetFiles {
    private final Path root;

    public TargetFiles(Path root) throws IOException {
        this.root = root.toRealPath();
    }

    public static String validate(String name) {
        if (name == null || name.length() > 512 || name.contains("\\") || name.startsWith("/")) {
            throw new IllegalArgumentException("Неверный относительный путь файла");
        }
        String[] parts = name.split("/", -1);
        if (parts.length < 2 || parts.length > 12) {
            throw new IllegalArgumentException("Укажите папку плагина и YAML-файл");
        }
        for (String part : parts) {
            if (part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9_. -]{1,128}")) {
                throw new IllegalArgumentException("Недопустимый компонент пути");
            }
        }
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
            throw new IllegalArgumentException("Допускаются только .yml и .yaml");
        }
        return name;
    }

    public byte[] read(String name) throws DoctorException {
        try {
            validate(name);
            Path current = root;
            for (String part : name.split("/")) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) {
                    throw new DoctorException("SYMLINK", "Символические ссылки не поддерживаются");
                }
            }
            Path file = current.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!file.startsWith(root)) {
                throw new DoctorException("PATH_ESCAPE", "Файл находится вне папки plugins");
            }
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) {
                throw new DoctorException("FILE_TYPE", "Ожидается обычный файл");
            }
            if (before.size() > 1048576) {
                throw new DoctorException("FILE_SIZE", "Файл превышает 1 МиБ");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) >= 0) {
                    buffer.flip();
                    if (output.size() + buffer.remaining() > 1048576) {
                        throw new DoctorException("FILE_SIZE", "Файл превышает 1 МиБ");
                    }
                    output.write(buffer.array(), 0, buffer.remaining());
                    buffer.clear();
                }
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !Objects.equals(before.fileKey(), after.fileKey())) {
                throw new DoctorException("FILE_CHANGED", "Файл изменился во время чтения; повторите проверку");
            }
            return output.toByteArray();
        } catch (NoSuchFileException error) {
            throw new DoctorException("NOT_FOUND", "Файл не найден");
        } catch (IOException | IllegalArgumentException error) {
            throw new DoctorException("FILE_ACCESS", "Не удалось прочитать выбранный файл");
        }
    }
}
