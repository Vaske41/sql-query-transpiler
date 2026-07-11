package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** A scalar subquery used as an expression. */
public record SubqueryExpression(Query query, SourcePosition pos) implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitSubqueryExpression(this);
    }
}
