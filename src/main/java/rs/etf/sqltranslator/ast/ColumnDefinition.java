package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/**
 * One column of a CREATE TABLE or ALTER TABLE ADD COLUMN. Auto-increment is always
 * this flag — {@code IDENTITY(1,1)}, {@code AUTO_INCREMENT}, {@code GENERATED ... AS
 * IDENTITY} and {@code SERIAL} all fold into it; it is never a type or constraint node.
 * An empty {@code nullable} means nullability was not written in the source.
 */
public record ColumnDefinition(Identifier name, DataType type, boolean autoIncrement,
                               Optional<Boolean> nullable,
                               Optional<Expression> defaultValue,
                               boolean primaryKey, boolean unique,
                               Optional<ForeignKeyRef> references, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitColumnDefinition(this);
    }
}
