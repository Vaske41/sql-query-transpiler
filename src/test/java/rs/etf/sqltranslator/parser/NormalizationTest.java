package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.NullsOrder;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.ast.SetQuantifier;
import rs.etf.sqltranslator.ast.SortDirection;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.UnionArm;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * One test per row of the spec's §2.2 normalization table: syntax unification the
 * builders perform so the AST is born dialect-agnostic (D3/D11). Escape rules are
 * exercised against the Phase 2 hostile inputs.
 */
class NormalizationTest {

    // ------------------------------------------------------------------
    // T-SQL row limits: TOP / OFFSET-FETCH
    // ------------------------------------------------------------------

    @Test
    void tsqlTopLiteralBecomesRowLimitCount() {
        RowLimit limit = limit("SELECT TOP 10 id FROM users;", Dialect.TSQL);
        assertThat(((NumericLiteral) limit.count().orElseThrow()).text()).isEqualTo("10");
        assertThat(limit.offset()).isEmpty();
    }

    @Test
    void tsqlTopParenthesizedExpressionBecomesRowLimitCount() {
        RowLimit limit = limit("SELECT TOP (5) id FROM users;", Dialect.TSQL);
        assertThat(((NumericLiteral) limit.count().orElseThrow()).text()).isEqualTo("5");
    }

    @Test
    void tsqlOffsetFetchBecomesRowLimit() {
        RowLimit limit = limit(
                "SELECT id FROM users ORDER BY id OFFSET 10 ROWS FETCH NEXT 5 ROWS ONLY;",
                Dialect.TSQL);
        assertThat(((NumericLiteral) limit.count().orElseThrow()).text()).isEqualTo("5");
        assertThat(((NumericLiteral) limit.offset().orElseThrow()).text()).isEqualTo("10");
    }

    @Test
    void tsqlOffsetWithoutFetchLeavesCountEmpty() {
        RowLimit limit = limit("SELECT id FROM users ORDER BY id OFFSET 10 ROWS;",
                Dialect.TSQL);
        assertThat(limit.count()).isEmpty();
        assertThat(((NumericLiteral) limit.offset().orElseThrow()).text()).isEqualTo("10");
    }

