package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.core.SourcePosition;

/** One translation warning: stable code, human message, source position. */
public record Warning(String code, String message, SourcePosition position) {
}
