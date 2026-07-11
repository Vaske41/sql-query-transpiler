package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class NormalizeSourceFunctionsRuleTest {

    private final Rule rule = new NormalizeSourceFunctionsRule();

    @Test
    void tsqlSpellingsFoldToCanonical() {
        TranslationResult r = runRule(rule,
                "SELECT GETDATE(), ISNULL(a, 0), LEN(name) FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("NOW");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("COALESCE");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 2)).name()).isEqualTo("CHAR_LENGTH");
    }

    @Test
    void mysqlSpellingsFoldToCanonical() {
        TranslationResult r = runRule(rule,
                "SELECT IFNULL(a, 0), SUBSTR(name, 2), CEIL(price), LENGTH(name) FROM t;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("COALESCE");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("SUBSTRING");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 2)).name()).isEqualTo("CEILING");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 3)).name()).isEqualTo("CHAR_LENGTH");
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("MYSQL_CHAR_LENGTH_ASSUMED"));
    }

    @Test
    void mysqlOneArgIsNullBecomesIsNullPredicate() {
        TranslationResult r = runRule(rule, "SELECT ISNULL(a) FROM t;",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(selectExpr(r.script(), 0, 0)).isInstanceOf(IsNullPredicate.class);
    }

    @Test
    void concatFunctionFoldsToLeftAssociativeConcatChain() {
        TranslationResult r = runRule(rule, "SELECT CONCAT(a, b, c) FROM t;",
                Dialect.MYSQL, Dialect.TSQL);
        BinaryOp outer = (BinaryOp) selectExpr(r.script(), 0, 0);
        assertThat(outer.op()).isEqualTo(BinaryOperator.CONCAT);
        assertThat(((BinaryOp) outer.left()).op()).isEqualTo(BinaryOperator.CONCAT);
    }

    @Test
    void postgresDatePartWithLiteralFieldFoldsToCanonicalExtractors() {
        TranslationResult r = runRule(rule,
                "SELECT DATE_PART('year', d), DATE_PART('month', d) FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("YEAR");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).args()).hasSize(1);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("MONTH");
    }

    @Test
    void aggregatesAndUnknownNamesPassUntouched() {
        TranslationResult r = runRule(rule, "SELECT COUNT(*), FOO(x) FROM t;",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("COUNT");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("FOO");
    }
}
