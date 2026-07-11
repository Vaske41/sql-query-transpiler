package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.TableSchema;
import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CaseExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.NullLiteral;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UnaryOp;
import rs.etf.sqltranslator.ast.UnaryOperator;
import rs.etf.sqltranslator.ast.WhenClause;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * Boolean semantics across the three dialects (ROADMAP Phase 4): T-SQL has no
 * boolean literals or bare-boolean predicates; PostgreSQL demands real booleans in
 * predicate positions and refuses integer stand-ins. Bare expressions in boolean
 * contexts get an explicit truthiness comparison ({@code <> 0} — faithful to the
 * MySQL/T-SQL "nonzero is true" convention and identical for 0/1 booleans);
 * catalog-resolved BOOLEAN columns keep/regain idiomatic PG forms.
 */
public final class RewriteBooleanSemanticsRule implements Rule {

    @Override
    public String name() {
        return "rewrite-boolean-semantics";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Rewriter(ctx).transform(script);
    }

    private static final class Rewriter extends ScopedTransformer {

        private Rewriter(TranslationContext ctx) {
            super(ctx);
        }

        // --- literal conversion (T-SQL has no TRUE/FALSE) ---

        @Override
        public Object visitBooleanLiteral(BooleanLiteral node) {
            if (ctx.target() == Dialect.TSQL) {
                return new NumericLiteral(node.value() ? "1" : "0", false, node.pos());
            }
            return node;
        }

        // --- boolean contexts: WHERE / HAVING / JOIN ON / searched-CASE WHEN ---

        @Override
        protected Object afterQuerySpecification(QuerySpecification spec) {
            Optional<TableSource> from = spec.from().map(f -> new TableSource(
                    f.first(),
                    f.joins().stream().map(j -> new Join(j.kind(), j.table(),
                            j.on().map(this::bool), j.pos())).toList(),
                    f.pos()));
            return new QuerySpecification(spec.quantifier(), spec.items(), from,
                    spec.where().map(this::bool), spec.groupBy(),
                    spec.having().map(this::bool), spec.pos());
        }

        @Override
        public Object visitCaseExpression(CaseExpression node) {
            CaseExpression rebuilt = (CaseExpression) super.visitCaseExpression(node);
            if (rebuilt.operand().isPresent()) {
                return rebuilt;                       // simple CASE: WHENs are values
            }
            List<WhenClause> whens = rebuilt.whens().stream()
                    .map(w -> new WhenClause(bool(w.condition()), w.result(), w.pos()))
                    .toList();
            return new CaseExpression(rebuilt.operand(), whens, rebuilt.elseValue(),
                    rebuilt.pos());
        }

        /** Makes an expression legal in a boolean context for the target. */
        private Expression bool(Expression expr) {
            if (expr instanceof BinaryOp op) {
                if (op.op() == BinaryOperator.AND || op.op() == BinaryOperator.OR) {
                    return new BinaryOp(op.op(), bool(op.left()), bool(op.right()), op.pos());
                }
                return op;                            // comparisons are already predicates
            }
            if (expr instanceof UnaryOp op && op.op() == UnaryOperator.NOT) {
                return new UnaryOp(UnaryOperator.NOT, bool(op.operand()), op.pos());
            }
            if (expr instanceof rs.etf.sqltranslator.ast.BetweenPredicate
                    || expr instanceof rs.etf.sqltranslator.ast.LikePredicate
                    || expr instanceof rs.etf.sqltranslator.ast.InListPredicate
                    || expr instanceof rs.etf.sqltranslator.ast.InSubqueryPredicate
                    || expr instanceof rs.etf.sqltranslator.ast.IsNullPredicate
                    || expr instanceof rs.etf.sqltranslator.ast.ExistsPredicate) {
                return expr;
            }
            if (expr instanceof BooleanLiteral) {
                return expr;                          // PG/MySQL targets: bare is legal
            }                                         // (T-SQL: already a NumericLiteral)
            Optional<TypeFamily> family = familyOf(expr);
            boolean resolvedBoolean = family.filter(f -> f == TypeFamily.BOOLEAN).isPresent();
            if (ctx.target() == Dialect.POSTGRESQL && resolvedBoolean) {
                return expr;                          // bare boolean is idiomatic PG
            }
            if (ctx.target() == Dialect.POSTGRESQL && family.isEmpty()) {
                SourcePosition pos = exprPos(expr);
                ctx.report().warn("BOOLEAN_CONTEXT_UNRESOLVED",
                        "bare expression in boolean context has unknown type; "
                                + "assumed numeric truthiness (<> 0)", pos);
            }
            SourcePosition pos = exprPos(expr);
            return new BinaryOp(BinaryOperator.NEQ, expr,
                    new NumericLiteral("0", false, pos), pos);
        }

