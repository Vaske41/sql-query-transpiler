package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.CatalogBuilder;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.List;

/**
 * Applies rules once, in fixed list order — a deterministic linear rewrite
 * pipeline inspired by Catalyst's <em>rule</em> idea, not Catalyst's
 * analyzer/optimizer scheduling or fixed-point iteration. Order is load-bearing
 * (pipeline stages, not independent batches).
 */
public final class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** The standard sequence. Order is load-bearing. */
    public static RuleEngine standard() {
        return new RuleEngine(List.of(
                new ValidateTargetCapabilitiesRule(),
                new NormalizeSourceFunctionsRule(),
                new ResolveConcatRule(),
                new InsertCastsRule(),
                new RewriteBooleanSemanticsRule(),
                new NarrowTypesRule(),
                new RenderTargetFunctionsRule(),
                new PreserveNullsOrderingRule(),
                new DropNullsOrderingRule()));
    }

    public TranslationResult run(Script script, Dialect source, Dialect target) {
        TranslationReport report = new TranslationReport();
        TranslationContext ctx =
                new TranslationContext(source, target, CatalogBuilder.build(script), report);
        Script current = script;
        for (Rule rule : rules) {
            current = rule.apply(current, ctx);
        }
        return new TranslationResult(current, report);
    }
}
