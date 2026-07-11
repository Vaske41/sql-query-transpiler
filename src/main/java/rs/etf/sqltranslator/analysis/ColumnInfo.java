package rs.etf.sqltranslator.analysis;

import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.Identifier;

/** One column of a cataloged table: name, folded type, auto-increment flag. */
public record ColumnInfo(Identifier name, DataType type, boolean autoIncrement) {
}
