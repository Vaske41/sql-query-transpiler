package rs.etf.sqltranslator.ast;

/**
 * The dialect-agnostic type system (D3). Dialect type names fold <em>into</em> it at
 * build time and render <em>out of</em> it in Phase 5 codegen; no raw type string
 * survives into the AST.
 */
public enum GenericType {
    TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, FLOAT, DOUBLE,
    CHAR, VARCHAR, NVARCHAR, TEXT, BOOLEAN, DATE, TIME, TIMESTAMP, BLOB
}
