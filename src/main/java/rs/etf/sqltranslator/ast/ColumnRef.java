package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** A (possibly qualified) column reference used as an expression. */
public record ColumnRef(QualifiedName name, SourcePosition pos) implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitColumnRef(this);
    }
}
