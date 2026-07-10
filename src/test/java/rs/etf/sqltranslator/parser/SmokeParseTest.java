package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.grammar.SmokeLexer;
import rs.etf.sqltranslator.grammar.SmokeParser;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeParseTest {

    @Test
    void parsesSelectOneWithoutSyntaxErrors() {
        SmokeLexer lexer = new SmokeLexer(CharStreams.fromString("SELECT 1"));
        SmokeParser parser = new SmokeParser(new CommonTokenStream(lexer));

        parser.statement();

        assertThat(parser.getNumberOfSyntaxErrors()).isZero();
    }

    @Test
    void countsSyntaxErrorsOnInvalidInput() {
        SmokeLexer lexer = new SmokeLexer(CharStreams.fromString("SELECT"));
        SmokeParser parser = new SmokeParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners(); // keep expected-failure noise out of the build log

        parser.statement();

        assertThat(parser.getNumberOfSyntaxErrors()).isPositive();
    }
}
