package rs.etf.sqltranslator.ast;

/**
 * Classic GoF Visitor over the frozen Phase 3 node set — one method per record type
 * in {@code ast/} (only {@code SourcePosition} and the enums are not visitable).
 * Used by analysis, transformation, and code generation alike.
 */
public interface AstVisitor<R> {

    // --- statements and query shape ---
    R visitScript(Script node);

    R visitSelectStatement(SelectStatement node);

    R visitQuery(Query node);

    R visitUnionArm(UnionArm node);

    R visitQuerySpecification(QuerySpecification node);

    R visitRowLimit(RowLimit node);

    R visitOrderItem(OrderItem node);

    R visitSelectStar(SelectStar node);

    R visitSelectExpr(SelectExpr node);

    R visitTableSource(TableSource node);

    R visitTableRef(TableRef node);

    R visitJoin(Join node);

    // --- DML ---
    R visitInsertStatement(InsertStatement node);

    R visitUpdateStatement(UpdateStatement node);

    R visitAssignment(Assignment node);

    R visitDeleteStatement(DeleteStatement node);

    // --- DDL ---
    R visitCreateTableStatement(CreateTableStatement node);

    R visitColumnDefinition(ColumnDefinition node);

    R visitForeignKeyRef(ForeignKeyRef node);

    R visitPrimaryKeyConstraint(PrimaryKeyConstraint node);

    R visitUniqueConstraint(UniqueConstraint node);

    R visitForeignKeyConstraint(ForeignKeyConstraint node);

    R visitDropTableStatement(DropTableStatement node);

    R visitAlterTableStatement(AlterTableStatement node);

    R visitAddColumn(AddColumn node);

    R visitDropColumn(DropColumn node);

    // --- expressions ---
    R visitBinaryOp(BinaryOp node);

    R visitUnaryOp(UnaryOp node);

    R visitBetweenPredicate(BetweenPredicate node);

    R visitLikePredicate(LikePredicate node);

    R visitInListPredicate(InListPredicate node);

    R visitInSubqueryPredicate(InSubqueryPredicate node);

    R visitIsNullPredicate(IsNullPredicate node);

    R visitExistsPredicate(ExistsPredicate node);

    R visitFunctionCall(FunctionCall node);

    R visitCaseExpression(CaseExpression node);

    R visitWhenClause(WhenClause node);

    R visitCastExpression(CastExpression node);

    R visitSubqueryExpression(SubqueryExpression node);

    // --- literals, identifiers, types ---
    R visitNumericLiteral(NumericLiteral node);

    R visitStringLiteral(StringLiteral node);

    R visitBooleanLiteral(BooleanLiteral node);

    R visitNullLiteral(NullLiteral node);

    R visitIdentifier(Identifier node);

    R visitQualifiedName(QualifiedName node);

    R visitColumnRef(ColumnRef node);

    R visitDataType(DataType node);

    R visitFixedLength(FixedLength node);

    R visitMaxLength(MaxLength node);
}
