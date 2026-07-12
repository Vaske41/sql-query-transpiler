package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class RenderTargetFunctionsRuleTest {

    private static final String DDL =
            "CREATE TABLE products (name VARCHAR(50), price DECIMAL(10,2));";

    private final Rule rule = new RenderTargetFunctionsRule();

    @Test
    void canonicalNamesRenameForTSql() {
        TranslationResult r = runRule(rule,
                "SELECT NOW(), CHAR_LENGTH(name) FROM t;", Dialect.MYSQL, Dialect.TSQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("GETDATE");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("LEN");
    }

    @Test
    void twoArgSubstringGainsLengthArgForTSql() {
        TranslationResult r = runRule(rule,
                "SELECT SUBSTRING(name, 2) FROM t;", Dialect.MYSQL, Dialect.TSQL);
        FunctionCall substring = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(substring.args()).hasSize(3);
        FunctionCall len = (FunctionCall) substring.args().get(2);
        assertThat(len.name()).isEqualTo("LEN");
    }

    @Test
    void yearMonthDayBecomeDatePartForPostgres() {
        TranslationResult r = runRule(rule,
                "SELECT YEAR(d), DAY(d) FROM t;", Dialect.MYSQL, Dialect.POSTGRESQL);
        FunctionCall year = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(year.name()).isEqualTo("DATE_PART");
        assertThat(((StringLiteral) year.args().get(0)).value()).isEqualTo("year");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).args()).hasSize(2);
    }

    @Test
    void concatChainBecomesConcatCallForMySql() {
        TranslationResult r = runRule(rule,
                "SELECT a || b || c FROM t;", Dialect.POSTGRESQL, Dialect.MYSQL);
        FunctionCall concat = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(concat.name()).isEqualTo("CONCAT");
        assertThat(concat.args()).hasSize(3);
    }

    @Test
    void nonStringConcatOperandGetsCastForTSql() {
        TranslationResult r = runRule(rule,
                DDL + "SELECT 'x' || price FROM products;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        BinaryOp concat = (BinaryOp) selectExpr(r.script(), 1, 0);
        CastExpression cast = (CastExpression) concat.right();
        assertThat(cast.targetType().type()).isEqualTo(GenericType.NVARCHAR);
        assertThat(cast.targetType().length().orElseThrow()).isEqualTo(new MaxLength());
    }

    @Test
    void unresolvedConcatOperandWarnsForTSql() {
        TranslationResult r = runRule(rule, "SELECT a || b FROM unknown_table;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("CONCAT_OPERAND_UNRESOLVED"));
    }

    @Test
    void unknownFunctionPassesThroughWithWarning() {
        TranslationResult r = runRule(rule, "SELECT FOO(x) FROM t;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("FOO");
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("FUNCTION_PASSTHROUGH"));
    }

    @Test
    void aggregatesNeverWarn() {
        TranslationResult r = runRule(rule,
                "SELECT COUNT(*), MAX(price), SUM(price) FROM products;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(r.report().warnings()).isEmpty();
    }
}