    @Test
    void tsqlTopInsideUnionIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> build(
                        "SELECT TOP 5 id FROM a UNION SELECT id FROM b;", Dialect.TSQL))
                .withMessageContaining("TOP inside UNION");
    }

    @Test
    void tsqlTopCombinedWithOffsetFetchIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> build(
                        "SELECT TOP 5 id FROM a ORDER BY id OFFSET 10 ROWS;", Dialect.TSQL))
                .withMessageContaining("TOP combined with OFFSET/FETCH");
    }

    // ------------------------------------------------------------------
    // MySQL / PostgreSQL row limits
    // ------------------------------------------------------------------

    @Test
    void mysqlLimitCommaFormSwapsOperands() {
        RowLimit limit = limit("SELECT id FROM users LIMIT 20, 10;", Dialect.MYSQL);
        assertThat(((NumericLiteral) limit.count().orElseThrow()).text()).isEqualTo("10");
        assertThat(((NumericLiteral) limit.offset().orElseThrow()).text()).isEqualTo("20");
    }

    @Test
    void mysqlLimitOffsetForm() {
        RowLimit limit = limit("SELECT id FROM users LIMIT 10 OFFSET 20;", Dialect.MYSQL);
        assertThat(((NumericLiteral) limit.count().orElseThrow()).text()).isEqualTo("10");
        assertThat(((NumericLiteral) limit.offset().orElseThrow()).text()).isEqualTo("20");
    }

    @Test
    void mysqlBareLimitLeavesOffsetEmpty() {
        RowLimit limit = limit("SELECT id FROM users LIMIT 10;", Dialect.MYSQL);
        assertThat(((NumericLiteral) limit.count().orElseThrow()).text()).isEqualTo("10");
        assertThat(limit.offset()).isEmpty();
    }

    @Test
    void pgOffsetBeforeLimitNormalizes() {
        RowLimit limit = limit("SELECT id FROM users OFFSET 5 LIMIT 10;",
                Dialect.POSTGRESQL);
        assertThat(((NumericLiteral) limit.count().orElseThrow()).text()).isEqualTo("10");
        assertThat(((NumericLiteral) limit.offset().orElseThrow()).text()).isEqualTo("5");
    }

    // ------------------------------------------------------------------
    // || : MySQL logical OR vs PostgreSQL concat
    // ------------------------------------------------------------------

    @Test
    void mysqlPipesFoldToLogicalOr() {
        BinaryOp op = (BinaryOp) selectItem("SELECT a || b FROM t;", Dialect.MYSQL);
        assertThat(op.op()).isEqualTo(BinaryOperator.OR);
    }

    @Test
    void pgPipesFoldToConcat() {
        BinaryOp op = (BinaryOp) selectItem("SELECT a || b FROM t;", Dialect.POSTGRESQL);
        assertThat(op.op()).isEqualTo(BinaryOperator.CONCAT);
    }

    // ------------------------------------------------------------------
    // CONVERT → CAST
    // ------------------------------------------------------------------

    @Test
    void tsqlTwoArgConvertFoldsToCast() {
        CastExpression cast =
                (CastExpression) selectItem("SELECT CONVERT(INT, price) FROM t;", Dialect.TSQL);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.INTEGER);
    }

    // ------------------------------------------------------------------
    // String unescaping (Phase 2 hostile inputs)
    // ------------------------------------------------------------------

    @Test
    void tsqlNationalStringAndQuoteDoubling() {
        StringLiteral n = (StringLiteral) selectItem("SELECT N'O''Brien' FROM t;", Dialect.TSQL);
        assertThat(n.value()).isEqualTo("O'Brien");
        assertThat(n.national()).isTrue();
        StringLiteral plain = (StringLiteral) selectItem("SELECT 'O''Brien' FROM t;",
                Dialect.TSQL);
        assertThat(plain.value()).isEqualTo("O'Brien");
        assertThat(plain.national()).isFalse();
    }

    @Test
    void mysqlBackslashAndDoublingEscapes() {
        assertThat(((StringLiteral) selectItem("SELECT 'O\\'Brien' FROM t;", Dialect.MYSQL))
                .value()).isEqualTo("O'Brien");
        assertThat(((StringLiteral) selectItem("SELECT 'O''Brien' FROM t;", Dialect.MYSQL))
                .value()).isEqualTo("O'Brien");
        assertThat(((StringLiteral) selectItem("SELECT \"say \\\"hi\\\"\" FROM t;",
                Dialect.MYSQL)).value()).isEqualTo("say \"hi\"");
        assertThat(((StringLiteral) selectItem("SELECT 'a\\nb' FROM t;", Dialect.MYSQL))
                .value()).isEqualTo("a\nb");
        // \% and \_ keep the backslash — they are LIKE-pattern escapes in MySQL.
        assertThat(((StringLiteral) selectItem("SELECT '50\\%' FROM t;", Dialect.MYSQL))
                .value()).isEqualTo("50\\%");
    }

    @Test
    void pgDoublingAndEscapeStringForms() {
        assertThat(((StringLiteral) selectItem("SELECT 'O''Brien' FROM t;",
                Dialect.POSTGRESQL)).value()).isEqualTo("O'Brien");
        assertThat(((StringLiteral) selectItem("SELECT E'tab\\there' FROM t;",
                Dialect.POSTGRESQL)).value()).isEqualTo("tab\there");
        assertThat(((StringLiteral) selectItem("SELECT E'O\\'Brien' FROM t;",
                Dialect.POSTGRESQL)).value()).isEqualTo("O'Brien");
    }

    // ------------------------------------------------------------------
    // Identifier unescaping and case preservation (D6)
    // ------------------------------------------------------------------

    @Test
    void tsqlBracketAndDoubleQuoteIdentifierEscapes() {
        SelectExpr item = (SelectExpr) query("SELECT [we]]ird], \"a\"\"b\" FROM t;",
                Dialect.TSQL).first().items().get(0);
        Identifier weird = ((rs.etf.sqltranslator.ast.ColumnRef) item.expr()).name().last();
        assertThat(weird.value()).isEqualTo("we]ird");
        assertThat(weird.quoted()).isTrue();
        SelectExpr second = (SelectExpr) query("SELECT [we]]ird], \"a\"\"b\" FROM t;",
                Dialect.TSQL).first().items().get(1);
        assertThat(((rs.etf.sqltranslator.ast.ColumnRef) second.expr()).name().last().value())
                .isEqualTo("a\"b");
    }

    @Test
    void mysqlBacktickIdentifierEscape() {
        Identifier id = ((rs.etf.sqltranslator.ast.ColumnRef)
                selectItem("SELECT `we``ird` FROM t;", Dialect.MYSQL)).name().last();
        assertThat(id.value()).isEqualTo("we`ird");
        assertThat(id.quoted()).isTrue();
    }

    @Test
    void pgDoubleQuoteIdentifierEscapePreservesCase() {
        Identifier id = ((rs.etf.sqltranslator.ast.ColumnRef)
                selectItem("SELECT \"We\"\"Ird\" FROM t;", Dialect.POSTGRESQL)).name().last();
        assertThat(id.value()).isEqualTo("We\"Ird");
        assertThat(id.quoted()).isTrue();
    }

    // ------------------------------------------------------------------
    // Auto-increment folds
    // ------------------------------------------------------------------

    @Test
    void tsqlIdentityOneOneFoldsToFlag() {
        ColumnDefinition id = column(
                "CREATE TABLE t (id INT IDENTITY(1,1) PRIMARY KEY);", Dialect.TSQL, 0);
        assertThat(id.autoIncrement()).isTrue();
        assertThat(id.primaryKey()).isTrue();
    }

    @Test
    void tsqlIdentityOtherSeedIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> build("CREATE TABLE t (id INT IDENTITY(5,10));",
                        Dialect.TSQL))
                .withMessageContaining("IDENTITY(5,10)");
    }

    @Test
    void mysqlAutoIncrementFoldsToFlag() {
        assertThat(column("CREATE TABLE t (id INT AUTO_INCREMENT);", Dialect.MYSQL, 0)
                .autoIncrement()).isTrue();
    }

    @Test
    void pgGeneratedAsIdentityFoldsToFlag() {
        assertThat(column("CREATE TABLE t (id INTEGER GENERATED ALWAYS AS IDENTITY);",
                Dialect.POSTGRESQL, 0).autoIncrement()).isTrue();
        assertThat(column("CREATE TABLE t (id INTEGER GENERATED BY DEFAULT AS IDENTITY);",
                Dialect.POSTGRESQL, 0).autoIncrement()).isTrue();
    }

    // ------------------------------------------------------------------
    // PG NULLS FIRST/LAST
    // ------------------------------------------------------------------

    @Test
    void pgNullsOrderIsCarried() {
        Query query = query("SELECT id FROM t ORDER BY id DESC NULLS LAST;",
                Dialect.POSTGRESQL);
        assertThat(query.orderBy().get(0).direction()).isEqualTo(SortDirection.DESC);
        assertThat(query.orderBy().get(0).nulls()).contains(NullsOrder.LAST);
    }

    // ------------------------------------------------------------------
    // Function names, quantifiers, star
    // ------------------------------------------------------------------

    @Test
    void functionNamesAreUppercased() {
        FunctionCall call = (FunctionCall) selectItem("SELECT getdate() FROM t;", Dialect.TSQL);
        assertThat(call.name()).isEqualTo("GETDATE");
    }

    @Test
    void keywordNamedFunctionsParseAndUppercase() {
        FunctionCall call = (FunctionCall) selectItem("SELECT left(name, 3) FROM t;",
                Dialect.POSTGRESQL);
        assertThat(call.name()).isEqualTo("LEFT");
        assertThat(call.args()).hasSize(2);
    }

    @Test
    void countStarAndCountDistinct() {
        FunctionCall star = (FunctionCall) selectItem("SELECT COUNT(*) FROM t;", Dialect.MYSQL);
        assertThat(star.star()).isTrue();
        assertThat(star.args()).isEmpty();
        FunctionCall distinct = (FunctionCall) selectItem("SELECT COUNT(DISTINCT x) FROM t;",
                Dialect.MYSQL);
        assertThat(distinct.quantifier()).contains(SetQuantifier.DISTINCT);
        assertThat(distinct.star()).isFalse();
    }

    @Test
    void quotedFunctionNameIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> build("SELECT \"fn\"(x) FROM t;", Dialect.POSTGRESQL))
                .withMessageContaining("quoted function name");
    }

    // ------------------------------------------------------------------
    // Numeric literals (D5) and hex refusal
    // ------------------------------------------------------------------

    @Test
    void numericLiteralTextIsPreservedByteIdentically() {
        assertThat(((NumericLiteral) selectItem("SELECT 9.99 FROM t;", Dialect.POSTGRESQL))
                .text()).isEqualTo("9.99");
    }

    @Test
    void exponentLiteralsKeepLexicalTextAndAreDecimal() {
        NumericLiteral first = (NumericLiteral) selectItem("SELECT 1e5, 2.5E-3;",
                Dialect.MYSQL);
        assertThat(first.text()).isEqualTo("1e5");
        assertThat(first.decimal()).isTrue();
        NumericLiteral second = (NumericLiteral)
                ((SelectExpr) query("SELECT 1e5, 2.5E-3;", Dialect.MYSQL)
                        .first().items().get(1)).expr();
        assertThat(second.text()).isEqualTo("2.5E-3");
    }

    @Test
    void hexLiteralsAreRefusedUniformly() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> build("SELECT 0xFF;", Dialect.TSQL))
                .withMessageContaining("hex literal 0xFF");
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> build("SELECT 0xFF;", Dialect.MYSQL))
                .withMessageContaining("hex literal 0xFF");
    }

    // ------------------------------------------------------------------
    // Qualified names, booleans, union arms
    // ------------------------------------------------------------------

    @Test
    void fourPartQualifiedNameIsRefusedWithPosition() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> build("SELECT id FROM a.b.c.d;", Dialect.POSTGRESQL))
                .satisfies(e -> {
                    assertThat(e.construct()).isEqualTo("qualified name with 4 parts");
                    assertThat(e.position().line()).isEqualTo(1);
                });
    }

    @Test
    void booleanLiteralsBuildWhereTheDialectHasThem() {
        assertThat(((BooleanLiteral) selectItem("SELECT TRUE FROM t;", Dialect.POSTGRESQL))
                .value()).isTrue();
        assertThat(((BooleanLiteral) selectItem("SELECT FALSE FROM t;", Dialect.MYSQL))
                .value()).isFalse();
    }

    @Test
    void unionAllFlagsAlignWithTheirArms() {
        Query query = query("SELECT a FROM t UNION ALL SELECT a FROM u UNION SELECT a FROM v;",
                Dialect.POSTGRESQL);
        List<UnionArm> arms = query.unionArms();
        assertThat(arms).hasSize(2);
        assertThat(arms.get(0).all()).isTrue();
        assertThat(arms.get(1).all()).isFalse();
    }

    // ------------------------------------------------------------------
    // Navigation helpers
    // ------------------------------------------------------------------

    private static Script build(String sql, Dialect dialect) {
        return AstBuilderFacade.buildScript(sql, dialect);
    }

    private static Query query(String sql, Dialect dialect) {
        return ((SelectStatement) build(sql, dialect).statements().get(0)).query();
    }

    private static RowLimit limit(String sql, Dialect dialect) {
        return query(sql, dialect).limit().orElseThrow();
    }

    private static Expression selectItem(String sql, Dialect dialect) {
        return ((SelectExpr) query(sql, dialect).first().items().get(0)).expr();
    }

    private static ColumnDefinition column(String sql, Dialect dialect, int index) {
        return ((CreateTableStatement) build(sql, dialect).statements().get(0))
                .columns().get(index);
    }
}
