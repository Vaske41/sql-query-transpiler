package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

/**
 * Batch 1 (Validate): refuse constructs the target cannot express. Traverses via
 * the identity transformer and throws instead of rewriting — fail fast, position
 * attached, never a silent wrong translation.
 */
public final class ValidateTargetCapabilitiesRule implements Rule {

    @Override
    public String name() {
        return "validate-target-capabilities";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Validator(ctx.target()).transform(script);
    }

    private static final class Validator extends AstTransformer {

        private final Dialect target;

        private Validator(Dialect target) {
            this.target = target;
        }

        @Override
        public Object visitJoin(Join node) {
            if (node.kind() == JoinKind.FULL && target == Dialect.MYSQL) {
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

        private void validateLimit(Query query, RowLimit limit) {
            if (limit.offset().isEmpty()) {
                return;
            }
            if (target == Dialect.TSQL && query.orderBy().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "OFFSET requires ORDER BY on SQL Server", limit.pos());
            }
            if (target == Dialect.MYSQL && limit.count().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "OFFSET without LIMIT is not supported by MySQL", limit.pos());
            }
        }
    }
}
