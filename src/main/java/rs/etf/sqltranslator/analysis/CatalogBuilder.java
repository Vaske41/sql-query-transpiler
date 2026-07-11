package rs.etf.sqltranslator.analysis;

import rs.etf.sqltranslator.ast.AbstractAstVisitor;
import rs.etf.sqltranslator.ast.AddColumn;
import rs.etf.sqltranslator.ast.AlterTableStatement;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.DropColumn;
import rs.etf.sqltranslator.ast.DropTableStatement;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@link Catalog} by walking a script's statements <em>in order</em>:
 * {@code CREATE TABLE} registers (duplicate name: last one wins), {@code ALTER TABLE
 * ADD/DROP COLUMN} rewrites the entry, {@code DROP TABLE} removes it, and
 * {@code ALTER}/{@code DROP} of an unregistered table is silently ignored.
 *
 * <p>Subclasses {@link AbstractAstVisitor} (walk/scan) and overrides only the DDL
 * nodes — analysis observation, not an AST→AST rewrite. Phase 4 rules subclass
 * {@code AstTransformer} for pure transforms.
 */
public final class CatalogBuilder extends AbstractAstVisitor<Void> {

    private final Map<String, TableSchema> tables = new LinkedHashMap<>();

    /** The ALTER TABLE target while its action is being visited; null when unknown. */
    private TableSchema alterTarget;

    private CatalogBuilder() {
    }

    public static Catalog build(Script script) {
        CatalogBuilder builder = new CatalogBuilder();
        script.accept(builder);
        return new Catalog(builder.tables);
    }

    @Override
    public Void visitCreateTableStatement(CreateTableStatement node) {
        List<ColumnInfo> columns = node.columns().stream()
                .map(CatalogBuilder::columnInfo)
                .toList();
        tables.put(key(node.table()), new TableSchema(node.table(), columns));
        return null;
    }

    @Override
    public Void visitDropTableStatement(DropTableStatement node) {
        tables.remove(key(node.table()));
        return null;
    }

    @Override
    public Void visitAlterTableStatement(AlterTableStatement node) {
        alterTarget = tables.get(key(node.table()));
        if (alterTarget != null) {
            node.action().accept(this);
        }
        alterTarget = null;
        return null;
    }

    @Override
    public Void visitAddColumn(AddColumn node) {
        List<ColumnInfo> columns = new ArrayList<>(alterTarget.columns());
        columns.add(columnInfo(node.column()));
        tables.put(key(alterTarget.name()), new TableSchema(alterTarget.name(), columns));
        return null;
    }

    @Override
    public Void visitDropColumn(DropColumn node) {
        String dropped = node.column().value().toLowerCase(Locale.ROOT);
        List<ColumnInfo> columns = alterTarget.columns().stream()
                .filter(c -> !c.name().value().toLowerCase(Locale.ROOT).equals(dropped))
                .toList();
        tables.put(key(alterTarget.name()), new TableSchema(alterTarget.name(), columns));
        return null;
    }

    private static ColumnInfo columnInfo(ColumnDefinition column) {
        return new ColumnInfo(column.name(), column.type(), column.autoIncrement());
    }

    private static String key(QualifiedName table) {
        return table.last().value().toLowerCase(Locale.ROOT);
    }
}
