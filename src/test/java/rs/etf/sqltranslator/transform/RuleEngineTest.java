package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    @Test
    void engineWithNoRulesIsIdentityAndReportsNothing() {
        Script script = AstBuilderFacade.buildScript(
                "SELECT id, name FROM users WHERE id > 5;", Dialect.POSTGRESQL);
        TranslationResult result = new RuleEngine(java.util.List.of())
                .run(script, Dialect.POSTGRESQL, Dialect.MYSQL);
        AstDumper dumper = new AstDumper();
        assertThat(dumper.dump(result.script())).isEqualTo(dumper.dump(script));
        assertThat(result.report().warnings()).isEmpty();
    }

    @Test
    void engineAppliesRulesInListOrder() {
        java.util.List<String> applied = new java.util.ArrayList<>();
        Rule a = named("a", applied);
        Rule b = named("b", applied);
        Script script = AstBuilderFacade.buildScript("SELECT 1;", Dialect.MYSQL);
        new RuleEngine(java.util.List.of(a, b)).run(script, Dialect.MYSQL, Dialect.TSQL);
        assertThat(applied).containsExactly("a", "b");
    }

    @Test
    void contextCarriesCatalogBuiltFromTheScript() {
        Script script = AstBuilderFacade.buildScript(
                "CREATE TABLE t (id INT); SELECT id FROM t;", Dialect.MYSQL);
        Rule probe = new Rule() {
            @Override public String name() { return "probe"; }
            @Override public Script apply(Script s, TranslationContext ctx) {
                assertThat(ctx.catalog().table("t")).isPresent();
                assertThat(ctx.source()).isEqualTo(Dialect.MYSQL);
                assertThat(ctx.target()).isEqualTo(Dialect.POSTGRESQL);
                return s;
            }
        };
        new RuleEngine(java.util.List.of(probe))
                .run(script, Dialect.MYSQL, Dialect.POSTGRESQL);
    }

    private static Rule named(String name, java.util.List<String> sink) {
        return new Rule() {
            @Override public String name() { return name; }
            @Override public Script apply(Script s, TranslationContext ctx) {
                sink.add(name);
                return s;
            }
        };
    }
}
