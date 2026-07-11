package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** {@code value [NOT] IN (item, ...)}. */
public record InListPredicate(Expression value, List<Expression> items, boolean negated,
                              SourcePosition pos) implements Expression {

    public InListPredicate {
        items = List.copyOf(items);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitInListPredicate(this);
    }
}
