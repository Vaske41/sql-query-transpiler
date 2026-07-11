package rs.etf.sqltranslator.ast;

/**
 * Walk-only visitor base with {@code null}/no-op defaults for every node type.
 * Analysis scanners (e.g. {@code CatalogBuilder}) subclass this and override only
 * the nodes they observe — without the identity-rebuild cost of {@link AstTransformer}.
 *
 * <p>Phase 4 AST→AST rules must subclass {@link AstTransformer}, not this class.
 */
public abstract class AbstractAstVisitor<R> implements AstVisitor<R> {

    /** Default result for un-overridden visits. */
    protected R defaultResult() {
        return null;
    }

    @Override
    public R visitScript(Script node) {
        for (Statement statement : node.statements()) {
            statement.accept(this);
        }
        return defaultResult();
    }

    @Override
    public R visitSelectStatement(SelectStatement node) {
        return defaultResult();
    }

    @Override
    public R visitQuery(Query node) {
        return defaultResult();
    }

    @Override
    public R visitUnionArm(UnionArm node) {
        return defaultResult();
    }

    @Override
    public R visitQuerySpecification(QuerySpecification node) {
        return defaultResult();
    }

    @Override
    public R visitRowLimit(RowLimit node) {
        return defaultResult();
    }

    @Override
    public R visitOrderItem(OrderItem node) {
        return defaultResult();
    }

    @Override
    public R visitSelectStar(SelectStar node) {
        return defaultResult();
    }

    @Override
    public R visitSelectExpr(SelectExpr node) {
        return defaultResult();
    }

    @Override
    public R visitTableSource(TableSource node) {
        return defaultResult();
    }

    @Override
    public R visitTableRef(TableRef node) {
        return defaultResult();
    }

    @Override
    public R visitJoin(Join node) {
        return defaultResult();
    }

    @Override
    public R visitInsertStatement(InsertStatement node) {
        return defaultResult();
    }

    @Override
    public R visitUpdateStatement(UpdateStatement node) {
        return defaultResult();
    }

    @Override
    public R visitAssignment(Assignment node) {
        return defaultResult();
    }

    @Override
    public R visitDeleteStatement(DeleteStatement node) {
        return defaultResult();
    }

    @Override
    public R visitCreateTableStatement(CreateTableStatement node) {
        return defaultResult();
    }

    @Override
    public R visitColumnDefinition(ColumnDefinition node) {
        return defaultResult();
    }

    @Override
    public R visitForeignKeyRef(ForeignKeyRef node) {
        return defaultResult();
    }

    @Override
    public R visitPrimaryKeyConstraint(PrimaryKeyConstraint node) {
        return defaultResult();
    }

    @Override
    public R visitUniqueConstraint(UniqueConstraint node) {
        return defaultResult();
    }

    @Override
    public R visitForeignKeyConstraint(ForeignKeyConstraint node) {
        return defaultResult();
    }

    @Override
    public R visitDropTableStatement(DropTableStatement node) {
        return defaultResult();
    }

    @Override
    public R visitAlterTableStatement(AlterTableStatement node) {
        return defaultResult();
    }

    @Override
    public R visitAddColumn(AddColumn node) {
        return defaultResult();
    }

    @Override
    public R visitDropColumn(DropColumn node) {
        return defaultResult();
    }

    @Override
    public R visitBinaryOp(BinaryOp node) {
        return defaultResult();
    }

    @Override
    public R visitUnaryOp(UnaryOp node) {
        return defaultResult();
    }

    @Override
    public R visitBetweenPredicate(BetweenPredicate node) {
        return defaultResult();
    }

    @Override
    public R visitLikePredicate(LikePredicate node) {
        return defaultResult();
    }

    @Override
    public R visitInListPredicate(InListPredicate node) {
        return defaultResult();
    }

    @Override
    public R visitInSubqueryPredicate(InSubqueryPredicate node) {
        return defaultResult();
    }

    @Override
    public R visitIsNullPredicate(IsNullPredicate node) {
        return defaultResult();
    }

    @Override
    public R visitExistsPredicate(ExistsPredicate node) {
        return defaultResult();
    }

    @Override
    public R visitFunctionCall(FunctionCall node) {
        return defaultResult();
    }

    @Override
    public R visitCaseExpression(CaseExpression node) {
        return defaultResult();
    }

    @Override
    public R visitWhenClause(WhenClause node) {
        return defaultResult();
    }

    @Override
    public R visitCastExpression(CastExpression node) {
        return defaultResult();
    }

    @Override
    public R visitSubqueryExpression(SubqueryExpression node) {
        return defaultResult();
    }

    @Override
    public R visitNumericLiteral(NumericLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitStringLiteral(StringLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitBooleanLiteral(BooleanLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitNullLiteral(NullLiteral node) {
        return defaultResult();
    }

    @Override
    public R visitIdentifier(Identifier node) {
        return defaultResult();
    }

    @Override
    public R visitQualifiedName(QualifiedName node) {
        return defaultResult();
    }

    @Override
    public R visitColumnRef(ColumnRef node) {
        return defaultResult();
    }

    @Override
    public R visitDataType(DataType node) {
        return defaultResult();
    }

    @Override
    public R visitFixedLength(FixedLength node) {
        return defaultResult();
    }

    @Override
    public R visitMaxLength(MaxLength node) {
        return defaultResult();
    }
}
