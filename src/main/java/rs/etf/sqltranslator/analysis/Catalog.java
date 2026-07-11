package rs.etf.sqltranslator.analysis;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight schema context built from a script's DDL — the analog of Catalyst's
 * catalog; it makes Phase 4's type-dependent rewrites decidable.
 *
 * <p>Keying rule: the map key is the <em>last</em> identifier of the table's
 * qualified name, lowercased — {@code dbo.users} and {@code users} deliberately
 * collide. This is a documented simplification consistent with the
 * case-insensitivity limitation (D6). The catalog is best-effort context:
 * statements referencing unknown tables are fine, and Phase 4 degrades to warnings
 * on misses.
 */
public record Catalog(Map<String, TableSchema> tables) {

    public Catalog {
        tables = Map.copyOf(tables);
    }

    /** Case-insensitive lookup by the table's own (unqualified) name. */
    public Optional<TableSchema> table(String tableName) {
        return Optional.ofNullable(tables.get(tableName.toLowerCase(Locale.ROOT)));
    }
}
