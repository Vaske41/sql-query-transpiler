package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** DELETE FROM ... [WHERE ...]. */
public record DeleteStatement(QualifiedName table, Optional<Expression> where,
                              SourcePosition pos) implements Statement {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDeleteStatement(this);
    }
}
