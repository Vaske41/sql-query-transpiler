package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class DropNullsOrderingRuleTest {

    private static final String SQL =
            "SELECT id FROM t ORDER BY a DESC NULLS LAST, b;";

    private final Rule rule = new DropNullsOrderingRule();

    private static OrderItem orderItem(Script script, int index) {
        return ((SelectStatement) script.statements().get(0)).query().orderBy().get(index);
    }

    @Test
    void nullsOrderingIsDroppedWithWarningForMySql() {
        TranslationResult r = runRule(rule, SQL, Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(orderItem(r.script(), 0).nulls()).isEmpty();
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("NULLS_ORDERING_DROPPED"));
    }

    @Test
    void nullsOrderingIsKeptForPostgres() {
        TranslationResult r = runRule(rule, SQL, Dialect.POSTGRESQL, Dialect.POSTGRESQL);
        assertThat(orderItem(r.script(), 0).nulls()).isPresent();
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void itemsWithoutNullsOrderingNeverWarn() {
        TranslationResult r = runRule(rule, "SELECT id FROM t ORDER BY b;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        assertThat(r.report().warnings()).isEmpty();
    }
}
