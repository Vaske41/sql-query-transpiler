package rs.etf.sqltranslator.analysis;

import rs.etf.sqltranslator.ast.QualifiedName;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The cataloged shape of one table. Columns are a {@code List} in <em>declaration
 * order</em> — order is load-bearing: Phase 4's cast-insertion rule resolves
 * {@code INSERT INTO t VALUES (...)} positionally, and the column list is optional
 * in all three grammars.
 */
public record TableSchema(QualifiedName name, List<ColumnInfo> columns) {

    public TableSchema {
        columns = List.copyOf(columns);
    }

    /**
     * Lowercased-name lookup over the declaration-ordered list — a documented
     * simplification consistent with the case-insensitivity limitation (D6).
     */
    public Optional<ColumnInfo> column(String columnName) {
        String wanted = columnName.toLowerCase(Locale.ROOT);
        return columns.stream()
                .filter(c -> c.name().value().toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }
}
