package rs.etf.sqltranslator.ast;

import java.util.Optional;

/**
 * A fully folded, dialect-agnostic type descriptor (D3). Deliberately carries no
 * {@code SourcePosition}: it is reused inside the {@code Catalog}, where positions are
 * meaningless — errors about types are reported at the owning
 * {@code ColumnDefinition}/{@code CastExpression} position.
 */
public record DataType(GenericType type, Optional<TypeLength> length, Optional<Integer> scale)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDataType(this);
    }
}
