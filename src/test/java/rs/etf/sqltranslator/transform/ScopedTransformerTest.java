package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.analysis.CatalogBuilder;
import rs.etf.sqltranslator.analysis.ColumnInfo;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedTransformerTest {

    private static final String DDL = """
            CREATE TABLE products (id INT, name VARCHAR(50), price DECIMAL(10,2));
            CREATE TABLE orders (id INT, product_id INT);
            """;

    /** Probe transformer: records what every ColumnRef resolves to. */
    private static final class Probe extends ScopedTransformer {
        final List<String> resolved = new ArrayList<>();

        Probe(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitColumnRef(ColumnRef node) {
            Optional<ColumnInfo> info = resolve(node);
            resolved.add(node.name().last().value() + "->"
                    + info.map(c -> c.type().type().name()).orElse("UNRESOLVED"));
            return super.visitColumnRef(node);
        }
    }

    private static Probe probe(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        TranslationContext ctx = new TranslationContext(dialect, Dialect.POSTGRESQL,
                CatalogBuilder.build(script), new TranslationReport());
        Probe p = new Probe(ctx);
        p.transform(script);
        return p;
    }

    @Test
    void resolvesAliasQualifiedColumnThroughFromScope() {
        Probe p = probe(DDL + "SELECT p.price FROM products p WHERE p.name = 'x';",
                Dialect.MYSQL);
        assertThat(p.resolved).contains("price->DECIMAL", "name->VARCHAR");
    }

    @Test
    void resolvesUnqualifiedColumnWhenUniqueAcrossScope() {
        Probe p = probe(DDL + "SELECT name FROM products p JOIN orders o ON p.id = o.product_id;",
                Dialect.MYSQL);
        assertThat(p.resolved).contains("name->VARCHAR");
    }

    @Test
    void ambiguousUnqualifiedColumnStaysUnresolved() {
        // `id` exists in both tables — must NOT guess.
        Probe p = probe(DDL + "SELECT id FROM products p JOIN orders o ON p.id = o.product_id;",
                Dialect.MYSQL);
        assertThat(p.resolved).contains("id->UNRESOLVED");
    }

    @Test
    void updateAndDeleteResolveAgainstTheirTargetTable() {
        Probe p = probe(DDL + "UPDATE products SET price = 1 WHERE name = 'x';"
                + "DELETE FROM orders WHERE product_id = 3;", Dialect.MYSQL);
        assertThat(p.resolved).contains("name->VARCHAR", "product_id->INTEGER");
    }

    @Test
    void typeFamilyCoversEveryGenericType() {
        for (GenericType type : GenericType.values()) {
            assertThat(TypeFamily.of(type)).isNotNull();
        }
        assertThat(TypeFamily.of(GenericType.NVARCHAR)).isEqualTo(TypeFamily.STRING);
        assertThat(TypeFamily.of(GenericType.DECIMAL)).isEqualTo(TypeFamily.NUMERIC);
        assertThat(TypeFamily.of(GenericType.TIMESTAMP)).isEqualTo(TypeFamily.DATETIME);
    }
}
