package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** {@code value IS [NOT] NULL}. */
public record IsNullPredicate(Expression value, boolean negated, SourcePosition pos)
        implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitIsNullPredicate(this);
    }
}
