package ru.warndev.configdoctor;

public final class DoctorException extends Exception {
    private final String code;
    private final int line;
    private final int column;

    public DoctorException(String code, String message) {
        this(code, message, 0, 0);
    }

    public DoctorException(String code, String message, int line, int column) {
        super(message);
        this.code = code;
        this.line = line;
        this.column = column;
    }

    public String code() {
        return code;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }
}
