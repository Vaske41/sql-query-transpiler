package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** A unary operation: NOT, unary minus, unary plus. */
public record UnaryOp(UnaryOperator op, Expression operand, SourcePosition pos)
        implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUnaryOp(this);
    }
}
