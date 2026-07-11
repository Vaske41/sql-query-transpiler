package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** ALTER TABLE ... ADD [COLUMN]. */
public record AddColumn(ColumnDefinition column, SourcePosition pos) implements AlterAction {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAddColumn(this);
    }
}
