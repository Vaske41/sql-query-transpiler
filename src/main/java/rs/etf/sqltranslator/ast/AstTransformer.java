package rs.etf.sqltranslator.ast;

import java.util.List;
import java.util.Optional;

/**
 * Identity-rebuild base visitor (D10) — part of the frozen Phase 3 API. Every
 * {@code visitX} rebuilds the node from its recursively-accepted children and returns
 * it, so a Phase 4 rule subclasses this and overrides <em>only</em> the node types it
 * matches; nobody reimplements the ~50-method traversal or drifts into switch
 * dispatch (D1).
 *
 * <p>The single {@code Object} type parameter forces internal casts when reassembling
 * children — an accepted cost of D1, confined to this one base class and covered by
 * the corpus-wide identity test.
 */
public class AstTransformer implements AstVisitor<Object> {

    /** Applies this transformer to a whole script. */
    public final Script transform(Script script) {
        return rebuild(script);
    }

    /** Recursively transforms one child, casting the result back to the child's type. */
    @SuppressWarnings("unchecked")
    protected final <T extends AstNode> T rebuild(T node) {
        return (T) node.accept(this);
    }

    protected final <T extends AstNode> List<T> rebuildList(List<T> nodes) {
        return nodes.stream().map(this::rebuild).toList();
    }

    protected final <T extends AstNode> Optional<T> rebuildOptional(Optional<T> node) {
        return node.map(this::rebuild);
    }

    // --- statements and query shape ---

    @Override
    public Object visitScript(Script node) {
        return new Script(rebuildList(node.statements()), node.pos());
    }

    @Override
    public Object visitSelectStatement(SelectStatement node) {
        return new SelectStatement(rebuild(node.query()), node.pos());
    }

    @Override
    public Object visitQuery(Query node) {
        return new Query(rebuild(node.first()), rebuildList(node.unionArms()),
                rebuildList(node.orderBy()), rebuildOptional(node.limit()), node.pos());
    }

    @Override
    public Object visitUnionArm(UnionArm node) {
        return new UnionArm(node.all(), rebuild(node.spec()), node.pos());
    }

    @Override
    public Object visitQuerySpecification(QuerySpecification node) {
        return new QuerySpecification(node.quantifier(), rebuildList(node.items()),
                rebuildOptional(node.from()), rebuildOptional(node.where()),
                rebuildList(node.groupBy()), rebuildOptional(node.having()), node.pos());
    }

    @Override
    public Object visitRowLimit(RowLimit node) {
        return new RowLimit(rebuildOptional(node.count()), rebuildOptional(node.offset()),
                node.pos());
    }

    @Override
    public Object visitOrderItem(OrderItem node) {
        return new OrderItem(rebuild(node.expr()), node.direction(), node.nulls(), node.pos());
    }

    @Override
    public Object visitSelectStar(SelectStar node) {
        return new SelectStar(rebuildOptional(node.qualifier()), node.pos());
    }

    @Override
    public Object visitSelectExpr(SelectExpr node) {
        return new SelectExpr(rebuild(node.expr()), rebuildOptional(node.alias()), node.pos());
    }

    @Override
    public Object visitTableSource(TableSource node) {
        return new TableSource(rebuild(node.first()), rebuildList(node.joins()), node.pos());
    }

    @Override
    public Object visitTableRef(TableRef node) {
        return new TableRef(rebuild(node.table()), rebuildOptional(node.alias()), node.pos());
    }

    @Override
    public Object visitJoin(Join node) {
        return new Join(node.kind(), rebuild(node.table()), rebuildOptional(node.on()),
                node.pos());
    }

    // --- DML ---

    @Override
    public Object visitInsertStatement(InsertStatement node) {
        return new InsertStatement(rebuild(node.table()), rebuildList(node.columns()),
                node.rows().stream().map(this::rebuildList).toList(), node.pos());
    }

    @Override
    public Object visitUpdateStatement(UpdateStatement node) {
        return new UpdateStatement(rebuild(node.table()), rebuildList(node.assignments()),
                rebuildOptional(node.where()), node.pos());
    }

    @Override
    public Object visitAssignment(Assignment node) {
        return new Assignment(rebuild(node.column()), rebuild(node.value()), node.pos());
    }

    @Override
    public Object visitDeleteStatement(DeleteStatement node) {
        return new DeleteStatement(rebuild(node.table()), rebuildOptional(node.where()),
                node.pos());
    }

    // --- DDL ---

    @Override
    public Object visitCreateTableStatement(CreateTableStatement node) {
        return new CreateTableStatement(rebuild(node.table()), rebuildList(node.columns()),
                rebuildList(node.constraints()), node.pos());
    }

    @Override
    public Object visitColumnDefinition(ColumnDefinition node) {
        return new ColumnDefinition(rebuild(node.name()), rebuild(node.type()),
                node.autoIncrement(), node.nullable(),
                rebuildOptional(node.defaultValue()), node.primaryKey(), node.unique(),
                rebuildOptional(node.references()), node.pos());
    }

