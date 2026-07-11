package rs.etf.sqltranslator.ast;

/** Length argument of a parameterized data type: a fixed width or MAX. */
public sealed interface TypeLength extends AstNode permits FixedLength, MaxLength {
}
