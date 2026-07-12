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
 * explicit CAST to the catalog-resolved column's type. Decidable cases only.
 * Literal-vs-{@code ColumnRef} shapes whose column does not resolve emit
 * {@code CAST_UNRESOLVED} and are left untouched — detectable incompleteness, not a
 * silent skip. T-SQL targets warn ({@code IMPLICIT_CONVERSION}) instead of rewriting.
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
            if (op.left() instanceof ColumnRef leftRef && isCastableLiteral(op.right())) {
                return resolve(leftRef)
                        .map(column -> (Object) new BinaryOp(op.op(), op.left(),
                                harmonize(op.right(), column), op.pos()))
                        .orElseGet(() -> {
                            warnUnresolved(leftRef, op.pos());
                            return op;
                        });
            }
            if (op.right() instanceof ColumnRef rightRef && isCastableLiteral(op.left())) {
                return resolve(rightRef)
                        .map(column -> (Object) new BinaryOp(op.op(),
                                harmonize(op.left(), column), op.right(), op.pos()))
                        .orElseGet(() -> {
                            warnUnresolved(rightRef, op.pos());
                            return op;
                        });
            }
            return op;
        }

        @Override
        public Object visitBetweenPredicate(BetweenPredicate node) {
            BetweenPredicate between = (BetweenPredicate) super.visitBetweenPredicate(node);
            if (!(between.value() instanceof ColumnRef ref)) {
                return between;
            }
            return resolve(ref)
                    .map(column -> (Object) new BetweenPredicate(between.value(),
                            harmonize(between.low(), column),
                            harmonize(between.high(), column),
                            between.negated(), between.pos()))
                    .orElseGet(() -> {
                        if (isCastableLiteral(between.low()) || isCastableLiteral(between.high())) {
                            warnUnresolved(ref, between.pos());
                        }
                        return between;
                    });
        }

        @Override
        public Object visitInListPredicate(InListPredicate node) {
            InListPredicate in = (InListPredicate) super.visitInListPredicate(node);
            if (!(in.value() instanceof ColumnRef ref)) {
                return in;
            }
            return resolve(ref)
                    .map(column -> (Object) new InListPredicate(in.value(),
                            in.items().stream().map(item -> harmonize(item, column)).toList(),
                            in.negated(), in.pos()))
                    .orElseGet(() -> {
                        if (in.items().stream().anyMatch(InsertCastsRule.Caster::isCastableLiteral)) {
                            warnUnresolved(ref, in.pos());
                        }
                        return in;
                    });
        }

        private void warnUnresolved(ColumnRef ref, SourcePosition pos) {
            ctx.report().warn("CAST_UNRESOLVED",
                    "column '" + ref.name().last().value()
                            + "' did not resolve in the script catalog; "
                            + "no cast inserted",
                    pos);
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
