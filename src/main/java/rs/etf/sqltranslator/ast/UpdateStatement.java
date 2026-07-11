package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/** UPDATE ... SET ... [WHERE ...]. */
public record UpdateStatement(QualifiedName table, List<Assignment> assignments,
                              Optional<Expression> where, SourcePosition pos)
        implements Statement {

    public UpdateStatement {
        assignments = List.copyOf(assignments);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUpdateStatement(this);
    }
}
