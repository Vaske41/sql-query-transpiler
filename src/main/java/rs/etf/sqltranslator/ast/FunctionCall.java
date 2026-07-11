package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * A function call. {@code name} is uppercased at build time (function names are
 * case-insensitive in all three dialects); {@code star} marks {@code COUNT(*)} and
 * {@code quantifier} carries {@code COUNT(DISTINCT x)}.
 */
public record FunctionCall(String name, List<Expression> args, boolean star,
                           Optional<SetQuantifier> quantifier, SourcePosition pos)
        implements Expression {

    public FunctionCall {
        args = List.copyOf(args);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitFunctionCall(this);
    }
}
