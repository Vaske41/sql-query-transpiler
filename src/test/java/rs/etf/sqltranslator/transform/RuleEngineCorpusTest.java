package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;
import rs.etf.sqltranslator.parser.CaseFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 exit evidence: the standard rule sequence over the whole corpus, all
 * three targets, twice — no unexpected refusal, byte-identical reruns.
 */
class RuleEngineCorpusTest {

    private static final Set<String> EXPECTED_REFUSALS = Set.of(
            "joins/full-join/input.tsql.sql|MYSQL",
            "joins/full-join/input.postgresql.sql|MYSQL",
            "limits/limit-offset/input.mysql.sql|TSQL");

    @TestFactory
    Stream<DynamicTest> everyCaseTranslatesDeterministicallyInAllDirections() {
        CaseFiles corpus = CaseFiles.under("/cases",
                p -> p.getFileName().toString().startsWith("input.")
                        && !p.toString().replace('\\', '/').contains("/unsupported/"));
        return corpus.files().stream().flatMap(file ->
                Stream.of(Dialect.values()).map(target -> DynamicTest.dynamicTest(
                        corpus.displayName(file) + " -> " + target,
                        () -> check(corpus, file, target))));
    }

    private void check(CaseFiles corpus, Path file, Dialect target) throws Exception {
        String sql = Files.readString(file);
        Dialect source = dialectOf(file.getFileName().toString());
        String key = corpus.displayName(file) + "|" + target;
        if (EXPECTED_REFUSALS.contains(key)) {
            assertThatThrownBy(() -> RuleEngine.standard()
                    .run(AstBuilderFacade.buildScript(sql, source), source, target))
                    .isInstanceOf(UnsupportedFeatureException.class);
            return;
        }
        assertThatCode(() -> RuleEngine.standard()
                .run(AstBuilderFacade.buildScript(sql, source), source, target))
                .doesNotThrowAnyException();

        Script first = RuleEngine.standard()
                .run(AstBuilderFacade.buildScript(sql, source), source, target).script();
        Script second = RuleEngine.standard()
                .run(AstBuilderFacade.buildScript(sql, source), source, target).script();
        AstDumper dumper = new AstDumper();
        assertThat(dumper.dump(second)).isEqualTo(dumper.dump(first));
    }

    private static Dialect dialectOf(String fileName) {
        String tag = fileName.substring("input.".length(), fileName.lastIndexOf('.'));
        return switch (tag) {
            case "tsql" -> Dialect.TSQL;
            case "mysql" -> Dialect.MYSQL;
            case "postgresql" -> Dialect.POSTGRESQL;
            default -> throw new IllegalArgumentException(fileName);
        };
    }
}
