package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** {@code CAST(expr AS type)}; T-SQL 2-arg {@code CONVERT} folds into this node. */
public record CastExpression(Expression operand, DataType targetType, SourcePosition pos)
        implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCastExpression(this);
    }
}
