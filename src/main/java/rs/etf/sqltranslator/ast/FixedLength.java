package rs.etf.sqltranslator.ast;

/** A numeric length argument, e.g. VARCHAR(100). Position-free like {@link DataType}. */
public record FixedLength(int value) implements TypeLength {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitFixedLength(this);
    }
}
