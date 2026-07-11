package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/**
 * An identifier stored unquoted and unescaped, with case preserved as written (D6);
 * {@code quoted} remembers whether the source wrote it delimited.
 */
public record Identifier(String value, boolean quoted, SourcePosition pos) implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitIdentifier(this);
    }
}
