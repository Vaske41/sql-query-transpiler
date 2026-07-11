package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.Optional;

/**
 * T-SQL overloads {@code +} as string concatenation. With string evidence (literal
 * operand or catalog-resolved string column) the operator becomes the canonical
 * {@code BinaryOp(CONCAT)}; with full numeric evidence it stays ADD; otherwise it
 * stays ADD with an {@code AMBIGUOUS_PLUS} warning — a documented boundary of the
 * catalog approach, never a guess.
 */
public final class ResolveConcatRule implements Rule {

    @Override
    public String name() {
        return "resolve-concat";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.source() != Dialect.TSQL) {
            return script;
        }
        return new Resolver(ctx).transform(script);
    }

    private static final class Resolver extends ScopedTransformer {

        private Resolver(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (op.op() != BinaryOperator.ADD) {
                return op;
            }
            Optional<TypeFamily> left = familyOf(op.left());
            Optional<TypeFamily> right = familyOf(op.right());
            boolean anyString = left.filter(f -> f == TypeFamily.STRING).isPresent()
                    || right.filter(f -> f == TypeFamily.STRING).isPresent();
            if (anyString) {
                return new BinaryOp(BinaryOperator.CONCAT, op.left(), op.right(), op.pos());
            }
            if (left.isEmpty() || right.isEmpty()) {
                ctx.report().warn("AMBIGUOUS_PLUS",
                        "operand types unknown; T-SQL '+' kept as addition", op.pos());
            }
            return op;
        }
    }
}
