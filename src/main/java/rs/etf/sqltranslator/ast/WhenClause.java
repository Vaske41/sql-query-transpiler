package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** One {@code WHEN condition THEN result} arm of a CASE. */
public record WhenClause(Expression condition, Expression result, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitWhenClause(this);
    }
}
