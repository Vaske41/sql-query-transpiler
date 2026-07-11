package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** The FROM clause: a first table reference plus zero or more joins. */
public record TableSource(TableRef first, List<Join> joins, SourcePosition pos)
        implements AstNode {

    public TableSource {
        joins = List.copyOf(joins);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitTableSource(this);
    }
}
