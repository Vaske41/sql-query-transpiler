package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** One {@code UNION [ALL] querySpecification} arm of a {@link Query}. */
public record UnionArm(boolean all, QuerySpecification spec, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUnionArm(this);
    }
}
