package rs.etf.sqltranslator.ast;

/**
 * Root of the dialect-agnostic AST (the Composite). Every node dispatches through
 * the classic GoF Visitor — no switch-based dispatch anywhere in the codebase (D1).
 */
public interface AstNode {

    <R> R accept(AstVisitor<R> visitor);
}
