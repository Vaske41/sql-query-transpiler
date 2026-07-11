package rs.etf.sqltranslator.ast;

/** The MAX length of T-SQL NVARCHAR(MAX)/VARCHAR(MAX). Position-free like {@link DataType}. */
public record MaxLength() implements TypeLength {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitMaxLength(this);
    }
}
