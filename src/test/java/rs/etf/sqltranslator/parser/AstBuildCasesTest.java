package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Corpus-wide build test (§6.1): every {@code cases/**}{@code /input.*.sql} builds a
 * {@code Script} without throwing — except {@code cases/unsupported/**}, which must
 * parse fine yet fail to build with {@link UnsupportedFeatureException} (refusal
 * over silent mistranslation).
 */
class AstBuildCasesTest {

    @TestFactory
    Stream<DynamicTest> everyCaseInputBuilds() {
        CaseFiles corpus = CaseFiles.under("/cases",
                p -> p.getFileName().toString().startsWith("input."));
        return corpus.files().stream()
                .map(p -> DynamicTest.dynamicTest(
                        corpus.displayName(p),
                        () -> {
                            String sql = Files.readString(p);
                            Dialect dialect = ParseCasesTest
                                    .dialectFromFileName(p.getFileName().toString());
                            if (isUnsupportedCase(p)) {
                                assertThatExceptionOfType(UnsupportedFeatureException.class)
                                        .isThrownBy(() ->
                                                AstBuilderFacade.buildScript(sql, dialect));
                            } else {
                                assertThatCode(() ->
                                        AstBuilderFacade.buildScript(sql, dialect))
                                        .doesNotThrowAnyException();
                            }
                        }));
    }

    static boolean isUnsupportedCase(Path file) {
        for (Path part : file) {
            if (part.getFileName().toString().equals("unsupported")) {
                return true;
            }
        }
        return false;
    }
}
