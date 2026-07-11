package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.ParseException;
import rs.etf.sqltranslator.grammar.MySqlLexer;
import rs.etf.sqltranslator.grammar.MySqlParser;
import rs.etf.sqltranslator.grammar.PostgreSqlLexer;
import rs.etf.sqltranslator.grammar.PostgreSqlParser;
import rs.etf.sqltranslator.grammar.TSqlLexer;
import rs.etf.sqltranslator.grammar.TSqlParser;

/** Single entry point: SQL text + source dialect → parse tree, or ParseException. */
public final class ParserFacade {

    private ParserFacade() {
    }

    public static ParserRuleContext parseScript(String sql, Dialect dialect) {
        CollectingErrorListener errorListener = new CollectingErrorListener();
        CharStream chars = CharStreams.fromString(sql);
        ParserRuleContext tree = switch (dialect) {
            case TSQL -> {
                TSqlParser parser = new TSqlParser(
                        tokens(new TSqlLexer(chars), errorListener));
                configure(parser, errorListener);
                yield parser.script();
            }
            case MYSQL -> {
                MySqlParser parser = new MySqlParser(
                        tokens(new MySqlLexer(chars), errorListener));
                configure(parser, errorListener);
                yield parser.script();
            }
            case POSTGRESQL -> {
                PostgreSqlParser parser = new PostgreSqlParser(
                        tokens(new PostgreSqlLexer(chars), errorListener));
                configure(parser, errorListener);
                yield parser.script();
            }
        };
        if (errorListener.hasErrors()) {
            throw new ParseException(errorListener.errors());
        }
        return tree;
    }

    private static CommonTokenStream tokens(Lexer lexer, CollectingErrorListener listener) {
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        return new CommonTokenStream(lexer);
    }

    private static void configure(Parser parser, CollectingErrorListener listener) {
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
    }
}
