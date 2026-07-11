package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** A binary operation; chains fold left-associatively at build time. */
public record BinaryOp(BinaryOperator op, Expression left, Expression right,
                       SourcePosition pos) implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitBinaryOp(this);
    }
}
