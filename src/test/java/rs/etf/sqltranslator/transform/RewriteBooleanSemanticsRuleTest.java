package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.ast.UnaryOp;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class RewriteBooleanSemanticsRuleTest {

    private static final String DDL =
            "CREATE TABLE flags (active BOOLEAN, qty INT);";

    private final Rule rule = new RewriteBooleanSemanticsRule();

    private static Expression whereOf(Script script, int stmtIndex) {
        SelectStatement select = (SelectStatement) script.statements().get(stmtIndex);
        return select.query().first().where().orElseThrow();
    }

    @Test
    void bareBooleanColumnBecomesNeqZeroForTsql() {
        TranslationResult r = runRule(rule, DDL + "SELECT qty FROM flags WHERE active;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        assertThat(where.op()).isEqualTo(BinaryOperator.NEQ);
        assertThat(where.left()).isInstanceOf(ColumnRef.class);
        assertThat(((NumericLiteral) where.right()).text()).isEqualTo("0");
    }

    @Test
    void bareResolvedBooleanStaysBareForPostgres() {
        TranslationResult r = runRule(rule, DDL + "SELECT qty FROM flags WHERE active;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(whereOf(r.script(), 1)).isInstanceOf(ColumnRef.class);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void bareNumericColumnGetsTruthinessWrapForPostgres() {
        TranslationResult r = runRule(rule, DDL + "SELECT qty FROM flags WHERE qty;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) whereOf(r.script(), 1)).op()).isEqualTo(BinaryOperator.NEQ);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void bareUnresolvedColumnWarnsForPostgres() {
        TranslationResult r = runRule(rule, "SELECT a FROM unknown_table WHERE a;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) whereOf(r.script(), 0)).op()).isEqualTo(BinaryOperator.NEQ);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("BOOLEAN_CONTEXT_UNRESOLVED"));
    }

    @Test
    void andOrNotDescendIntoOperands() {
        TranslationResult r = runRule(rule,
                DDL + "SELECT qty FROM flags WHERE active AND NOT active;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        BinaryOp and = (BinaryOp) whereOf(r.script(), 1);
        assertThat(((BinaryOp) and.left()).op()).isEqualTo(BinaryOperator.NEQ);
        UnaryOp not = (UnaryOp) and.right();
        assertThat(((BinaryOp) not.operand()).op()).isEqualTo(BinaryOperator.NEQ);
    }

    @Test
    void booleanLiteralBecomesNumericEverywhereForTsql() {
        TranslationResult r = runRule(rule, "SELECT TRUE, FALSE;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        assertThat(((NumericLiteral) selectExpr(r.script(), 0, 0)).text()).isEqualTo("1");
        assertThat(((NumericLiteral) selectExpr(r.script(), 0, 1)).text()).isEqualTo("0");
    }

    @Test
    void booleanColumnComparedToOneOrZeroSimplifiesForPostgres() {
        TranslationResult r = runRule(rule,
                DDL + "SELECT qty FROM flags WHERE active = 1;"
                        + "SELECT qty FROM flags WHERE active = 0;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(whereOf(r.script(), 1)).isInstanceOf(ColumnRef.class);
        UnaryOp not = (UnaryOp) whereOf(r.script(), 2);
        assertThat(not.operand()).isInstanceOf(ColumnRef.class);
    }

    @Test
    void numericAssignmentsToBooleanColumnsBecomeBooleanLiteralsForPostgres() {
        TranslationResult r = runRule(rule,
                DDL + "INSERT INTO flags (active, qty) VALUES (1, 5);"
                        + "INSERT INTO flags VALUES (0, 9);"
                        + "UPDATE flags SET active = 1;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        InsertStatement named = (InsertStatement) r.script().statements().get(1);
        assertThat(named.rows().get(0).get(0)).isInstanceOf(BooleanLiteral.class);
        assertThat(named.rows().get(0).get(1)).isInstanceOf(NumericLiteral.class);
        InsertStatement positional = (InsertStatement) r.script().statements().get(2);
        assertThat(positional.rows().get(0).get(0)).isInstanceOf(BooleanLiteral.class);
        UpdateStatement update = (UpdateStatement) r.script().statements().get(3);
        assertThat(update.assignments().get(0).value()).isInstanceOf(BooleanLiteral.class);
    }

    @Test
    void booleanDefaultHarmonizesForPostgres() {
        TranslationResult r = runRule(rule,
                "CREATE TABLE t (active BOOLEAN DEFAULT 1);",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        CreateTableStatement create = (CreateTableStatement) r.script().statements().get(0);
        assertThat(create.columns().get(0).defaultValue().orElseThrow())
                .isInstanceOf(BooleanLiteral.class);
    }
}
