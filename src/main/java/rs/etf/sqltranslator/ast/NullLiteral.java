package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** The NULL literal. */
public record NullLiteral(SourcePosition pos) implements Literal {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitNullLiteral(this);
    }
}
