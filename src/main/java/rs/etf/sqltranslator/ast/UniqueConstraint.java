package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/** Table-level UNIQUE constraint. */
public record UniqueConstraint(Optional<Identifier> name, List<Identifier> columns,
                               SourcePosition pos) implements TableConstraint {

    public UniqueConstraint {
        columns = List.copyOf(columns);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUniqueConstraint(this);
    }
}
