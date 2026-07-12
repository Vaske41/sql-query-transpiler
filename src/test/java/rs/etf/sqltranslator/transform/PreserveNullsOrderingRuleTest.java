package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.NullsOrder;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.ast.SortDirection;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class PreserveNullsOrderingRuleTest {

    private final Rule rule = new PreserveNullsOrderingRule();

    private static OrderItem firstOrderItem(TranslationResult r) {
        SelectStatement select = (SelectStatement) r.script().statements().get(0);
        return select.query().orderBy().get(0);
    }

    @Test
    void mysqlAscGainsNullsFirstForPostgres() {
        TranslationResult r = runRule(rule, "SELECT id FROM t ORDER BY id;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        OrderItem item = firstOrderItem(r);
        assertThat(item.direction()).isEqualTo(SortDirection.ASC);
        assertThat(item.nulls()).contains(NullsOrder.FIRST);
    }

    @Test
    void mysqlDescGainsNullsLastForPostgres() {
        TranslationResult r = runRule(rule, "SELECT id FROM t ORDER BY id DESC;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        OrderItem item = firstOrderItem(r);
        assertThat(item.direction()).isEqualTo(SortDirection.DESC);
        assertThat(item.nulls()).contains(NullsOrder.LAST);
    }

    @Test
    void postgresSourceIsLeftAlone() {
        TranslationResult r = runRule(rule, "SELECT id FROM t ORDER BY id;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL);
        assertThat(firstOrderItem(r).nulls()).isEmpty();
    }

    @Test
    void existingNullsClauseIsPreserved() {
        TranslationResult r = runRule(rule,
                "SELECT id FROM t ORDER BY id NULLS LAST;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        // Rule no-ops for non-PG targets; DropNullsOrderingRule owns the reverse.
        assertThat(firstOrderItem(r).nulls()).contains(NullsOrder.LAST);
    }
}
