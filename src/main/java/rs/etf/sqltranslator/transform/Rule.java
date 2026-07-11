package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Script;

/**
 * One Catalyst-style rewrite: a pure Script → Script function (Strategy pattern).
 * Rules run once, in the fixed order wired in {@link RuleEngine#standard()}; a rule
 * may throw {@link rs.etf.sqltranslator.core.UnsupportedFeatureException} to refuse.
 */
public interface Rule {

    String name();

    Script apply(Script script, TranslationContext ctx);
}
