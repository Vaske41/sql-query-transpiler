package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.ColumnInfo;
import rs.etf.sqltranslator.ast.BetweenPredicate;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.InListPredicate;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * The flagship catalog rewrite (ROADMAP Phase 4): where the source dialect converts
 * implicitly but PostgreSQL would error, wrap the literal side of a comparison in an
 * explicit CAST to the catalog-resolved column's type. Decidable cases only —
 * unresolved columns are left untouched (documented boundary); T-SQL targets get a
 * warning instead of a rewrite (T-SQL converts implicitly, but by different
 * precedence rules worth surfacing).
 */
public final class InsertCastsRule implements Rule {

    @Override
    public String name() {
        return "insert-casts";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() == Dialect.MYSQL) {
            return script;                       // MySQL converts implicitly; nothing to do
        }
        return new Caster(ctx).transform(script);
    }

    private static final class Caster extends ScopedTransformer {

        private static final List<BinaryOperator> COMPARISONS =
                List.of(BinaryOperator.EQ,
                        BinaryOperator.NEQ,
                        BinaryOperator.LT,
                        BinaryOperator.LTE,
                        BinaryOperator.GT,
                        BinaryOperator.GTE);

        private Caster(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (!COMPARISONS.contains(op.op())) {
                return op;
            }
            Optional<ColumnInfo> leftColumn = columnOf(op.left());
            Optional<ColumnInfo> rightColumn = columnOf(op.right());
            if (leftColumn.isPresent() && isCastableLiteral(op.right())) {
                return new BinaryOp(op.op(), op.left(),
                        harmonize(op.right(), leftColumn.get()), op.pos());
            }
            if (rightColumn.isPresent() && isCastableLiteral(op.left())) {
                return new BinaryOp(op.op(),
                        harmonize(op.left(), rightColumn.get()), op.right(), op.pos());
            }
            return op;
        }

        @Override
        public Object visitBetweenPredicate(BetweenPredicate node) {
            BetweenPredicate between = (BetweenPredicate) super.visitBetweenPredicate(node);
            return columnOf(between.value())
                    .map(column -> (Object) new BetweenPredicate(between.value(),
                            harmonize(between.low(), column),
                            harmonize(between.high(), column),
                            between.negated(), between.pos()))
                    .orElse(between);
        }

        @Override
        public Object visitInListPredicate(InListPredicate node) {
            InListPredicate in = (InListPredicate) super.visitInListPredicate(node);
            return columnOf(in.value())
                    .map(column -> (Object) new InListPredicate(in.value(),
                            in.items().stream().map(item -> harmonize(item, column)).toList(),
                            in.negated(), in.pos()))
                    .orElse(in);
        }

        /** The expression's catalog column, if it is a resolved ColumnRef. */
        private Optional<ColumnInfo> columnOf(Expression expr) {
            return expr instanceof ColumnRef ref ? resolve(ref) : Optional.empty();
        }

        private static boolean isCastableLiteral(Expression expr) {
            return expr instanceof NumericLiteral || expr instanceof StringLiteral;
        }

        /** Wraps a mismatched literal in CAST (PG) or just warns (T-SQL). */
        private Expression harmonize(Expression literal, ColumnInfo column) {
            if (!isCastableLiteral(literal)) {
                return literal;
            }
            SourcePosition pos = literalPos(literal);
            TypeFamily columnFamily = TypeFamily.of(column.type().type());
            TypeFamily literalFamily = familyOf(literal).orElseThrow();
            if (literalFamily == columnFamily) {
                return literal;
            }
            if (columnFamily == TypeFamily.BOOLEAN && literal instanceof NumericLiteral n
                    && (n.text().equals("0") || n.text().equals("1"))) {
                return literal;                       // leave for RewriteBooleanSemanticsRule
            }
            if (ctx.target() == Dialect.TSQL) {
                ctx.report().warn("IMPLICIT_CONVERSION",
                        "comparison of " + literalFamily + " literal with " + columnFamily
                                + " column '" + column.name().value()
                                + "' relies on implicit conversion", pos);
                return literal;
            }
            ctx.report().warn("CAST_INSERTED",
                    "explicit CAST inserted: " + literalFamily + " literal compared with "
                            + columnFamily + " column '" + column.name().value() + "'",
                    pos);
            return new CastExpression(literal, column.type(), pos);
        }

        private static SourcePosition literalPos(Expression literal) {
            if (literal instanceof NumericLiteral n) {
                return n.pos();
            }
            if (literal instanceof StringLiteral s) {
                return s.pos();
            }
            throw new IllegalArgumentException("not a castable literal");
        }
    }
}
