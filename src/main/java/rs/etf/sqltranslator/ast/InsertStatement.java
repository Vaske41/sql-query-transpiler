package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** INSERT ... VALUES; an empty {@code columns} list means no column list was written. */
public record InsertStatement(QualifiedName table, List<Identifier> columns,
                              List<List<Expression>> rows, SourcePosition pos)
        implements Statement {

    public InsertStatement {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitInsertStatement(this);
    }
}
