package rs.etf.sqltranslator.core;

/**
 * Thrown when the input uses a construct outside the supported v1 subset.
 * Refusal with a precise position always beats a silent wrong translation.
 */
public class UnsupportedFeatureException extends RuntimeException {

    private final String construct;
    private final SourcePosition position;

    public UnsupportedFeatureException(String construct, SourcePosition position) {
        super("Unsupported feature at " + position + ": " + construct);
        this.construct = construct;
        this.position = position;
    }

    public String construct() {
        return construct;
    }

    public SourcePosition position() {
        return position;
    }
}
