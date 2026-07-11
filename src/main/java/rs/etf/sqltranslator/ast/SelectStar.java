package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** {@code *} or {@code t.*} in a SELECT list. */
public record SelectStar(Optional<QualifiedName> qualifier, SourcePosition pos)
        implements SelectItem {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitSelectStar(this);
    }
}
