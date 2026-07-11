package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** {@code EXISTS (subquery)}. */
public record ExistsPredicate(Query subquery, SourcePosition pos) implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitExistsPredicate(this);
    }
}
