package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.Optional;

/**
 * MySQL and T-SQL cannot express NULLS FIRST/LAST. The clause is dropped with a
 * warning naming what was lost — the target's default NULL placement takes over
 * (emulation via ISNULL-style sort keys is out of v1 scope, documented limitation).
 */
public final class DropNullsOrderingRule implements Rule {

    @Override
    public String name() {
        return "drop-nulls-ordering";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() == Dialect.POSTGRESQL) {
            return script;
        }
        return new Dropper(ctx).transform(script);
    }

    private static final class Dropper extends AstTransformer {

        private final TranslationContext ctx;

        private Dropper(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitOrderItem(OrderItem node) {
            OrderItem item = (OrderItem) super.visitOrderItem(node);
            if (item.nulls().isEmpty()) {
                return item;
            }
            ctx.report().warn("NULLS_ORDERING_DROPPED",
                    "NULLS " + item.nulls().get() + " is not expressible in "
                            + ctx.target() + "; target default NULL placement applies",
                    item.pos());
            return new OrderItem(item.expr(), item.direction(), Optional.empty(),
                    item.pos());
        }
    }
}
