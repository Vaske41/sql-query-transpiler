package rs.etf.sqltranslator.ast;

/** Join flavor; CROSS implies an empty ON condition. */
public enum JoinKind { INNER, LEFT, RIGHT, FULL, CROSS }
