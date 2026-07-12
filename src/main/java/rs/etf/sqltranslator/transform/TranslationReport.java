package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.ArrayList;
import java.util.List;

/** Accumulates warnings in emission order (deterministic: rules run in fixed order). */
public final class TranslationReport {

    private final List<Warning> warnings = new ArrayList<>();

    public void warn(String code, String message, SourcePosition position) {
        warnings.add(new Warning(code, message, position));
    }

    public List<Warning> warnings() {
        return List.copyOf(warnings);
    }
}
