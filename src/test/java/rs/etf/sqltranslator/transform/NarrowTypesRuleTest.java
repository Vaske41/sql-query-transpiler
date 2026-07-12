package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.FixedLength;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class NarrowTypesRuleTest {

    private final Rule rule = new NarrowTypesRule();

    private static DataType columnType(Script script, int stmtIndex, int columnIndex) {
        CreateTableStatement create =
                (CreateTableStatement) script.statements().get(stmtIndex);
        return create.columns().get(columnIndex).type();
    }

    @Test
    void nvarcharNarrowsForMySqlAndPostgres() {
        String ddl = "CREATE TABLE t (a NVARCHAR(50), b NVARCHAR(MAX), c VARCHAR(MAX));";
        TranslationResult r = runRule(rule, ddl, Dialect.TSQL, Dialect.MYSQL);
        assertThat(columnType(r.script(), 0, 0).type()).isEqualTo(GenericType.VARCHAR);
        assertThat(columnType(r.script(), 0, 0).length().orElseThrow())
                .isEqualTo(new FixedLength(50));
        assertThat(columnType(r.script(), 0, 1).type()).isEqualTo(GenericType.TEXT);
        assertThat(columnType(r.script(), 0, 1).length()).isEmpty();
        assertThat(columnType(r.script(), 0, 2).type()).isEqualTo(GenericType.TEXT);
    }

    @Test
    void textBecomesNvarcharMaxForTSql() {
        TranslationResult r = runRule(rule, "CREATE TABLE t (d TEXT);",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(columnType(r.script(), 0, 0).type()).isEqualTo(GenericType.NVARCHAR);
        assertThat(columnType(r.script(), 0, 0).length().orElseThrow())
                .isEqualTo(new MaxLength());
    }

    @Test
    void tinyintWidensToSmallintForPostgresWithWarning() {
        TranslationResult r = runRule(rule, "CREATE TABLE t (a TINYINT);",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(columnType(r.script(), 0, 0).type()).isEqualTo(GenericType.SMALLINT);
        assertThat(r.report().warnings()).anyMatch(w -> w.code().equals("TINYINT_WIDENED"));
    }

    @Test
    void tinyintAcrossTsqlMysqlWarnsAboutSignedness() {
        TranslationResult r = runRule(rule, "CREATE TABLE t (a TINYINT);",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(columnType(r.script(), 0, 0).type()).isEqualTo(GenericType.TINYINT);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("TINYINT_SIGNEDNESS"));
    }

    @Test
    void castTargetTypesNarrowToo() {
        TranslationResult r = runRule(rule, "SELECT CAST(x AS NVARCHAR(20)) FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        CastExpression cast = (CastExpression) selectExpr(r.script(), 0, 0);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.VARCHAR);
    }
}
