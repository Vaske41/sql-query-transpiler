package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.CatalogBuilder;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import java.util.List;

/** Shared plumbing for rule tests: parse → context → single rule → result. */
final class TransformTestSupport {

    private TransformTestSupport() {
    }

    static TranslationResult runRule(Rule rule, String sql, Dialect source, Dialect target) {
        Script script = AstBuilderFacade.buildScript(sql, source);
        TranslationReport report = new TranslationReport();
        TranslationContext ctx = new TranslationContext(source, target,
                CatalogBuilder.build(script), report);
        return new TranslationResult(rule.apply(script, ctx), report);
    }

    /** Expression of the itemIndex-th select item of the stmtIndex-th statement. */
    static Expression selectExpr(Script script, int stmtIndex, int itemIndex) {
        SelectStatement select = (SelectStatement) script.statements().get(stmtIndex);
        List<rs.etf.sqltranslator.ast.SelectItem> items = select.query().first().items();
        return ((SelectExpr) items.get(itemIndex)).expr();
    }
}
