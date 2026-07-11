package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/** Simple ({@code CASE x WHEN ...}) or searched ({@code CASE WHEN ...}) CASE. */
public record CaseExpression(Optional<Expression> operand, List<WhenClause> whens,
                             Optional<Expression> elseValue, SourcePosition pos)
        implements Expression {

    public CaseExpression {
        whens = List.copyOf(whens);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCaseExpression(this);
    }
}
