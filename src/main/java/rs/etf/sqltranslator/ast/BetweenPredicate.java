package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** {@code value [NOT] BETWEEN low AND high}. */
public record BetweenPredicate(Expression value, Expression low, Expression high,
                               boolean negated, SourcePosition pos) implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitBetweenPredicate(this);
    }
}
