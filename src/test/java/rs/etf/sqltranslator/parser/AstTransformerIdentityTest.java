package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transformer identity test (§6.5): the stock {@link AstTransformer} applied to
 * every corpus-built {@code Script} yields dump-identical output — guards the
 * rebuild plumbing every Phase 4 rule will inherit.
 */
class AstTransformerIdentityTest {

    @TestFactory
    Stream<DynamicTest> identityTransformPreservesEveryCorpusScript() {
        CaseFiles corpus = CaseFiles.under("/cases",
                p -> p.getFileName().toString().startsWith("input.")
                        && !AstBuildCasesTest.isUnsupportedCase(p));
        AstDumper dumper = new AstDumper();
        AstTransformer identity = new AstTransformer();
        return corpus.files().stream()
                .map(p -> DynamicTest.dynamicTest(
                        corpus.displayName(p),
                        () -> {
                            String sql = Files.readString(p);
                            Dialect dialect = ParseCasesTest
                                    .dialectFromFileName(p.getFileName().toString());
                            Script script = AstBuilderFacade.buildScript(sql, dialect);
                            Script rebuilt = identity.transform(script);
                            assertThat(dumper.dump(rebuilt)).isEqualTo(dumper.dump(script));
                        }));
    }
}
