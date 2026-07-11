package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThatCode;

/** Quoting/escaping edge cases per dialect — the printers must survive these in Phase 5. */
class LexerHostileInputTest {

    @Test
    void tsqlBracketEscapeAndUnicodeString() {
        assertThatCode(() -> ParserFacade.parseScript(
                "SELECT [we]]ird], \"a\"\"b\" FROM t WHERE x = N'O''Brien';", Dialect.TSQL))
                .doesNotThrowAnyException();
    }

    @Test
    void mysqlBacktickEscapeAndBothStringQuotes() {
        assertThatCode(() -> ParserFacade.parseScript(
                "SELECT `we``ird` FROM t WHERE a = 'O\\'Brien' AND b = \"say \\\"hi\\\"\";", Dialect.MYSQL))
                .doesNotThrowAnyException();
    }

    @Test
    void postgresDoubledQuotesAndEscapeString() {
        assertThatCode(() -> ParserFacade.parseScript(
                "SELECT \"we\"\"ird\" FROM t WHERE a = 'O''Brien' AND b = E'tab\\there';", Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void commentsEverywhere() {
        assertThatCode(() -> ParserFacade.parseScript(
                "SELECT 1 -- line\n, 2 /* block */ FROM t; -- trailing", Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }
}
