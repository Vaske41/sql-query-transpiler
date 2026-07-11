package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** A column-level {@code REFERENCES table[(column)]} clause. */
public record ForeignKeyRef(QualifiedName table, Optional<Identifier> column,
                            SourcePosition pos) implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitForeignKeyRef(this);
    }
}
