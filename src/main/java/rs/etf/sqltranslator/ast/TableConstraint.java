package rs.etf.sqltranslator.ast;

/** A table-level constraint in CREATE TABLE. */
public sealed interface TableConstraint extends AstNode
        permits PrimaryKeyConstraint, UniqueConstraint, ForeignKeyConstraint {
}
