package rs.etf.sqltranslator.ast;

/** One item of a SELECT list. */
public sealed interface SelectItem extends AstNode permits SelectStar, SelectExpr {
}
