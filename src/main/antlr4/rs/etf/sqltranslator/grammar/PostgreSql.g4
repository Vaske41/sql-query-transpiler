grammar PostgreSql;

// =====================================================================
// 1. Parser rules (canonical — identical rule names across dialects)
// =====================================================================

script : statement (';' statement)* ';'? EOF ;

statement : selectStatement ;

selectStatement : queryExpression ;

queryExpression
    : querySpecification (UNION ALL? querySpecification)*
      orderByClause?
      rowLimitClause?
    ;

querySpecification
    : SELECT setQuantifier? selectItem (',' selectItem)*
      (FROM tableSource)?
      whereClause?
      groupByClause?
      havingClause?
    ;

setQuantifier : DISTINCT | ALL ;

selectItem
    : '*'                                   # selectStar
    | qualifiedName '.' '*'                 # selectQualifiedStar
    | expression (AS? identifier)?          # selectExpr
    ;

tableSource : tablePrimary joinedTable* ;

tablePrimary : qualifiedName (AS? identifier)? ;

joinedTable : joinType tablePrimary (ON expression)? ;

joinType
    : INNER? JOIN
    | LEFT OUTER? JOIN
    | RIGHT OUTER? JOIN
    | FULL OUTER? JOIN
    | CROSS JOIN
    ;

whereClause : WHERE expression ;

groupByClause : GROUP BY expression (',' expression)* ;

havingClause : HAVING expression ;

orderByClause : ORDER BY orderItem (',' orderItem)* ;

// PG: NULLS FIRST/LAST per order item.
orderItem : expression (ASC | DESC)? (NULLS (FIRST | LAST))? ;

subquery : '(' queryExpression ')' ;

// ---------- Expressions (precedence ladder, lowest first) ----------

expression : orExpression ;

orExpression : andExpression (OR andExpression)* ;

andExpression : notExpression (AND notExpression)* ;

notExpression : NOT notExpression | predicate ;

predicate
    : concatExpression comparisonOperator concatExpression                 # comparisonPredicate
    | concatExpression NOT? BETWEEN concatExpression AND concatExpression  # betweenPredicate
    | concatExpression NOT? LIKE concatExpression                          # likePredicate
    | concatExpression NOT? IN '(' expression (',' expression)* ')'        # inListPredicate
    | concatExpression NOT? IN subquery                                    # inSubqueryPredicate
    | concatExpression IS NOT? NULL                                        # isNullPredicate
    | EXISTS subquery                                                      # existsPredicate
    | concatExpression                                                     # simplePredicate
    ;

comparisonOperator : '=' | '<>' | '!=' | '<' | '<=' | '>' | '>=' ;

// PG: || is string concat, precedence above additive.
concatExpression : additiveExpression (PIPES additiveExpression)* ;

additiveExpression : multiplicativeExpression (('+' | '-') multiplicativeExpression)* ;

multiplicativeExpression : unaryExpression (('*' | '/' | '%') unaryExpression)* ;

unaryExpression : ('-' | '+') unaryExpression | primaryExpression ;

primaryExpression
    : literal                               # literalExpr
    | caseExpression                        # caseExpr
    | castExpression                        # castExpr
    | functionCall                          # functionExpr
    | qualifiedName                         # columnRefExpr
    | subquery                              # scalarSubqueryExpr
    | '(' expression ')'                    # parenExpr
    ;

functionCall : functionName '(' (setQuantifier? expression (',' expression)* | '*')? ')' ;

functionName : identifier | MAX ;

caseExpression
    : CASE expression? (WHEN expression THEN expression)+ (ELSE expression)? END
    ;

castExpression : CAST '(' expression AS dataType ')' ;

dataType : identifier ('(' dataTypeArg (',' dataTypeArg)? ')')? ;

qualifiedName : identifier ('.' identifier)* ;   // >3 parts refused in Phase 3 builder

// PG: booleans are literals (bare boolean columns are valid predicates —
// already covered by simplePredicate → columnRefExpr).
literal
    : INTEGER_LITERAL
    | DECIMAL_LITERAL
    | STRING_LITERAL
    | NULL
    | TRUE
    | FALSE
    ;

identifier : ID | QUOTED_IDENTIFIER ;

// =====================================================================
// 2. Dialect-specific parser rules
// =====================================================================

dataTypeArg : INTEGER_LITERAL ;

// PG: LIMIT and OFFSET in either order.
rowLimitClause
    : LIMIT expression (OFFSET expression)?
    | OFFSET expression (LIMIT expression)?
    ;

// =====================================================================
// 3. Keywords (shared block — byte-identical in all three grammars)
// =====================================================================

ALL:A L L; AND:A N D; AS:A S; ASC:A S C; BETWEEN:B E T W E E N; BY:B Y;
CASE:C A S E; CAST:C A S T; CONVERT:C O N V E R T; CROSS:C R O S S;
DESC:D E S C; DISTINCT:D I S T I N C T; ELSE:E L S E; END:E N D;
EXISTS:E X I S T S; FALSE:F A L S E; FETCH:F E T C H; FIRST:F I R S T;
FROM:F R O M; FULL:F U L L; GROUP:G R O U P; HAVING:H A V I N G; IN:I N;
INNER:I N N E R; IS:I S; JOIN:J O I N; LAST:L A S T; LEFT:L E F T;
LIKE:L I K E; LIMIT:L I M I T; MAX:M A X; NEXT:N E X T; NOT:N O T;
NULL:N U L L; NULLS:N U L L S; OFFSET:O F F S E T; ON:O N; ONLY:O N L Y;
OR:O R; ORDER:O R D E R; OUTER:O U T E R; RIGHT:R I G H T; ROW:R O W;
ROWS:R O W S; SELECT:S E L E C T; THEN:T H E N; TOP:T O P; TRUE:T R U E;
UNION:U N I O N; WHEN:W H E N; WHERE:W H E R E;

// =====================================================================
// 4. Operators, literals, identifiers (dialect-specific lexing)
// =====================================================================

PIPES : '||' ;

INTEGER_LITERAL : [0-9]+ ;

DECIMAL_LITERAL : [0-9]+ '.' [0-9]* | '.' [0-9]+ ;

// PG: '...' with '' doubling; E'...' adds backslash escapes. "quoted" identifiers.
STRING_LITERAL
    : '\'' ('\'\'' | ~['])* '\''
    | E '\'' ('\\' . | '\'\'' | ~['\\])* '\''
    ;

QUOTED_IDENTIFIER : '"' ('""' | ~'"')* '"' ;

ID : [A-Za-z_][A-Za-z0-9_$]* ;

// =====================================================================
// 5. Trivia
// =====================================================================

LINE_COMMENT  : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;

// =====================================================================
// 6. Case-insensitive letter fragments
// =====================================================================

fragment A:[Aa]; fragment B:[Bb]; fragment C:[Cc]; fragment D:[Dd]; fragment E:[Ee];
fragment F:[Ff]; fragment G:[Gg]; fragment H:[Hh]; fragment I:[Ii]; fragment J:[Jj];
fragment K:[Kk]; fragment L:[Ll]; fragment M:[Mm]; fragment N:[Nn]; fragment O:[Oo];
fragment P:[Pp]; fragment Q:[Qq]; fragment R:[Rr]; fragment S:[Ss]; fragment T:[Tt];
fragment U:[Uu]; fragment V:[Vv]; fragment W:[Ww]; fragment X:[Xx]; fragment Y:[Yy];
fragment Z:[Zz];
