package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/** One SELECT block: items, FROM, WHERE, GROUP BY, HAVING. */
public record QuerySpecification(Optional<SetQuantifier> quantifier, List<SelectItem> items,
                                 Optional<TableSource> from, Optional<Expression> where,
                                 List<Expression> groupBy, Optional<Expression> having,
                                 SourcePosition pos) implements AstNode {

    public QuerySpecification {
        items = List.copyOf(items);
        groupBy = List.copyOf(groupBy);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitQuerySpecification(this);
    }
}
