package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** DROP TABLE [IF EXISTS]. */
public record DropTableStatement(QualifiedName table, boolean ifExists, SourcePosition pos)
        implements Statement {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDropTableStatement(this);
    }
}
