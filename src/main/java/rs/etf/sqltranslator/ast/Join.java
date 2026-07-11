package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** One join step; {@code on} is empty exactly for {@link JoinKind#CROSS}. */
public record Join(JoinKind kind, TableRef table, Optional<Expression> on, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitJoin(this);
    }
}
