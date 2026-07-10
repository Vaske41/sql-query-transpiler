grammar TSql;

// =====================================================================
// 1. Parser rules (canonical — identical rule names across dialects)
// =====================================================================

script : statement (';' statement)* ';'? EOF ;

statement
    : selectStatement
    | insertStatement
    | updateStatement
    | deleteStatement
    | createTableStatement
    | dropTableStatement
    | alterTableStatement
    ;

insertStatement
    : INSERT INTO qualifiedName ('(' identifier (',' identifier)* ')')?
      VALUES rowValue (',' rowValue)*
    ;

rowValue : '(' expression (',' expression)* ')' ;

updateStatement : UPDATE qualifiedName SET assignment (',' assignment)* whereClause? ;

assignment : identifier '=' expression ;

deleteStatement : DELETE FROM qualifiedName whereClause? ;

createTableStatement : CREATE TABLE qualifiedName '(' tableElement (',' tableElement)* ')' ;

tableElement : columnDefinition | tableConstraint ;

columnDefinition : identifier dataType columnConstraint* ;

columnConstraint
    : NOT NULL
    | NULL
    | DEFAULT expression
    | PRIMARY KEY
    | UNIQUE
    | REFERENCES qualifiedName ('(' identifier ')')?
    | autoIncrement
    ;

tableConstraint
    : (CONSTRAINT identifier)?
      ( PRIMARY KEY columnList
      | UNIQUE columnList
      | FOREIGN KEY columnList REFERENCES qualifiedName columnList?
      )
    ;

columnList : '(' identifier (',' identifier)* ')' ;

dropTableStatement : DROP TABLE (IF EXISTS)? qualifiedName ;

alterTableStatement
    : ALTER TABLE qualifiedName
      ( ADD COLUMN? columnDefinition
      | DROP COLUMN identifier
      )
    ;

selectStatement : queryExpression ;

// T-SQL has no trailing rowLimitClause — OFFSET/FETCH folds into orderByClause.
queryExpression
    : querySpecification (UNION ALL? querySpecification)*
      orderByClause?
    ;

querySpecification
    : SELECT topClause? setQuantifier? selectItem (',' selectItem)*
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

// OFFSET/FETCH is part of ORDER BY in T-SQL.
orderByClause
    : ORDER BY orderItem (',' orderItem)*
      (OFFSET expression (ROW | ROWS) (FETCH (FIRST | NEXT) expression (ROW | ROWS) ONLY)?)?
    ;

orderItem : expression (ASC | DESC)? ;

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

concatExpression : additiveExpression ;

additiveExpression : multiplicativeExpression (('+' | '-') multiplicativeExpression)* ;

multiplicativeExpression : unaryExpression (('*' | '/' | '%') unaryExpression)* ;

unaryExpression : ('-' | '+') unaryExpression | primaryExpression ;

primaryExpression
    : literal                               # literalExpr
    | caseExpression                        # caseExpr
    | castExpression                        # castExpr
    | convertExpression                     # convertExpr
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

literal
    : INTEGER_LITERAL
    | DECIMAL_LITERAL
    | STRING_LITERAL
    | NULL
    ;

identifier : ID | QUOTED_IDENTIFIER ;

// =====================================================================
// 2. Dialect-specific parser rules
// =====================================================================

// T-SQL 2-arg CONVERT (3-arg style codes stay refused — refuse-list).
convertExpression : CONVERT '(' dataType ',' expression ')' ;

topClause : TOP ( INTEGER_LITERAL | '(' expression ')' ) ;

autoIncrement : IDENTITY '(' INTEGER_LITERAL ',' INTEGER_LITERAL ')' ;

// T-SQL allows NVARCHAR(MAX).
dataTypeArg : INTEGER_LITERAL | MAX ;

// =====================================================================
// 3. Keywords (shared block — byte-identical in all three grammars)
// =====================================================================

ADD:A D D; ALL:A L L; ALTER:A L T E R; ALWAYS:A L W A Y S; AND:A N D;
AS:A S; ASC:A S C; AUTO_INCREMENT:A U T O '_' I N C R E M E N T;
BETWEEN:B E T W E E N; BY:B Y; CASE:C A S E; CAST:C A S T;
COLUMN:C O L U M N; CONSTRAINT:C O N S T R A I N T; CONVERT:C O N V E R T;
CREATE:C R E A T E; CROSS:C R O S S; DEFAULT:D E F A U L T;
DELETE:D E L E T E; DESC:D E S C; DISTINCT:D I S T I N C T; DROP:D R O P;
ELSE:E L S E; END:E N D; EXISTS:E X I S T S; FALSE:F A L S E;
FETCH:F E T C H; FIRST:F I R S T; FOREIGN:F O R E I G N; FROM:F R O M;
FULL:F U L L; GENERATED:G E N E R A T E D; GROUP:G R O U P;
HAVING:H A V I N G; IDENTITY:I D E N T I T Y; IF:I F; IN:I N;
INNER:I N N E R; INSERT:I N S E R T; INTO:I N T O; IS:I S; JOIN:J O I N;
KEY:K E Y; LAST:L A S T; LEFT:L E F T; LIKE:L I K E; LIMIT:L I M I T;
MAX:M A X; NEXT:N E X T; NOT:N O T; NULL:N U L L; NULLS:N U L L S;
OFFSET:O F F S E T; ON:O N; ONLY:O N L Y; OR:O R; ORDER:O R D E R;
OUTER:O U T E R; PRIMARY:P R I M A R Y; REFERENCES:R E F E R E N C E S;
RIGHT:R I G H T; ROW:R O W; ROWS:R O W S; SELECT:S E L E C T; SET:S E T;
TABLE:T A B L E; THEN:T H E N; TOP:T O P; TRUE:T R U E; UNION:U N I O N;
UNIQUE:U N I Q U E; UPDATE:U P D A T E; VALUES:V A L U E S;
WHEN:W H E N; WHERE:W H E R E;

// =====================================================================
// 4. Operators, literals, identifiers (dialect-specific lexing)
// =====================================================================

PIPES : '||' ;

INTEGER_LITERAL : [0-9]+ ;

DECIMAL_LITERAL : [0-9]+ '.' [0-9]* | '.' [0-9]+ ;

// T-SQL: 'x', N'x'; '' doubles a quote. [bracketed] or "quoted" identifiers.
STRING_LITERAL : N? '\'' ('\'\'' | ~['])* '\'' ;

QUOTED_IDENTIFIER
    : '[' (']]' | ~']')* ']'
    | '"' ('""' | ~'"')* '"'
    ;

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
