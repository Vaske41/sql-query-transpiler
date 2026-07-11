package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** TRUE/FALSE — reachable only from MySQL and PostgreSQL sources at parse time. */
public record BooleanLiteral(boolean value, SourcePosition pos) implements Literal {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitBooleanLiteral(this);
    }
}
