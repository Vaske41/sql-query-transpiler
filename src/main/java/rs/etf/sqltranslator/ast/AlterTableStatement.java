package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** ALTER TABLE with exactly one {@link AlterAction}. */
public record AlterTableStatement(QualifiedName table, AlterAction action, SourcePosition pos)
        implements Statement {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAlterTableStatement(this);
    }
}
