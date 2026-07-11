package rs.etf.sqltranslator.ast;

import java.util.List;
import java.util.Optional;

/**
 * Canonical, <em>position-free</em>, indentation-structured debug dump of an AST.
 * Because positions are excluded by design, two scripts that build the same logical
 * AST from different dialect files dump identically — the cross-dialect equality
 * test and the round-trip smoke tests rely on exactly that property.
 *
 * <p>Format: one header line per node ({@code NodeName key=value ...} for scalar
 * components), then one indented {@code label: <child>} block per composite
 * component. Empty optionals and empty lists are omitted; list elements are
 * labeled {@code label[i]} so shapes can never collide.
 */
public final class AstDumper implements AstVisitor<String> {

    private static final String INDENT = "  ";

    public String dump(AstNode node) {
        return node.accept(this);
    }

    // --- statements and query shape ---

    @Override
    public String visitScript(Script node) {
        return node("Script").children("statements", node.statements()).done();
    }

    @Override
    public String visitSelectStatement(SelectStatement node) {
        return node("SelectStatement").child("query", node.query()).done();
    }

    @Override
    public String visitQuery(Query node) {
        return node("Query")
                .child("first", node.first())
                .children("unionArms", node.unionArms())
                .children("orderBy", node.orderBy())
                .child("limit", node.limit())
                .done();
    }

    @Override
    public String visitUnionArm(UnionArm node) {
        return node("UnionArm all=" + node.all()).child("spec", node.spec()).done();
    }

    @Override
    public String visitQuerySpecification(QuerySpecification node) {
        return node("QuerySpecification" + optional("quantifier", node.quantifier()))
                .children("items", node.items())
                .child("from", node.from())
                .child("where", node.where())
                .children("groupBy", node.groupBy())
                .child("having", node.having())
                .done();
    }

    @Override
    public String visitRowLimit(RowLimit node) {
        return node("RowLimit")
                .child("count", node.count())
                .child("offset", node.offset())
                .done();
    }

    @Override
    public String visitOrderItem(OrderItem node) {
        return node("OrderItem direction=" + node.direction() + optional("nulls", node.nulls()))
                .child("expr", node.expr())
                .done();
    }

    @Override
    public String visitSelectStar(SelectStar node) {
        return node("SelectStar").child("qualifier", node.qualifier()).done();
    }

    @Override
    public String visitSelectExpr(SelectExpr node) {
        return node("SelectExpr")
                .child("expr", node.expr())
                .child("alias", node.alias())
                .done();
    }

    @Override
    public String visitTableSource(TableSource node) {
        return node("TableSource")
                .child("first", node.first())
                .children("joins", node.joins())
                .done();
    }

    @Override
    public String visitTableRef(TableRef node) {
        return node("TableRef")
                .child("table", node.table())
                .child("alias", node.alias())
                .done();
    }

    @Override
    public String visitJoin(Join node) {
        return node("Join kind=" + node.kind())
                .child("table", node.table())
                .child("on", node.on())
                .done();
    }

    // --- DML ---

    @Override
    public String visitInsertStatement(InsertStatement node) {
        Dump dump = node("InsertStatement")
                .child("table", node.table())
                .children("columns", node.columns());
        List<List<Expression>> rows = node.rows();
        for (int i = 0; i < rows.size(); i++) {
            List<Expression> row = rows.get(i);
            for (int j = 0; j < row.size(); j++) {
                dump.child("rows[" + i + "][" + j + "]", row.get(j));
            }
        }
        return dump.done();
    }

    @Override
    public String visitUpdateStatement(UpdateStatement node) {
        return node("UpdateStatement")
                .child("table", node.table())
                .children("assignments", node.assignments())
                .child("where", node.where())
                .done();
    }

    @Override
    public String visitAssignment(Assignment node) {
        return node("Assignment")
                .child("column", node.column())
                .child("value", node.value())
                .done();
    }

    @Override
    public String visitDeleteStatement(DeleteStatement node) {
        return node("DeleteStatement")
                .child("table", node.table())
                .child("where", node.where())
                .done();
    }

    // --- DDL ---

    @Override
    public String visitCreateTableStatement(CreateTableStatement node) {
        return node("CreateTableStatement")
                .child("table", node.table())
                .children("columns", node.columns())
                .children("constraints", node.constraints())
                .done();
    }

    @Override
    public String visitColumnDefinition(ColumnDefinition node) {
        String header = "ColumnDefinition autoIncrement=" + node.autoIncrement()
                + " primaryKey=" + node.primaryKey()
                + " unique=" + node.unique()
                + node.nullable().map(n -> " nullable=" + n).orElse("");
        return node(header)
                .child("name", node.name())
                .child("type", node.type())
                .child("defaultValue", node.defaultValue())
                .child("references", node.references())
                .done();
    }

    @Override
    public String visitForeignKeyRef(ForeignKeyRef node) {
        return node("ForeignKeyRef")
                .child("table", node.table())
                .child("column", node.column())
                .done();
    }

    @Override
    public String visitPrimaryKeyConstraint(PrimaryKeyConstraint node) {
        return node("PrimaryKeyConstraint")
                .child("name", node.name())
                .children("columns", node.columns())
                .done();
    }

    @Override
    public String visitUniqueConstraint(UniqueConstraint node) {
        return node("UniqueConstraint")
                .child("name", node.name())
                .children("columns", node.columns())
                .done();
    }

    @Override
    public String visitForeignKeyConstraint(ForeignKeyConstraint node) {
        return node("ForeignKeyConstraint")
                .child("name", node.name())
                .children("columns", node.columns())
                .child("refTable", node.refTable())
                .children("refColumns", node.refColumns())
                .done();
    }

