package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** A whole SQL script: the ordered statements of one input. */
public record Script(List<Statement> statements, SourcePosition pos) implements AstNode {

    public Script {
        statements = List.copyOf(statements);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitScript(this);
    }
}
