package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** An expression SELECT item with an optional alias. */
public record SelectExpr(Expression expr, Optional<Identifier> alias, SourcePosition pos)
        implements SelectItem {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitSelectExpr(this);
    }
}
