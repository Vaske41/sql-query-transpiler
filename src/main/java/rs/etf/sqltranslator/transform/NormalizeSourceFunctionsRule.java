package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;

import java.util.List;
import java.util.Locale;

/**
 * Batch 2 (Normalize): fold source-specific function spellings into one canonical
 * name per concept, so every later rule matches a single spelling. Data lives in
 * small per-dialect rename tables; structural folds (ISNULL/1, CONCAT/n, DATE_PART)
 * are the only code paths.
 */
public final class NormalizeSourceFunctionsRule implements Rule {

    @Override
    public String name() {
        return "normalize-source-functions";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Normalizer(ctx).transform(script);
    }

    private static final class Normalizer extends AstTransformer {

        private final TranslationContext ctx;

        private Normalizer(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            FunctionCall call = (FunctionCall) super.visitFunctionCall(node);
            if (call.star() || call.quantifier().isPresent()) {
                return call;               // COUNT(*) / COUNT(DISTINCT x): aggregates
            }
            return switch (ctx.source()) {
                case TSQL -> tsql(call);
                case MYSQL -> mysql(call);
                case POSTGRESQL -> postgres(call);
            };
        }

        private Object tsql(FunctionCall call) {
            return switch (key(call)) {
                case "GETDATE/0" -> renamed(call, "NOW");
                case "ISNULL/2" -> renamed(call, "COALESCE");
                case "LEN/1" -> renamed(call, "CHAR_LENGTH");
                case "CONCAT/*" -> concatChain(call);
                default -> call;
            };
        }

        private Object mysql(FunctionCall call) {
            return switch (key(call)) {
                case "IFNULL/2" -> renamed(call, "COALESCE");
                case "ISNULL/1" -> new IsNullPredicate(call.args().get(0), false, call.pos());
                case "SUBSTR/2", "SUBSTR/3" -> renamed(call, "SUBSTRING");
                case "CEIL/1" -> renamed(call, "CEILING");
                case "LENGTH/1" -> {
                    ctx.report().warn("MYSQL_CHAR_LENGTH_ASSUMED",
                            "MySQL LENGTH() counts bytes; translated as character length",
                            call.pos());
                    yield renamed(call, "CHAR_LENGTH");
                }
                case "CONCAT/*" -> concatChain(call);
                default -> call;
            };
        }

        private Object postgres(FunctionCall call) {
            return switch (key(call)) {
                case "LENGTH/1" -> renamed(call, "CHAR_LENGTH");
                case "SUBSTR/2", "SUBSTR/3" -> renamed(call, "SUBSTRING");
                case "CEIL/1" -> renamed(call, "CEILING");
                case "DATE_PART/2" -> datePart(call);
                case "CONCAT/*" -> concatChain(call);
                default -> call;
            };
        }

        /** "NAME/argCount", with CONCAT of 2+ args collapsed to "CONCAT/*". */
        private static String key(FunctionCall call) {
            if (call.name().equals("CONCAT") && call.args().size() >= 2) {
                return "CONCAT/*";
            }
            return call.name() + "/" + call.args().size();
        }

        private static FunctionCall renamed(FunctionCall call, String canonical) {
            return new FunctionCall(canonical, call.args(), call.star(),
                    call.quantifier(), call.pos());
        }

        /** CONCAT(a, b, c) → ((a || b) || c) as BinaryOp(CONCAT) — the canonical form. */
        private static Expression concatChain(FunctionCall call) {
            Expression chain = call.args().get(0);
            for (Expression arg : call.args().subList(1, call.args().size())) {
                chain = new BinaryOp(BinaryOperator.CONCAT, chain, arg, call.pos());
            }
            return chain;
        }

        /** DATE_PART('year'|'month'|'day', x) → YEAR/MONTH/DAY(x); other fields pass. */
        private static Object datePart(FunctionCall call) {
            if (call.args().get(0) instanceof StringLiteral field) {
                String canonical = switch (field.value().toLowerCase(Locale.ROOT)) {
                    case "year" -> "YEAR";
                    case "month" -> "MONTH";
                    case "day" -> "DAY";
                    default -> null;
                };
                if (canonical != null) {
                    return new FunctionCall(canonical, List.of(call.args().get(1)),
                            false, call.quantifier(), call.pos());
                }
            }
            return call;
        }
    }
}