    @Override
    public String visitDropTableStatement(DropTableStatement node) {
        return node("DropTableStatement ifExists=" + node.ifExists())
                .child("table", node.table())
                .done();
    }

    @Override
    public String visitAlterTableStatement(AlterTableStatement node) {
        return node("AlterTableStatement")
                .child("table", node.table())
                .child("action", node.action())
                .done();
    }

    @Override
    public String visitAddColumn(AddColumn node) {
        return node("AddColumn").child("column", node.column()).done();
    }

    @Override
    public String visitDropColumn(DropColumn node) {
        return node("DropColumn").child("column", node.column()).done();
    }

    // --- expressions ---

    @Override
    public String visitBinaryOp(BinaryOp node) {
        return node("BinaryOp op=" + node.op())
                .child("left", node.left())
                .child("right", node.right())
                .done();
    }

    @Override
    public String visitUnaryOp(UnaryOp node) {
        return node("UnaryOp op=" + node.op()).child("operand", node.operand()).done();
    }

    @Override
    public String visitBetweenPredicate(BetweenPredicate node) {
        return node("BetweenPredicate negated=" + node.negated())
                .child("value", node.value())
                .child("low", node.low())
                .child("high", node.high())
                .done();
    }

    @Override
    public String visitLikePredicate(LikePredicate node) {
        return node("LikePredicate negated=" + node.negated())
                .child("value", node.value())
                .child("pattern", node.pattern())
                .done();
    }

    @Override
    public String visitInListPredicate(InListPredicate node) {
        return node("InListPredicate negated=" + node.negated())
                .child("value", node.value())
                .children("items", node.items())
                .done();
    }

    @Override
    public String visitInSubqueryPredicate(InSubqueryPredicate node) {
        return node("InSubqueryPredicate negated=" + node.negated())
                .child("value", node.value())
                .child("subquery", node.subquery())
                .done();
    }

    @Override
    public String visitIsNullPredicate(IsNullPredicate node) {
        return node("IsNullPredicate negated=" + node.negated())
                .child("value", node.value())
                .done();
    }

    @Override
    public String visitExistsPredicate(ExistsPredicate node) {
        return node("ExistsPredicate").child("subquery", node.subquery()).done();
    }

    @Override
    public String visitFunctionCall(FunctionCall node) {
        return node("FunctionCall name=" + node.name() + " star=" + node.star()
                + optional("quantifier", node.quantifier()))
                .children("args", node.args())
                .done();
    }

    @Override
    public String visitCaseExpression(CaseExpression node) {
        return node("CaseExpression")
                .child("operand", node.operand())
                .children("whens", node.whens())
                .child("else", node.elseValue())
                .done();
    }

    @Override
    public String visitWhenClause(WhenClause node) {
        return node("WhenClause")
                .child("condition", node.condition())
                .child("result", node.result())
                .done();
    }

    @Override
    public String visitCastExpression(CastExpression node) {
        return node("CastExpression")
                .child("operand", node.operand())
                .child("targetType", node.targetType())
                .done();
    }

    @Override
    public String visitSubqueryExpression(SubqueryExpression node) {
        return node("SubqueryExpression").child("query", node.query()).done();
    }

    // --- literals, identifiers, types ---

    @Override
    public String visitNumericLiteral(NumericLiteral node) {
        return "NumericLiteral text=" + node.text() + " decimal=" + node.decimal();
    }

    @Override
    public String visitStringLiteral(StringLiteral node) {
        return "StringLiteral value=" + quote(node.value()) + " national=" + node.national();
    }

    @Override
    public String visitBooleanLiteral(BooleanLiteral node) {
        return "BooleanLiteral value=" + node.value();
    }

    @Override
    public String visitNullLiteral(NullLiteral node) {
        return "NullLiteral";
    }

    @Override
    public String visitIdentifier(Identifier node) {
        return "Identifier value=" + quote(node.value()) + " quoted=" + node.quoted();
    }

    @Override
    public String visitQualifiedName(QualifiedName node) {
        return node("QualifiedName").children("parts", node.parts()).done();
    }

    @Override
    public String visitColumnRef(ColumnRef node) {
        return node("ColumnRef").child("name", node.name()).done();
    }

    @Override
    public String visitDataType(DataType node) {
        return node("DataType type=" + node.type()
                + node.scale().map(s -> " scale=" + s).orElse(""))
                .child("length", node.length())
                .done();
    }

    @Override
    public String visitFixedLength(FixedLength node) {
        return "FixedLength value=" + node.value();
    }

    @Override
    public String visitMaxLength(MaxLength node) {
        return "MaxLength";
    }

    // --- helpers ---

    private Dump node(String header) {
        return new Dump(header);
    }

    private static String optional(String key, Optional<? extends Enum<?>> value) {
        return value.map(v -> " " + key + "=" + v.name()).orElse("");
    }

    /** Quotes a string value keeping the dump line-structured and unambiguous. */
    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
    }

    /** Accumulates one node's header plus indented, labeled child blocks. */
    private final class Dump {

        private final StringBuilder sb;

        Dump(String header) {
            sb = new StringBuilder(header);
        }

        Dump child(String label, AstNode child) {
            sb.append('\n').append(indent(label + ": " + child.accept(AstDumper.this)));
            return this;
        }

        Dump child(String label, Optional<? extends AstNode> child) {
            child.ifPresent(c -> child(label, c));
            return this;
        }

        Dump children(String label, List<? extends AstNode> children) {
            for (int i = 0; i < children.size(); i++) {
                child(label + "[" + i + "]", children.get(i));
            }
            return this;
        }

        String done() {
            return sb.toString();
        }

        private String indent(String block) {
            return INDENT + block.replace("\n", "\n" + INDENT);
        }
    }
}
