package rs.etf.sqltranslator.core;

/** One syntax error reported by the lexer or parser, 1-based line, 0-based column. */
public record SyntaxError(int line, int charPositionInLine, String message) {}