    @Override
    public Object visitForeignKeyRef(ForeignKeyRef node) {
        return new ForeignKeyRef(rebuild(node.table()), rebuildOptional(node.column()),
                node.pos());
    }

    @Override
    public Object visitPrimaryKeyConstraint(PrimaryKeyConstraint node) {
        return new PrimaryKeyConstraint(rebuildOptional(node.name()),
                rebuildList(node.columns()), node.pos());
    }

    @Override
    public Object visitUniqueConstraint(UniqueConstraint node) {
        return new UniqueConstraint(rebuildOptional(node.name()),
                rebuildList(node.columns()), node.pos());
    }

    @Override
    public Object visitForeignKeyConstraint(ForeignKeyConstraint node) {
        return new ForeignKeyConstraint(rebuildOptional(node.name()),
                rebuildList(node.columns()), rebuild(node.refTable()),
                rebuildList(node.refColumns()), node.pos());
    }

    @Override
    public Object visitDropTableStatement(DropTableStatement node) {
        return new DropTableStatement(rebuild(node.table()), node.ifExists(), node.pos());
    }

    @Override
    public Object visitAlterTableStatement(AlterTableStatement node) {
        return new AlterTableStatement(rebuild(node.table()), rebuild(node.action()),
                node.pos());
    }

    @Override
    public Object visitAddColumn(AddColumn node) {
        return new AddColumn(rebuild(node.column()), node.pos());
    }

    @Override
    public Object visitDropColumn(DropColumn node) {
        return new DropColumn(rebuild(node.column()), node.pos());
    }

    // --- expressions ---

    @Override
    public Object visitBinaryOp(BinaryOp node) {
        return new BinaryOp(node.op(), rebuild(node.left()), rebuild(node.right()),
                node.pos());
    }

    @Override
    public Object visitUnaryOp(UnaryOp node) {
        return new UnaryOp(node.op(), rebuild(node.operand()), node.pos());
    }

    @Override
    public Object visitBetweenPredicate(BetweenPredicate node) {
        return new BetweenPredicate(rebuild(node.value()), rebuild(node.low()),
                rebuild(node.high()), node.negated(), node.pos());
    }

    @Override
    public Object visitLikePredicate(LikePredicate node) {
        return new LikePredicate(rebuild(node.value()), rebuild(node.pattern()),
                node.negated(), node.pos());
    }

    @Override
    public Object visitInListPredicate(InListPredicate node) {
        return new InListPredicate(rebuild(node.value()), rebuildList(node.items()),
                node.negated(), node.pos());
    }

    @Override
    public Object visitInSubqueryPredicate(InSubqueryPredicate node) {
        return new InSubqueryPredicate(rebuild(node.value()), rebuild(node.subquery()),
                node.negated(), node.pos());
    }

    @Override
    public Object visitIsNullPredicate(IsNullPredicate node) {
        return new IsNullPredicate(rebuild(node.value()), node.negated(), node.pos());
    }

    @Override
    public Object visitExistsPredicate(ExistsPredicate node) {
        return new ExistsPredicate(rebuild(node.subquery()), node.pos());
    }

    @Override
    public Object visitFunctionCall(FunctionCall node) {
        return new FunctionCall(node.name(), rebuildList(node.args()), node.star(),
                node.quantifier(), node.pos());
    }

    @Override
    public Object visitCaseExpression(CaseExpression node) {
        return new CaseExpression(rebuildOptional(node.operand()), rebuildList(node.whens()),
                rebuildOptional(node.elseValue()), node.pos());
    }

    @Override
    public Object visitWhenClause(WhenClause node) {
        return new WhenClause(rebuild(node.condition()), rebuild(node.result()), node.pos());
    }

    @Override
    public Object visitCastExpression(CastExpression node) {
        return new CastExpression(rebuild(node.operand()), rebuild(node.targetType()),
                node.pos());
    }

    @Override
    public Object visitSubqueryExpression(SubqueryExpression node) {
        return new SubqueryExpression(rebuild(node.query()), node.pos());
    }

    // --- literals, identifiers, types (leaves rebuild to themselves) ---

    @Override
    public Object visitNumericLiteral(NumericLiteral node) {
        return node;
    }

    @Override
    public Object visitStringLiteral(StringLiteral node) {
        return node;
    }

    @Override
    public Object visitBooleanLiteral(BooleanLiteral node) {
        return node;
    }

    @Override
    public Object visitNullLiteral(NullLiteral node) {
        return node;
    }

    @Override
    public Object visitIdentifier(Identifier node) {
        return node;
    }

    @Override
    public Object visitQualifiedName(QualifiedName node) {
        return new QualifiedName(rebuildList(node.parts()), node.pos());
    }

    @Override
    public Object visitColumnRef(ColumnRef node) {
        return new ColumnRef(rebuild(node.name()), node.pos());
    }

    @Override
    public Object visitDataType(DataType node) {
        return new DataType(node.type(), rebuildOptional(node.length()), node.scale());
    }

    @Override
    public Object visitFixedLength(FixedLength node) {
        return node;
    }

    @Override
    public Object visitMaxLength(MaxLength node) {
        return node;
    }
}
