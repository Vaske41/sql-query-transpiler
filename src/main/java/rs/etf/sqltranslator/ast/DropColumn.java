package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** ALTER TABLE ... DROP COLUMN. */
public record DropColumn(Identifier column, SourcePosition pos) implements AlterAction {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDropColumn(this);
    }
}
