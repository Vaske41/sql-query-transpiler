package rs.etf.sqltranslator.ast;

/** A literal value. */
public sealed interface Literal extends Expression
        permits NumericLiteral, StringLiteral, BooleanLiteral, NullLiteral {
}
