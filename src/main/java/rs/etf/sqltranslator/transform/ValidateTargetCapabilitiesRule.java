package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validate batch: refuse constructs the target cannot express, and warn when
 * MySQL loose {@code GROUP BY} would be rejected by PostgreSQL / T-SQL at
 * execution. Traverses via the identity transformer — fail fast on refusals,
 * never a silent wrong translation.
 */
public final class ValidateTargetCapabilitiesRule implements Rule {

    @Override
    public String name() {
        return "validate-target-capabilities";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Validator(ctx).transform(script);
    }

    private static final class Validator extends AstTransformer {

        private final TranslationContext ctx;

        private Validator(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitJoin(Join node) {
            if (node.kind() == JoinKind.FULL && ctx.target() == Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "FULL JOIN is not supported by MySQL", node.pos());
            }
            return super.visitJoin(node);
        }

        @Override
        public Object visitQuery(Query node) {
            node.limit().ifPresent(limit -> validateLimit(node, limit));
            return super.visitQuery(node);
        }

        @Override
        public Object visitQuerySpecification(QuerySpecification node) {
            warnLooseGroupBy(node);
            return super.visitQuerySpecification(node);
        }

        private void warnLooseGroupBy(QuerySpecification spec) {
            if (ctx.source() != Dialect.MYSQL
                    || ctx.target() == Dialect.MYSQL
                    || spec.groupBy().isEmpty()) {
                return;
            }
            Set<String> grouped = spec.groupBy().stream()
                    .filter(ColumnRef.class::isInstance)
                    .map(e -> ((ColumnRef) e).name().last().value().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            for (SelectItem item : spec.items()) {
                if (!(item instanceof SelectExpr selectExpr)) {
                    continue;
                }
                Expression expr = selectExpr.expr();
                if (!(expr instanceof ColumnRef ref)) {
                    continue;
                }
                String name = ref.name().last().value().toLowerCase(Locale.ROOT);
                if (!grouped.contains(name)) {
                    ctx.report().warn("LOOSE_GROUP_BY",
                            "MySQL allows select column '" + ref.name().last().value()
                                    + "' outside GROUP BY; " + ctx.target()
                                    + " will reject this at execution",
                            ref.pos());
                }
            }
        }

        private void validateLimit(Query query, RowLimit limit) {
            if (limit.offset().isEmpty()) {
                return;
            }
            if (ctx.target() == Dialect.TSQL && query.orderBy().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "OFFSET requires ORDER BY on SQL Server", limit.pos());
            }
            if (ctx.target() == Dialect.MYSQL && limit.count().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "OFFSET without LIMIT is not supported by MySQL", limit.pos());
            }
        }
    }
}
