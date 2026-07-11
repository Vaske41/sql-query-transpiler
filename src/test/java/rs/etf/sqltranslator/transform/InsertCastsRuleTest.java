package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BetweenPredicate;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.InListPredicate;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class InsertCastsRuleTest {

    private static final String DDL =
            "CREATE TABLE products (name VARCHAR(50), price DECIMAL(10,2));";

    private final Rule rule = new InsertCastsRule();

    /** WHERE expression of the statement at stmtIndex. */
    private static Expression whereOf(Script script, int stmtIndex) {
        SelectStatement select = (SelectStatement) script.statements().get(stmtIndex);
        return select.query().first().where().orElseThrow();
    }

    @Test
    void numericLiteralAgainstVarcharColumnGetsCastForPostgres() {
        TranslationResult r = runRule(rule, DDL + "SELECT name FROM products WHERE name = 5;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        CastExpression cast = (CastExpression) where.right();
        assertThat(cast.targetType().type()).isEqualTo(GenericType.VARCHAR);
        assertThat(r.report().warnings()).anyMatch(w -> w.code().equals("CAST_INSERTED"));
    }

    @Test
    void stringLiteralAgainstDecimalColumnGetsCastForPostgres() {
        TranslationResult r = runRule(rule, DDL + "SELECT name FROM products WHERE price > '10';",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        assertThat(((CastExpression) where.right()).targetType().type())
                .isEqualTo(GenericType.DECIMAL);
    }

    @Test
    void matchingFamiliesAreLeftAlone() {
        TranslationResult r = runRule(rule, DDL + "SELECT name FROM products WHERE name = 'x';",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        assertThat(where.right()).isNotInstanceOf(CastExpression.class);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void unresolvedColumnIsLeftAloneWithoutWarning() {
        TranslationResult r = runRule(rule, "SELECT a FROM unknown_table WHERE a = 5;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 0);
        assertThat(where.right()).isNotInstanceOf(CastExpression.class);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void tsqlTargetOnlyWarns() {
        TranslationResult r = runRule(rule, DDL + "SELECT name FROM products WHERE name = 5;",
                Dialect.MYSQL, Dialect.TSQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        assertThat(where.right()).isNotInstanceOf(CastExpression.class);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("IMPLICIT_CONVERSION"));
    }

    @Test
    void betweenBoundsAndInListItemsGetCasts() {
        TranslationResult r = runRule(rule,
                DDL + "SELECT name FROM products WHERE price BETWEEN '1' AND '9';"
                        + "SELECT name FROM products WHERE name IN (1, 'x');",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BetweenPredicate between = (BetweenPredicate) whereOf(r.script(), 1);
        assertThat(between.low()).isInstanceOf(CastExpression.class);
        assertThat(between.high()).isInstanceOf(CastExpression.class);
        InListPredicate in = (InListPredicate) whereOf(r.script(), 2);
        assertThat(in.items().get(0)).isInstanceOf(CastExpression.class);
        assertThat(in.items().get(1)).isNotInstanceOf(CastExpression.class);
    }
}
