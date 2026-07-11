package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical → target function rendering: the function-mapping table as data plus the
 * few argument adapters that need code (ROADMAP Phase 4). Also lowers the canonical
 * CONCAT operator into each target's mechanism. Functions outside the table pass
 * through unchanged with a warning — the honest treatment of the vendor long tail.
 */
public final class RenderTargetFunctionsRule implements Rule {

    /** Canonical names with identical spelling and arity in all three targets. */
    private static final Set<String> UNIVERSAL = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX",
            "COALESCE", "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM",
            "REPLACE", "ABS", "ROUND", "FLOOR", "CEILING", "LEFT", "RIGHT", "SUBSTRING");

    /** Canonical names handled by rename tables or adapters below. */
    private static final Set<String> MAPPED = Set.of(
            "NOW", "CHAR_LENGTH", "YEAR", "MONTH", "DAY");

    private static final Map<String, String> TSQL_RENAMES = Map.of(
            "NOW", "GETDATE",
            "CHAR_LENGTH", "LEN");

    @Override
    public String name() {
        return "render-target-functions";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Renderer(ctx).transform(script);
    }

    private static final class Renderer extends ScopedTransformer {

        private Renderer(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            FunctionCall call = (FunctionCall) super.visitFunctionCall(node);
            if (call.star() || call.quantifier().isPresent()) {
                return call;
            }
            String name = call.name();
            if (!UNIVERSAL.contains(name) && !MAPPED.contains(name)) {
                ctx.report().warn("FUNCTION_PASSTHROUGH",
                        "function " + name + " is outside the mapping table; "
                                + "passed through unchanged", call.pos());
                return call;
            }
            if (ctx.target() == Dialect.TSQL) {
                if (name.equals("SUBSTRING") && call.args().size() == 2) {
                    return substringAdapter(call);
                }
                String renamed = TSQL_RENAMES.getOrDefault(name, name);
                if (!renamed.equals(name)) {
                    return new FunctionCall(renamed, call.args(), false,
                            call.quantifier(), call.pos());
                }
                return call;
            }
            if (ctx.target() == Dialect.POSTGRESQL
                    && (name.equals("YEAR") || name.equals("MONTH") || name.equals("DAY"))) {
                List<Expression> args = List.of(
                        new StringLiteral(name.toLowerCase(Locale.ROOT), false, call.pos()),
                        call.args().get(0));
                return new FunctionCall("DATE_PART", args, false,
                        call.quantifier(), call.pos());
            }
            return call;             // MySQL: every canonical spelling is native
        }

        /** T-SQL SUBSTRING requires 3 args: append LEN(<string>) as the length. */
        private static FunctionCall substringAdapter(FunctionCall call) {
            List<Expression> args = new ArrayList<>(call.args());
            args.add(new FunctionCall("LEN", List.of(call.args().get(0)), false,
                    Optional.empty(), call.pos()));
            return new FunctionCall("SUBSTRING", args, false, call.quantifier(), call.pos());
        }

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (op.op() != BinaryOperator.CONCAT) {
                return op;
            }
            if (ctx.target() == Dialect.MYSQL) {
                // Bottom-up: an inner chain is already a CONCAT(...) call — merge it.
                List<Expression> args = new ArrayList<>();
                flatten(op.left(), args);
                flatten(op.right(), args);
                return new FunctionCall("CONCAT", args, false, Optional.empty(), op.pos());
            }
            if (ctx.target() == Dialect.TSQL) {
                return new BinaryOp(BinaryOperator.CONCAT,
                        stringOperand(op.left()), stringOperand(op.right()), op.pos());
            }
            return op;               // PostgreSQL prints ||
        }

        private static void flatten(Expression expr, List<Expression> into) {
            if (expr instanceof FunctionCall call && call.name().equals("CONCAT")
                    && !call.star() && call.quantifier().isEmpty()) {
                into.addAll(call.args());
            } else {
                into.add(expr);
            }
        }

        /** T-SQL '+' concatenates only strings: cast known non-strings, warn on unknown. */
        private Expression stringOperand(Expression operand) {
            SourcePosition pos = exprPos(operand);
            Optional<TypeFamily> family = familyOf(operand);
            if (family.isEmpty()) {
                ctx.report().warn("CONCAT_OPERAND_UNRESOLVED",
                        "concat operand type unknown; emitted without CAST — "
                                + "T-SQL '+' may fail or add", pos);
                return operand;
            }
            if (family.get() == TypeFamily.STRING) {
                return operand;
            }
            return new CastExpression(operand,
                    new DataType(GenericType.NVARCHAR, Optional.of(new MaxLength()),
                            Optional.empty()), pos);
        }

        private static SourcePosition exprPos(Expression expr) {
            if (expr instanceof rs.etf.sqltranslator.ast.ColumnRef ref) {
                return ref.pos();
            }
            if (expr instanceof rs.etf.sqltranslator.ast.NumericLiteral n) {
                return n.pos();
            }
            if (expr instanceof StringLiteral s) {
                return s.pos();
            }
            if (expr instanceof BinaryOp op) {
                return op.pos();
            }
            if (expr instanceof FunctionCall call) {
                return call.pos();
            }
            if (expr instanceof CastExpression cast) {
                return cast.pos();
            }
            throw new IllegalArgumentException("no position on expression: " + expr.getClass());
        }
    }
}
