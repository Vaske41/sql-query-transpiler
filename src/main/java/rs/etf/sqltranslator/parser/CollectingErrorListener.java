package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import rs.etf.sqltranslator.core.SyntaxError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects every syntax error instead of printing to the console (replaces ANTLR's default). */
final class CollectingErrorListener extends BaseErrorListener {

    private final List<SyntaxError> errors = new ArrayList<>();

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine, String msg,
                            RecognitionException e) {
        errors.add(new SyntaxError(line, charPositionInLine, msg));
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    List<SyntaxError> errors() {
        return Collections.unmodifiableList(errors);
    }
}
