package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Script;

/** The rewritten script plus every warning the rules emitted. */
public record TranslationResult(Script script, TranslationReport report) {
}
