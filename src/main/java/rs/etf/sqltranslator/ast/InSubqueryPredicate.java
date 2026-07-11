package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** {@code value [NOT] IN (subquery)}. */
public record InSubqueryPredicate(Expression value, Query subquery, boolean negated,
                                  SourcePosition pos) implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitInSubqueryPredicate(this);
    }
}
