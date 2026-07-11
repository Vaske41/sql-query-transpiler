package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/** Table-level FOREIGN KEY constraint; {@code refColumns} is empty when not written. */
public record ForeignKeyConstraint(Optional<Identifier> name, List<Identifier> columns,
                                   QualifiedName refTable, List<Identifier> refColumns,
                                   SourcePosition pos) implements TableConstraint {

    public ForeignKeyConstraint {
        columns = List.copyOf(columns);
        refColumns = List.copyOf(refColumns);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitForeignKeyConstraint(this);
    }
}
