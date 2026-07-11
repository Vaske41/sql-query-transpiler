package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** One {@code column = value} assignment of an UPDATE. */
public record Assignment(Identifier column, Expression value, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAssignment(this);
    }
}
