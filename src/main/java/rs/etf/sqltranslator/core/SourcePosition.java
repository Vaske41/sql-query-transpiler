package rs.etf.sqltranslator.core;

/**
 * Position of a construct in the original SQL text — 1-based line, 0-based column,
 * the same convention as {@link SyntaxError}. Lives in {@code core/} (not {@code ast/})
 * so {@link UnsupportedFeatureException} keeps {@code core/} free of AST dependencies.
 */
public record SourcePosition(int line, int column) {

    @Override
    public String toString() {
        return line + ":" + column;
    }
}
