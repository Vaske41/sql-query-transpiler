package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.NullsOrder;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SortDirection;
import rs.etf.sqltranslator.core.Dialect;

import java.util.Optional;

/**
 * MySQL and T-SQL sort NULLs as the lowest value (ASC → NULLs first, DESC → NULLs
 * last). PostgreSQL defaults to the opposite on ASC. When the target is PostgreSQL
 * and the source did not spell {@code NULLS FIRST/LAST}, emit the clause that
 * preserves source NULL placement (ROADMAP Phase 4 both-directions story).
 */
public final class PreserveNullsOrderingRule implements Rule {

    @Override
    public String name() {
        return "preserve-nulls-ordering";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.POSTGRESQL
                || ctx.source() == Dialect.POSTGRESQL) {
            return script;
        }
        return new Preserver().transform(script);
    }

    private static final class Preserver extends AstTransformer {

        @Override
        public Object visitOrderItem(OrderItem node) {
            OrderItem item = (OrderItem) super.visitOrderItem(node);
            if (item.nulls().isPresent()) {
                return item;
            }
            NullsOrder preserved = item.direction() == SortDirection.ASC
                    ? NullsOrder.FIRST
                    : NullsOrder.LAST;
            return new OrderItem(item.expr(), item.direction(), Optional.of(preserved),
                    item.pos());
        }
    }
}
