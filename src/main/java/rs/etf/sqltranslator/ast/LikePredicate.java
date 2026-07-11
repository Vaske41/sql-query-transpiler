package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** {@code value [NOT] LIKE pattern}. */
public record LikePredicate(Expression value, Expression pattern, boolean negated,
                            SourcePosition pos) implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitLikePredicate(this);
    }
}
