package rs.etf.sqltranslator.ast;

/** The single action of an ALTER TABLE statement. */
public sealed interface AlterAction extends AstNode permits AddColumn, DropColumn {
}
