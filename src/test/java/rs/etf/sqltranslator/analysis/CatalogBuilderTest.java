package rs.etf.sqltranslator.analysis;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog tests (§6.4): in-order DDL application, declaration-ordered columns
 * (positional INSERT resolution), case-insensitive lookup, and the documented
 * last-identifier keying simplification.
 */
class CatalogBuilderTest {

    @Test
    void ddlThenDmlScriptYieldsExpectedCatalog() {
        Catalog catalog = catalog(Dialect.POSTGRESQL, """
                CREATE TABLE products (id INT NOT NULL PRIMARY KEY, name VARCHAR(50),
                                       price DECIMAL(10,2));
                INSERT INTO products (id, name, price) VALUES (1, 'Widget', 9.99);
                SELECT name FROM products WHERE price > 5;
                """);
        assertThat(catalog.tables()).containsOnlyKeys("products");
        TableSchema products = catalog.table("products").orElseThrow();
        assertThat(products.columns()).hasSize(3);
        assertThat(products.column("price").orElseThrow().type().type())
                .isEqualTo(GenericType.DECIMAL);
    }

    @Test
    void columnsKeepDeclarationOrderForPositionalInsertResolution() {
        Catalog catalog = catalog(Dialect.MYSQL,
                "CREATE TABLE t (zebra INT, alpha VARCHAR(10), mid BOOLEAN);");
        assertThat(catalog.table("t").orElseThrow().columns())
                .extracting(c -> c.name().value())
                .containsExactly("zebra", "alpha", "mid");
    }

    @Test
    void lookupsAreCaseInsensitive() {
        Catalog catalog = catalog(Dialect.TSQL, "CREATE TABLE Users (Id INT, Name VARCHAR(10));");
        assertThat(catalog.table("USERS")).isPresent();
        TableSchema users = catalog.table("users").orElseThrow();
        assertThat(users.column("ID")).isPresent();
        // Case stays preserved as written (D6) even though lookup ignores it.
        assertThat(users.column("id").orElseThrow().name().value()).isEqualTo("Id");
    }

    @Test
    void alterAndDropApplyInScriptOrder() {
        Catalog catalog = catalog(Dialect.POSTGRESQL, """
                CREATE TABLE t (a INT);
                ALTER TABLE t ADD COLUMN b VARCHAR(10);
                ALTER TABLE t ADD COLUMN c BOOLEAN;
                ALTER TABLE t DROP COLUMN a;
                """);
        assertThat(catalog.table("t").orElseThrow().columns())
                .extracting(c -> c.name().value())
                .containsExactly("b", "c");
    }

    @Test
    void dropTableRemovesTheEntry() {
        Catalog catalog = catalog(Dialect.MYSQL, """
                CREATE TABLE gone (a INT);
                CREATE TABLE kept (b INT);
                DROP TABLE gone;
                """);
        assertThat(catalog.tables()).containsOnlyKeys("kept");
    }

    @Test
    void duplicateCreateTableLastOneWins() {
        Catalog catalog = catalog(Dialect.POSTGRESQL, """
                CREATE TABLE t (old_col INT);
                CREATE TABLE t (new_col VARCHAR(5));
                """);
        assertThat(catalog.table("t").orElseThrow().columns())
                .extracting(c -> c.name().value())
                .containsExactly("new_col");
    }

    @Test
    void alterAndDropOfUnknownTablesAreSilentlyIgnored() {
        Catalog catalog = catalog(Dialect.TSQL, """
                ALTER TABLE ghost ADD nickname VARCHAR(50);
                DROP TABLE phantom;
                CREATE TABLE real_table (a INT);
                """);
        assertThat(catalog.tables()).containsOnlyKeys("real_table");
    }

    @Test
    void qualifiedAndBareNamesDeliberatelyCollideOnLastIdentifier() {
        Catalog catalog = catalog(Dialect.TSQL, """
                CREATE TABLE dbo.users (a INT);
                CREATE TABLE users (b INT);
                """);
        assertThat(catalog.tables()).containsOnlyKeys("users");
        assertThat(catalog.table("users").orElseThrow().columns())
                .extracting(c -> c.name().value())
                .containsExactly("b");
    }

    @Test
    void autoIncrementIsCarriedIntoColumnInfo() {
        Catalog catalog = catalog(Dialect.POSTGRESQL, "CREATE TABLE t (id SERIAL, x INT);");
        TableSchema t = catalog.table("t").orElseThrow();
        assertThat(t.column("id").orElseThrow().autoIncrement()).isTrue();
        assertThat(t.column("id").orElseThrow().type().type()).isEqualTo(GenericType.INTEGER);
        assertThat(t.column("x").orElseThrow().autoIncrement()).isFalse();
    }

    @Test
    void mysqlTinyintOneLandsAsBooleanInCatalog() {
        Catalog catalog = catalog(Dialect.MYSQL,
                "CREATE TABLE flags (active TINYINT(1) DEFAULT 1);");
        assertThat(catalog.table("flags").orElseThrow().column("active").orElseThrow()
                .type().type()).isEqualTo(GenericType.BOOLEAN);
    }

    private static Catalog catalog(Dialect dialect, String sql) {
        return CatalogBuilder.build(AstBuilderFacade.buildScript(sql, dialect));
    }
}
