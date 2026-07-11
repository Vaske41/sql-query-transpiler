package rs.etf.sqltranslator.ast;

/** A top-level SQL statement. */
public sealed interface Statement extends AstNode
        permits SelectStatement, InsertStatement, UpdateStatement, DeleteStatement,
                CreateTableStatement, DropTableStatement, AlterTableStatement {
}
