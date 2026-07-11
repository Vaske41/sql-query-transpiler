package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** A dotted name of one to three parts; more than three parts is refused at build time. */
public record QualifiedName(List<Identifier> parts, SourcePosition pos) implements AstNode {

    public QualifiedName {
        parts = List.copyOf(parts);
    }

    /** The last (least-qualified) part — the object's own name. */
    public Identifier last() {
        return parts.get(parts.size() - 1);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitQualifiedName(this);
    }
}
