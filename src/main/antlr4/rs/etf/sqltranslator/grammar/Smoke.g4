// Throwaway toolchain-proof grammar. Phase 2 replaces this with the real
// per-dialect grammars (TSql.g4, MySql.g4, PostgreSql.g4).
grammar Smoke;

statement : SELECT INTEGER EOF ;

SELECT  : [Ss][Ee][Ll][Ee][Cc][Tt] ;
INTEGER : [0-9]+ ;
WS      : [ \t\r\n]+ -> skip ;
