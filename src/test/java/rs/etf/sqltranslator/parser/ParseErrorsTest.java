package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.ParseException;

import java.nio.file.Files;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Walks src/test/resources/parse-errors/<dialect> and asserts each file raises ParseException. */
class ParseErrorsTest {

    @TestFactory
    Stream<DynamicTest> everyErrorInputRaisesParseException() {
        CaseFiles corpus = CaseFiles.under("/parse-errors",
                p -> p.getFileName().toString().endsWith(".sql"));
        return corpus.files().stream()
                .map(p -> DynamicTest.dynamicTest(
                        corpus.displayName(p),
                        () -> {
                            String sql = Files.readString(p);
                            Dialect dialect = Dialect.valueOf(
                                    p.getParent().getFileName().toString().toUpperCase());
                            ParseException ex = catchThrowableOfType(
                                    ParseException.class,
                                    () -> ParserFacade.parseScript(sql, dialect));
                            assertThat(ex.errors()).isNotEmpty();
                            assertThat(ex.errors().get(0).line()).isGreaterThanOrEqualTo(1);
                        }));
    }
}
