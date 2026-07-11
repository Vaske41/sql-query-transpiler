package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** One ORDER BY item; {@code nulls} is PostgreSQL-only and translated in Phase 4. */
public record OrderItem(Expression expr, SortDirection direction, Optional<NullsOrder> nulls,
                        SourcePosition pos) implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitOrderItem(this);
    }
}
