package rs.etf.sqltranslator.core;

import java.util.List;

/** Thrown after parsing completes with one or more syntax errors; never partial results. */
public class ParseException extends RuntimeException {

    private final List<SyntaxError> errors;

    public ParseException(List<SyntaxError> errors) {
        super(describe(errors));
        this.errors = List.copyOf(errors);
    }

    private static String describe(List<SyntaxError> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("ParseException requires at least one SyntaxError");
        }
        SyntaxError first = errors.get(0);
        return errors.size() + " syntax error(s), first at line " + first.line()
                + ":" + first.charPositionInLine() + " — " + first.message();
    }

    public List<SyntaxError> errors() {
        return errors;
    }
}
