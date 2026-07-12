package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.NullsOrder;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 rewrite-evidence suite: the standard engine must produce observable
 * AST deltas (and stable warning codes) on flagship catalog/normalize paths —
 * not merely run without throwing (see {@link RuleEngineCorpusTest}).
 */
class RuleEngineRewriteEvidenceTest {

    private static TranslationResult translate(String sql, Dialect source, Dialect target) {
        return RuleEngine.standard().run(AstBuilderFacade.buildScript(sql, source), source, target);
    }

    @Test
    void castInsertionRewritesAndWarns() {
        TranslationResult r = translate(
                "CREATE TABLE products (name VARCHAR(50));"
                        + "SELECT name FROM products WHERE name = 5;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) ((SelectStatement) r.script().statements().get(1))
                .query().first().where().orElseThrow();
        assertThat(where.right()).isInstanceOf(CastExpression.class);
        assertThat(r.report().warnings()).anyMatch(w -> w.code().equals("CAST_INSERTED"));
    }

    @Test
    void tsqlPlusBecomesConcatThenSurvivesRender() {
        TranslationResult r = translate(
                "SELECT 'a' + 'b';", Dialect.TSQL, Dialect.MYSQL);
        assertThat(new AstDumper().dump(r.script())).contains("CONCAT");
    }

    @Test
    void booleanBareColumnWrapsForTsql() {
        TranslationResult r = translate(
                "CREATE TABLE flags (active BOOLEAN);"
                        + "SELECT active FROM flags WHERE active;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        BinaryOp where = (BinaryOp) ((SelectStatement) r.script().statements().get(1))
                .query().first().where().orElseThrow();
        assertThat(where.op()).isEqualTo(BinaryOperator.NEQ);
    }

    @Test
    void narrowTypesWidensTinyintForPostgres() {
        TranslationResult r = translate(
                "CREATE TABLE t (a TINYINT);", Dialect.MYSQL, Dialect.POSTGRESQL);
        CreateTableStatement create = (CreateTableStatement) r.script().statements().get(0);
        assertThat(create.columns().get(0).type().type()).isEqualTo(GenericType.SMALLINT);
        assertThat(r.report().warnings()).anyMatch(w -> w.code().equals("TINYINT_WIDENED"));
    }

    @Test
    void mysqlOrderByGainsNullsFirstOnPostgres() {
        TranslationResult r = translate(
                "SELECT id FROM t ORDER BY id;", Dialect.MYSQL, Dialect.POSTGRESQL);
        OrderItem item = ((SelectStatement) r.script().statements().get(0))
                .query().orderBy().get(0);
        assertThat(item.nulls()).contains(NullsOrder.FIRST);
    }

    @Test
    void unresolvedCastShapeWarns() {
        TranslationResult r = translate(
                "SELECT a FROM unknown_table WHERE a = 5;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(r.report().warnings()).anyMatch(w -> w.code().equals("CAST_UNRESOLVED"));
        BinaryOp where = (BinaryOp) ((SelectStatement) r.script().statements().get(0))
                .query().first().where().orElseThrow();
        assertThat(where.right()).isNotInstanceOf(CastExpression.class);
        assertThat(where.left()).isInstanceOf(ColumnRef.class);
    }

    @Test
    void sameDialectDoesNotGuaranteeDumpIdentity() {
        // Normalize→render can rename (LENGTH→CHAR_LENGTH→LENGTH) or leave
        // structural traces; identity is not a Phase 4 invariant when source==target.
        String sql = "SELECT LENGTH(name) FROM t;";
        var first = translate(sql, Dialect.MYSQL, Dialect.MYSQL);
        var second = translate(sql, Dialect.MYSQL, Dialect.MYSQL);
        AstDumper dumper = new AstDumper();
        assertThat(dumper.dump(first.script())).isEqualTo(dumper.dump(second.script()));
    }
}
