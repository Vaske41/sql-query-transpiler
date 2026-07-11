package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** A table reference with an optional alias. */
public record TableRef(QualifiedName table, Optional<Identifier> alias, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitTableRef(this);
    }
}
