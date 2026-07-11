package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

/**
 * Phase 3 entry point: SQL text + source dialect → dialect-agnostic {@link Script},
 * or {@link rs.etf.sqltranslator.core.ParseException} /
 * {@link rs.etf.sqltranslator.core.UnsupportedFeatureException}.
 * Parse trees from {@link ParserFacade} are throwaway — this is the seam Phase 4's
 * {@code Translator} consumes.
 */
public final class AstBuilderFacade {

    private AstBuilderFacade() {
    }

    public static Script buildScript(String sql, Dialect dialect) {
        ParserRuleContext tree = ParserFacade.parseScript(sql, dialect);
        return switch (dialect) {
            case TSQL -> (Script) new TSqlAstBuilder().visit(tree);
            case MYSQL -> (Script) new MySqlAstBuilder().visit(tree);
            case POSTGRESQL -> (Script) new PostgreSqlAstBuilder().visit(tree);
        };
    }
}
