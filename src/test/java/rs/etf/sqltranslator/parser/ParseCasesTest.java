package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;

/** Walks src/test/resources/cases and asserts every input.<dialect>.sql parses cleanly. */
class ParseCasesTest {

    @TestFactory
    Stream<DynamicTest> everyCaseInputParses() {
        CaseFiles corpus = CaseFiles.under("/cases",
                p -> p.getFileName().toString().startsWith("input."));
        return corpus.files().stream()
                .map(p -> DynamicTest.dynamicTest(
                        corpus.displayName(p),
                        () -> {
                            String sql = Files.readString(p);
                            Dialect dialect = dialectFromFileName(p.getFileName().toString());
                            assertThatCode(() -> ParserFacade.parseScript(sql, dialect))
                                    .doesNotThrowAnyException();
                        }));
    }

    static Dialect dialectFromFileName(String fileName) {
        String tag = fileName.substring("input.".length(), fileName.lastIndexOf('.'));
        return switch (tag) {
            case "tsql" -> Dialect.TSQL;
            case "mysql" -> Dialect.MYSQL;
            case "postgresql" -> Dialect.POSTGRESQL;
            default -> throw new IllegalArgumentException("Unknown dialect tag: " + fileName);
        };
    }
}
