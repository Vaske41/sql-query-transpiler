package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class ResolveConcatRuleTest {

    private static final String DDL =
            "CREATE TABLE t (name VARCHAR(50), price DECIMAL(10,2));";

    private final Rule rule = new ResolveConcatRule();

    @Test
    void plusWithStringLiteralBecomesConcat() {
        TranslationResult r = runRule(rule, "SELECT 'Mr ' + name FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 0, 0)).op())
                .isEqualTo(BinaryOperator.CONCAT);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void plusWithCatalogStringColumnBecomesConcat() {
        TranslationResult r = runRule(rule, DDL + "SELECT name + name FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 1, 0)).op())
                .isEqualTo(BinaryOperator.CONCAT);
    }

    @Test
    void numericPlusStaysAddition() {
        TranslationResult r = runRule(rule, DDL + "SELECT price + 1 FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 1, 0)).op())
                .isEqualTo(BinaryOperator.ADD);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void unresolvedOperandsStayAdditionWithWarning() {
        TranslationResult r = runRule(rule, "SELECT a + b FROM unknown_table;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 0, 0)).op())
                .isEqualTo(BinaryOperator.ADD);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("AMBIGUOUS_PLUS"));
    }

    @Test
    void nonTsqlSourceIsUntouched() {
        TranslationResult r = runRule(rule, "SELECT 'a' + 1;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 0, 0)).op())
                .isEqualTo(BinaryOperator.ADD);
        assertThat(r.report().warnings()).isEmpty();
    }
}
