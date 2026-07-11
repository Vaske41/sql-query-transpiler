package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** Statement wrapper around a top-level {@link Query}. */
public record SelectStatement(Query query, SourcePosition pos) implements Statement {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitSelectStatement(this);
    }
}
