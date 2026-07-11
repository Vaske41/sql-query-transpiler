package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** CREATE TABLE with column definitions and table-level constraints. */
public record CreateTableStatement(QualifiedName table, List<ColumnDefinition> columns,
                                   List<TableConstraint> constraints, SourcePosition pos)
        implements Statement {

    public CreateTableStatement {
        columns = List.copyOf(columns);
        constraints = List.copyOf(constraints);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCreateTableStatement(this);
    }
}
