package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/**
 * A string literal holding the <em>unescaped</em> value; {@code national} marks
 * T-SQL {@code N'...'}.
 */
public record StringLiteral(String value, boolean national, SourcePosition pos)
        implements Literal {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitStringLiteral(this);
    }
}
