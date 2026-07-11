package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/**
 * A numeric literal keeping its lexical text (D5) so {@code 9.99} reaches the output
 * byte-identically; exponent forms ({@code 1e5}, {@code 2.5E-3}) included.
 * {@code decimal} mirrors the INTEGER_LITERAL vs DECIMAL_LITERAL token distinction.
 */
public record NumericLiteral(String text, boolean decimal, SourcePosition pos)
        implements Literal {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitNumericLiteral(this);
    }
}
