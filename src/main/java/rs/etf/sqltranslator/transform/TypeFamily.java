package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.GenericType;

/** Coarse type families for mismatch detection (cast insertion, concat evidence). */
public enum TypeFamily {
    NUMERIC, STRING, BOOLEAN, DATETIME, BINARY;

    public static TypeFamily of(GenericType type) {
        return switch (type) {
            case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, FLOAT, DOUBLE -> NUMERIC;
            case CHAR, VARCHAR, NVARCHAR, TEXT -> STRING;
            case BOOLEAN -> BOOLEAN;
            case DATE, TIME, TIMESTAMP -> DATETIME;
            case BLOB -> BINARY;
        };
    }
}
