package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.Catalog;
import rs.etf.sqltranslator.core.Dialect;

/**
 * Everything a rule may consult: source/target dialect, the catalog built from the
 * source script's DDL (never narrowed — rules read source-typed columns), and the
 * warning sink.
 */
public record TranslationContext(Dialect source, Dialect target, Catalog catalog,
                                 TranslationReport report) {
}