        // --- PG target: boolean-column comparisons and assignments ---

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (ctx.target() != Dialect.POSTGRESQL
                    || (op.op() != BinaryOperator.EQ && op.op() != BinaryOperator.NEQ)) {
                return op;
            }
            Expression column = booleanColumn(op.left()) ? op.left()
                    : booleanColumn(op.right()) ? op.right() : null;
            Expression other = column == op.left() ? op.right() : op.left();
            if (column == null || !(other instanceof NumericLiteral literal)
                    || !(literal.text().equals("0") || literal.text().equals("1"))) {
                return op;
            }
            boolean truthy = literal.text().equals("1") == (op.op() == BinaryOperator.EQ);
            return truthy ? column
                    : new UnaryOp(UnaryOperator.NOT, column, op.pos());
        }

        @Override
        public Object visitColumnDefinition(ColumnDefinition node) {
            ColumnDefinition column = (ColumnDefinition) super.visitColumnDefinition(node);
            if (ctx.target() != Dialect.POSTGRESQL
                    || column.type().type() != GenericType.BOOLEAN) {
                return column;
            }
            Optional<Expression> harmonized = column.defaultValue().map(this::asBooleanLiteral);
            return new ColumnDefinition(column.name(), column.type(), column.autoIncrement(),
                    column.nullable(), harmonized, column.primaryKey(), column.unique(),
                    column.references(), column.pos());
        }

        @Override
        protected Object afterInsertStatement(InsertStatement insert) {
            if (ctx.target() != Dialect.POSTGRESQL) {
                return insert;
            }
            Optional<TableSchema> schema =
                    ctx.catalog().table(insert.table().last().value());
            if (schema.isEmpty()) {
                return insert;
            }
            List<List<Expression>> rows = insert.rows().stream()
                    .map(row -> harmonizeRow(row, insert.columns(), schema.get()))
                    .toList();
            return new InsertStatement(insert.table(), insert.columns(), rows, insert.pos());
        }

        @Override
        public Object visitAssignment(Assignment node) {
            Assignment assignment = (Assignment) super.visitAssignment(node);
            if (ctx.target() != Dialect.POSTGRESQL) {
                return assignment;
            }
            ColumnRef ref = syntheticRef(assignment.column());
            boolean isBoolean = resolve(ref)
                    .filter(c -> c.type().type() == GenericType.BOOLEAN).isPresent();
            if (!isBoolean) {
                return assignment;
            }
            return new Assignment(assignment.column(),
                    asBooleanLiteral(assignment.value()), assignment.pos());
        }

        // --- helpers ---

        private List<Expression> harmonizeRow(List<Expression> row,
                                              List<Identifier> namedColumns,
                                              TableSchema schema) {
            List<Expression> out = new java.util.ArrayList<>(row.size());
            for (int i = 0; i < row.size(); i++) {
                GenericType type = columnTypeAt(i, namedColumns, schema);
                out.add(type == GenericType.BOOLEAN
                        ? asBooleanLiteral(row.get(i)) : row.get(i));
            }
            return out;
        }

        /** Column type at value position i: named list if present, else declaration order. */
        private GenericType columnTypeAt(int i, List<Identifier> namedColumns,
                                         TableSchema schema) {
            if (!namedColumns.isEmpty()) {
                return i < namedColumns.size()
                        ? schema.column(namedColumns.get(i).value())
                                .map(c -> c.type().type()).orElse(null)
                        : null;
            }
            return i < schema.columns().size()
                    ? schema.columns().get(i).type().type() : null;
        }

        private Expression asBooleanLiteral(Expression expr) {
            if (expr instanceof NumericLiteral literal) {
                if (literal.text().equals("1")) {
                    return new BooleanLiteral(true, literal.pos());
                }
                if (literal.text().equals("0")) {
                    return new BooleanLiteral(false, literal.pos());
                }
            }
            return expr;
        }

        private boolean booleanColumn(Expression expr) {
            return expr instanceof ColumnRef ref && resolve(ref)
                    .filter(c -> c.type().type() == GenericType.BOOLEAN).isPresent();
        }

        /** Wraps an assignment's bare Identifier as a ColumnRef so resolve() applies. */
        private static ColumnRef syntheticRef(Identifier column) {
            SourcePosition pos = column.pos();
            return new ColumnRef(new QualifiedName(List.of(column), pos), pos);
        }

        private static SourcePosition exprPos(Expression expr) {
            if (expr instanceof ColumnRef ref) {
                return ref.pos();
            }
            if (expr instanceof NumericLiteral n) {
                return n.pos();
            }
            if (expr instanceof BooleanLiteral b) {
                return b.pos();
            }
            if (expr instanceof BinaryOp op) {
                return op.pos();
            }
            if (expr instanceof UnaryOp op) {
                return op.pos();
            }
            if (expr instanceof NullLiteral nullLit) {
                return nullLit.pos();
            }
            if (expr instanceof rs.etf.sqltranslator.ast.FunctionCall call) {
                return call.pos();
            }
            if (expr instanceof rs.etf.sqltranslator.ast.CastExpression cast) {
                return cast.pos();
            }
            if (expr instanceof rs.etf.sqltranslator.ast.SubqueryExpression sub) {
                return sub.pos();
            }
            if (expr instanceof CaseExpression c) {
                return c.pos();
            }
            throw new IllegalArgumentException("no position on expression: " + expr.getClass());
        }
    }
}
