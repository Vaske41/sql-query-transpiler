# Phase 3 — AST Construction: Design

**Date:** 2026-07-11
**Status:** Approved — amended 2026-07-11 after external design review (D10–D11, §3 fix 3, §4, §5)
**Depends on:** Phase 2 (`phase-2-grammar-parsing` branch — three dialect grammars, `ParserFacade`, corpus harness)
**Roadmap reference:** ROADMAP.md, Phase 3 (Days 4–6)

## Context

Phases 1–2 delivered three trimmed ANTLR4 grammars (T-SQL, MySQL, PostgreSQL) with
identical canonical rule names and labeled alternatives, a `ParserFacade` producing
parse trees, collected-error handling, and a ~44-case parse corpus. Phase 3 turns
parse trees into the **single dialect-agnostic AST** — the analog of Catalyst's
logical plan — plus the **Catalog** (lightweight schema from DDL) that makes
Phase 4's type-dependent rewrites decidable.

Review findings this design resolves:

1. The roadmap's AST sketch is missing nodes the grammars already parse
   (UNION, predicate forms, `COUNT(DISTINCT …)`, `NVARCHAR(MAX)` length, PG
   `NULLS FIRST/LAST`).
2. ANTLR generates three *unrelated* visitor base classes, so a literal shared
   `AbstractAstBuilder` superclass is impossible — sharing must be by delegation.
3. Two grammar gaps: keyword-named functions (`LEFT`/`RIGHT`) don't parse, and
   `dataType` is single-identifier only (`DOUBLE PRECISION` fails).
4. PG `SERIAL` parses as an ordinary type name; folding it is the builder's job.
5. Design review (2026-07-11): exponent/hex literals mis-lex and fall into the
   select-item alias rule (`SELECT 1e5` silently becomes `SELECT 1 AS e5`);
   T-SQL accepts `TOP` alongside `OFFSET/FETCH`, which SQL Server rejects;
   Phase 4's rule engine needs a default-traversal transformer shipped with the
   frozen node set; the catalog needs declaration-ordered columns for
   positional `INSERT` resolution.

## Decisions (with rationale)

| # | Decision | Rationale |
|---|---|---|
| D1 | **Classic Visitor** — `AstVisitor<R>` + `accept()` on every node; no switch-based dispatch anywhere | The thesis text cites the GoF pattern; one style used everywhere per roadmap |
| D2 | **Fix the grammar gaps now** — `LEFT`/`RIGHT` as function names; two-word `dataType`; exponent/hex literal lexing | Everything in Phase 4's planned function/type tables parses (`MAX`/`LEFT`/`RIGHT` are its only keyword collisions; other keyword-named functions would need the same one-line tweak later — grammars are not frozen); last cheap moment to touch grammars |
| D3 | **Type folding in the builders** — per-dialect name → `GenericType` lookup; unknown types refused at build time | AST is born fully dialect-agnostic (strongest form of the thesis claim); no raw type strings survive |
| D4 | **Thin visitors + shared core** — three generated-visitor subclasses doing mechanical extraction only; all logic in one `AstBuilderSupport` | Type-safe, standard ANTLR; every normalization/bug-fix lands once |
| D5 | Numeric literals keep lexical text | `9.99` must reach output byte-identically (determinism guarantee) |
| D6 | Identifiers stored unquoted/unescaped + `quoted` flag; case preserved as written | All three dialects treat unquoted identifiers case-insensitively enough for v1; case-sensitivity is refuse-listed |
| D7 | One reusable `Query` node shared by statements and subqueries | Grammar subqueries contain full `queryExpression` (ORDER BY/LIMIT included) |
| D8 | `UnsupportedFeatureException` moves to Phase 3 (`core/`) | Builders refuse constructs; the exception cannot wait for Phase 4 |
| D9 | Node set **freezes** at end of Phase 3 | Roadmap risk-register mitigation; later needs become rules, not nodes |
| D10 | **`AstTransformer` ships in Phase 3** — identity-rebuild `AstVisitor<Object>` base, part of the frozen API | Phase 4 rules override only the nodes they match; without it every rule reimplements ~40-method traversal or drifts into switch dispatch, violating D1 |
| D11 | **Normalization lives in the builders** — deliberately supersedes the roadmap's "keep builders dumb, normalize in Phase 4" split | The roadmap's motive (isolated testability) survives via `AstBuilderSupport`; D3 requires the AST born dialect-agnostic; Phase 4 keeps every semantic rewrite |

## 1. AST node hierarchy (~40 records, `ast/` package)

All nodes are Java 17 records implementing sealed interfaces. Every record carries
`SourcePosition(int line, int column)` as its **last** component — 1-based line,
0-based column, same convention as `SyntaxError`; populated from `ctx.getStart()`.
`SourcePosition` lives in `core/` beside `SyntaxError`, not in `ast/`, so that
`UnsupportedFeatureException` (§4) keeps `core/` free of any `ast/` dependency.
Exception: the pure type descriptors `DataType`, `TypeLength`, `FixedLength`,
`MaxLength` carry no position — they are also reused inside the `Catalog`, where
positions are meaningless; errors about types are reported at the owning
`ColumnDefinition`/`CastExpression` position.

### 1.1 Statements and query shape

```java
record Script(List<Statement> statements, SourcePosition pos)

sealed interface Statement permits SelectStatement, InsertStatement, UpdateStatement,
    DeleteStatement, CreateTableStatement, DropTableStatement, AlterTableStatement

// One reusable query node (D7). Flat union list mirrors the grammar:
// querySpecification (UNION ALL? querySpecification)* orderByClause? rowLimitClause?
record Query(QuerySpecification first, List<UnionArm> unionArms,
             List<OrderItem> orderBy, Optional<RowLimit> limit, SourcePosition pos)
record UnionArm(boolean all, QuerySpecification spec, SourcePosition pos)
record QuerySpecification(Optional<SetQuantifier> quantifier, List<SelectItem> items,
                          Optional<TableSource> from, Optional<Expression> where,
                          List<Expression> groupBy, Optional<Expression> having,
                          SourcePosition pos)
record SelectStatement(Query query, SourcePosition pos)   // Statement wrapper

record RowLimit(Optional<Expression> count, Optional<Expression> offset, SourcePosition pos)
record OrderItem(Expression expr, SortDirection direction, Optional<NullsOrder> nulls,
                 SourcePosition pos)
enum SortDirection { ASC, DESC }        // ASC when unspecified
enum NullsOrder   { FIRST, LAST }       // PG only; carried, translated in Phase 4
enum SetQuantifier { DISTINCT, ALL }
```

```java
sealed interface SelectItem permits SelectStar, SelectExpr
record SelectStar(Optional<QualifiedName> qualifier, SourcePosition pos)  // * and t.*
record SelectExpr(Expression expr, Optional<Identifier> alias, SourcePosition pos)

record TableSource(TableRef first, List<Join> joins, SourcePosition pos)
record TableRef(QualifiedName table, Optional<Identifier> alias, SourcePosition pos)
record Join(JoinKind kind, TableRef table, Optional<Expression> on, SourcePosition pos)
enum JoinKind { INNER, LEFT, RIGHT, FULL, CROSS }   // CROSS ⇒ on is empty
```

### 1.2 DML

```java
record InsertStatement(QualifiedName table, List<Identifier> columns,  // empty = no list
                       List<List<Expression>> rows, SourcePosition pos)
record UpdateStatement(QualifiedName table, List<Assignment> assignments,
                       Optional<Expression> where, SourcePosition pos)
record Assignment(Identifier column, Expression value, SourcePosition pos)
record DeleteStatement(QualifiedName table, Optional<Expression> where, SourcePosition pos)
```

### 1.3 DDL

```java
record CreateTableStatement(QualifiedName table, List<ColumnDefinition> columns,
                            List<TableConstraint> constraints, SourcePosition pos)
record ColumnDefinition(Identifier name, DataType type, boolean autoIncrement,
                        Optional<Boolean> nullable,          // empty = unspecified
                        Optional<Expression> defaultValue,
                        boolean primaryKey, boolean unique,
                        Optional<ForeignKeyRef> references, SourcePosition pos)
record ForeignKeyRef(QualifiedName table, Optional<Identifier> column, SourcePosition pos)

sealed interface TableConstraint permits PrimaryKeyConstraint, UniqueConstraint,
                                         ForeignKeyConstraint
record PrimaryKeyConstraint(Optional<Identifier> name, List<Identifier> columns,
                            SourcePosition pos)
record UniqueConstraint(Optional<Identifier> name, List<Identifier> columns,
                        SourcePosition pos)
record ForeignKeyConstraint(Optional<Identifier> name, List<Identifier> columns,
                            QualifiedName refTable, List<Identifier> refColumns,
                            SourcePosition pos)

record DropTableStatement(QualifiedName table, boolean ifExists, SourcePosition pos)
record AlterTableStatement(QualifiedName table, AlterAction action, SourcePosition pos)
sealed interface AlterAction permits AddColumn, DropColumn
record AddColumn(ColumnDefinition column, SourcePosition pos)
record DropColumn(Identifier column, SourcePosition pos)
```

### 1.4 Expressions

```java
sealed interface Expression permits Literal, ColumnRef, BinaryOp, UnaryOp,
    BetweenPredicate, LikePredicate, InListPredicate, InSubqueryPredicate,
    IsNullPredicate, ExistsPredicate, FunctionCall, CaseExpression,
    CastExpression, SubqueryExpression

record BinaryOp(BinaryOperator op, Expression left, Expression right, SourcePosition pos)
enum BinaryOperator { OR, AND, EQ, NEQ, LT, LTE, GT, GTE, ADD, SUB, MUL, DIV, MOD, CONCAT }
record UnaryOp(UnaryOperator op, Expression operand, SourcePosition pos)
enum UnaryOperator { NEG, POS, NOT }

// Dedicated predicate nodes (BinaryOp cannot carry these forms):
record BetweenPredicate(Expression value, Expression low, Expression high,
                        boolean negated, SourcePosition pos)
record LikePredicate(Expression value, Expression pattern, boolean negated, SourcePosition pos)
record InListPredicate(Expression value, List<Expression> items, boolean negated,
                       SourcePosition pos)
record InSubqueryPredicate(Expression value, Query subquery, boolean negated,
                           SourcePosition pos)
record IsNullPredicate(Expression value, boolean negated, SourcePosition pos)
record ExistsPredicate(Query subquery, SourcePosition pos)

record FunctionCall(String name,                       // uppercased (case-insensitive
                    List<Expression> args,             //   in all three dialects)
                    boolean star,                      // COUNT(*)
                    Optional<SetQuantifier> quantifier,// COUNT(DISTINCT x)
                    SourcePosition pos)
record CaseExpression(Optional<Expression> operand, List<WhenClause> whens,
                      Optional<Expression> elseValue, SourcePosition pos)
record WhenClause(Expression condition, Expression result, SourcePosition pos)
record CastExpression(Expression operand, DataType targetType, SourcePosition pos)
record SubqueryExpression(Query query, SourcePosition pos)
```

### 1.5 Literals, identifiers, types

```java
sealed interface Literal extends Expression permits NumericLiteral, StringLiteral,
                                                    BooleanLiteral, NullLiteral
record NumericLiteral(String text, boolean decimal, SourcePosition pos)
    // D5: lexical text, exponent forms included (1e5, 2.5E-3); decimal mirrors
    // the INTEGER_LITERAL vs DECIMAL_LITERAL token distinction
record StringLiteral(String value /*unescaped*/, boolean national /*N'…'*/,
                     SourcePosition pos)
record BooleanLiteral(boolean value, SourcePosition pos)   // MySQL/PG only at parse time
record NullLiteral(SourcePosition pos)

record Identifier(String value /*unquoted+unescaped*/, boolean quoted, SourcePosition pos)
record QualifiedName(List<Identifier> parts, SourcePosition pos)  // >3 parts refused
record ColumnRef(QualifiedName name, SourcePosition pos)

record DataType(GenericType type, Optional<TypeLength> length, Optional<Integer> scale)
sealed interface TypeLength permits FixedLength, MaxLength
record FixedLength(int value) implements TypeLength
record MaxLength() implements TypeLength                    // NVARCHAR(MAX)
enum GenericType { TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, FLOAT, DOUBLE,
                   CHAR, VARCHAR, NVARCHAR, TEXT, BOOLEAN, DATE, TIME, TIMESTAMP, BLOB }
```

Auto-increment is **always** the `ColumnDefinition.autoIncrement` flag, never a type
or constraint node — `IDENTITY(1,1)`, `AUTO_INCREMENT`, `GENERATED … AS IDENTITY`,
and `SERIAL` all fold into it.

### 1.6 Visitor

`AstVisitor<R>` (D1): one `visitX` method per record type in `ast/` (~40 methods
including `visitScript`; only `SourcePosition` and the enums are not visitable);
every node implements `<R> R accept(AstVisitor<R> visitor)`. No switch-based
dispatch anywhere in the codebase — one style, used everywhere (analysis,
transform, codegen).

`AstDumper implements AstVisitor<String>` ships with the hierarchy: a canonical,
**position-free**, indentation-structured debug dump. It serves the roadmap's
round-trip smoke-test deliverable and the cross-dialect equality test (§6), and
its output doubles as thesis exhibits.

`AstTransformer` also ships with the hierarchy (D10): an `AstVisitor<Object>`
whose every `visitX` is the identity transform — rebuild the node from its
recursively-accepted children and return it. Phase 4 rules subclass it and
override only the node types they match; nobody reimplements 40-method
traversal or drifts into switch dispatch. The single type parameter forces
internal casts when reassembling children — an accepted cost of D1, confined
to this one base class and covered by an identity test over the whole
corpus (§6).

## 2. Builders (`parser/` package)

### 2.1 Architecture (D4)

```
AstBuilderFacade.buildScript(sql, dialect) → Script
    └─ ParserFacade.parseScript(sql, dialect)        (existing, unchanged)
    └─ {TSql|MySql|PostgreSql}AstBuilder.visit(tree)  (thin, mechanical)
           └─ AstBuilderSupport                        (ALL logic, shared)
```

- Each `XAstBuilder extends XBaseVisitor<Object>` — method bodies only extract
  children/tokens/positions and delegate. No conditionals beyond structural
  null-checks. Bodies are textually near-identical across the three builders;
  that triplication is accepted because it is mechanical and the compiler
  verifies it against each grammar.
- `AstBuilderSupport` owns: node construction helpers, `RowLimit` unification,
  per-dialect type folding tables, identifier/string unescaping, function-name
  uppercasing, all refusals. One class, unit-testable in isolation.
- `ParserFacade` keeps returning parse trees; Phase 2 tests untouched.

Placing normalization in the builders deliberately supersedes the roadmap's
"keep builders dumb; normalize in Phase 4 rules" guideline (D11). That
guideline's motive — testability in isolation — is preserved because
`AstBuilderSupport` is a plain unit-testable class, and D3 requires the AST to
be born dialect-agnostic. What moves into the builders is pure **syntax
unification** (`TOP`/`LIMIT`, `CONVERT`→`CAST`, `SERIAL`, escapes); the Phase 4
rule catalog keeps every **semantic** rewrite (function mapping, type-out
rendering, concat resolution, boolean semantics) — the thesis's core chapter
loses no exhibits.

### 2.2 Normalization table (per-dialect folds — thesis table)

| Dialect | Source construct | Canonical AST result |
|---|---|---|
| T-SQL | `TOP n` / `TOP (expr)` | `Query.limit.count`; **refused** when the query has UNION arms |
| T-SQL | `ORDER BY … OFFSET m ROWS [FETCH FIRST/NEXT n ROWS ONLY]` | `RowLimit(count=n, offset=m)` |
| T-SQL | `TOP` together with `OFFSET/FETCH` | **refused** — SQL Server itself rejects the combination; merging would invent semantics |
| T-SQL | `CONVERT(type, expr)` (2-arg) | `CastExpression` |
| T-SQL | `N'…'` | `StringLiteral(national=true)` |
| T-SQL | `IDENTITY(1,1)` | `autoIncrement=true`; other seed/increment **refused** |
| T-SQL | `[x]` (`]]` escape), `"x"` (`""` escape) | `Identifier(value, quoted=true)` |
| MySQL | `LIMIT m, n` | `RowLimit(count=n, offset=m)` — operand swap |
| MySQL | `LIMIT n [OFFSET m]` | `RowLimit(count=n, offset=m)` |
| MySQL | `a \|\| b` | `BinaryOp(OR)` (default `sql_mode`: logical OR) |
| MySQL | `'…'`/`"…"` strings (backslash + doubling escapes), `` `x` `` (` `` ` escape) | unescaped `StringLiteral` / `Identifier(quoted=true)` |
| MySQL | `AUTO_INCREMENT` | `autoIncrement=true` |
| MySQL | `TINYINT(1)` | `GenericType.BOOLEAN` — ubiquitous boolean idiom; bare `TINYINT` stays `TINYINT`; other lengths refused |
| PG | `SERIAL` / `BIGSERIAL` / `SMALLSERIAL` | `INTEGER`/`BIGINT`/`SMALLINT` + `autoIncrement=true` |
| PG | `GENERATED ALWAYS\|BY DEFAULT AS IDENTITY` | `autoIncrement=true` |
| PG | `a \|\| b` | `BinaryOp(CONCAT)` |
| PG | `E'…'` (backslash escapes), `'…'` (doubling) | unescaped `StringLiteral` |
| PG | `NULLS FIRST/LAST` | `OrderItem.nulls` |
| all | dialect type name | `GenericType` via per-dialect lookup; unknown **refused** |
| all | `qualifiedName` with >3 parts | **refused** |
| all | function names | uppercased in `FunctionCall.name` |
| all | exponent numerics (`1e5`, `2.5E-3`) | `NumericLiteral`, lexical text preserved (D5) |
| all | `0x…` hex literals | **refused** — no dialect-portable v1 meaning |
| all | quoted identifier as function name | **refused** — uppercasing would corrupt a case-sensitive name |
| all | quoted-identifier case | preserved as written (D6) |

### 2.3 Type-folding tables (D3, excerpt — full tables in code)

- T-SQL: `INT→INTEGER`, `BIGINT`, `SMALLINT`, `TINYINT`, `BIT→BOOLEAN`,
  `DECIMAL/NUMERIC→DECIMAL`, `FLOAT→DOUBLE`, `REAL→FLOAT`, `NVARCHAR`, `VARCHAR`,
  `NCHAR→NVARCHAR`\*, `CHAR`, `DATETIME2/DATETIME→TIMESTAMP`, `DATE`, `TIME`,
  `VARBINARY(MAX)/IMAGE→BLOB`, `DOUBLE PRECISION→DOUBLE`
- MySQL: `INT/INTEGER→INTEGER`, `TINYINT`, `SMALLINT`, `BIGINT`, `DECIMAL`,
  `FLOAT`, `DOUBLE/DOUBLE PRECISION→DOUBLE`, `VARCHAR`, `CHAR`, `TEXT`,
  `BOOLEAN/BOOL→BOOLEAN`, `DATETIME/TIMESTAMP→TIMESTAMP`, `DATE`, `TIME`, `BLOB`
- PG: `INTEGER/INT/INT4→INTEGER`, `SMALLINT/INT2`, `BIGINT/INT8`,
  `DECIMAL/NUMERIC→DECIMAL`, `REAL→FLOAT`, `DOUBLE PRECISION/FLOAT8→DOUBLE`,
  `VARCHAR`, `CHAR`, `TEXT`, `BOOLEAN/BOOL`, `TIMESTAMP`, `DATE`, `TIME`,
  `BYTEA→BLOB`, `SERIAL` family (see §2.2)

\* `NCHAR→NVARCHAR` is a documented v1 simplification (fixed-width national char
folds to variable-width generic).

Length/scale arguments are carried only into the parameterizable generics
(`CHAR`, `VARCHAR`, `NVARCHAR`, `DECIMAL`, `TIME`, `TIMESTAMP`); an argument on
any other fold (`FLOAT(24)`, `DOUBLE PRECISION(10)`) is **refused**, not
silently dropped — Phase 5 could otherwise emit invalid SQL like `DOUBLE(24)`.
Two documented exceptions: `VARBINARY(MAX)` (T-SQL) — `MAX` is part of the
lookup key itself and is consumed by the fold to `BLOB`; MySQL `TINYINT(1)` —
folds to `BOOLEAN` (boolean idiom for Phase 4), while bare `TINYINT` and
`TINYINT(n)` for `n ≠ 1` follow the normal rules (keep / refuse).

Anything not in the table → `UnsupportedFeatureException("type <name>", pos)`.

## 3. Grammar fixes (Phase 2 debt, D2)

Applied to **all three** grammars at the start of Phase 3, keeping the canonical
sections aligned:

1. `functionName : identifier | MAX | LEFT | RIGHT ;` — unambiguous: a join's
   `LEFT`/`RIGHT` is never followed by `(`.
2. `dataType : identifier identifier? ('(' dataTypeArg (',' dataTypeArg)? ')')? ;`
   — the **builder** whitelists `DOUBLE PRECISION` as the only legal two-word
   form; any other two-word sequence is refused with the offending text.
3. Numeric-literal lexing. `DECIMAL_LITERAL` gains an exponent alternative —
   `([0-9]+ ('.' [0-9]*)? | '.' [0-9]+) [eE] [+-]? [0-9]+` — because today
   `SELECT 1e5` lexes as `1` + identifier `e5`, and the select-item alias rule
   silently yields `SELECT 1 AS e5`: a *wrong query*, not an error, violating
   the refuse-over-silent guarantee. A `HEX_LITERAL : '0' X [0-9A-Fa-f]+ ;`
   token (same silent-alias trap via `0xFF`, valid in T-SQL and MySQL) joins
   the canonical `literal` rule in all three grammars and is **refused** by
   the builders — uniform refusal beats a confusing parse error, and hex has
   no dialect-portable v1 meaning.

The byte-identical keyword-block test continues to guard drift. All three fixes
get corpus cases (`functions/left-right/`, `create-table-types/double-precision/`,
`literals/exponent/`, plus the `cases/unsupported/` entries in §4).

## 4. Refusals and errors

New in `core/`:

```java
class UnsupportedFeatureException extends RuntimeException {
    private final String construct;          // e.g. "type GEOGRAPHY", "TOP inside UNION"
    private final SourcePosition position;
}
```

Builder-level refusals (all with positions): >3-part qualified names, unknown type
names, illegal two-word types, length args on non-parameterizable type folds,
`TOP` in a query with UNION arms, `TOP` combined with `OFFSET/FETCH`,
`IDENTITY(s,i)` with `(s,i) ≠ (1,1)`, hex literals, quoted function names.

New corpus category `cases/unsupported/` seeds the roadmap's Phase 7 category:
`four-part-name`, `unknown-type`, `top-in-union`, `top-with-offset-fetch`,
`identity-seed`, `hex-literal`, `quoted-function-name`. These files must
**fail to build** with `UnsupportedFeatureException` (they parse fine).

## 5. Catalog (`analysis/` package)

```java
record Catalog(Map<String, TableSchema> tables)  // key: LAST identifier of the table's
                                                 // qualified name, lowercased (see below)
record TableSchema(QualifiedName name, List<ColumnInfo> columns)  // declaration order
record ColumnInfo(Identifier name, DataType type, boolean autoIncrement)
```

Columns are a **`List` in declaration order** — order is load-bearing: Phase 4's
cast-insertion rule must resolve `INSERT INTO t VALUES (…)` positionally, and
the column list is optional in all three grammars. `TableSchema` additionally
exposes a `column(String)` helper doing lowercased-name lookup over the list.

Keying rule: the map key is the **last identifier** of the table's qualified
name, lowercased — `dbo.users` and `users` deliberately collide. This and the
lowercased column lookup are documented simplifications consistent with the
case-insensitivity limitation (D6).

`CatalogBuilder.build(Script) → Catalog` walks statements **in order**:
`CREATE TABLE` registers (duplicate name: last one wins); `ALTER TABLE
ADD/DROP COLUMN` rewrites the entry; `DROP TABLE` removes it; `ALTER`/`DROP`
of an unregistered table is silently ignored. Statements referencing unknown
tables are fine — the catalog is best-effort context for Phase 4, which
degrades to warnings on misses.

## 6. Testing

1. **Corpus-wide build test** (`AstBuildCasesTest`, reuses `CaseFiles` +
   `@TestFactory`): every `cases/**/input.*.sql` builds a `Script` without
   throwing — except `cases/unsupported/**`, which must throw
   `UnsupportedFeatureException`. ~135+ dynamic tests.
2. **Cross-dialect AST equality** (`CrossDialectAstEqualityTest`): for a curated
   list of case directories whose three dialect inputs express the same logical
   statement, assert the three built `Script`s produce **identical `AstDumper`
   output** (the dump is position-free by design, so line/column differences
   between dialect files cannot cause false negatives). Exclusions are listed
   with reasons (e.g. `select-concat`: `+`/`||`/`CONCAT()` unify only in
   Phase 4). This test is the thesis's direct evidence for the "one generalized
   AST" claim, and failing diffs are readable.
3. **Normalization units**: one test per row of the §2.2 table, including every
   unescape rule against the Phase 2 hostile inputs, the `LIMIT m,n` swap,
   `CONVERT` fold, `SERIAL` fold, `TOP` forms, `OFFSET/FETCH`, auto-increment
   variants, type folds (incl. `DOUBLE PRECISION`), numeric-literal text
   preservation (incl. exponent forms), function-name uppercasing.
4. **Catalog tests**: `ddl-then-dml-script` fixtures → expected catalog;
   ALTER/DROP applied in order; case-insensitive lookup; **declaration order**
   preserved (positional resolution for `INSERT` without a column list);
   duplicate `CREATE TABLE` and `ALTER` of an unknown table.
5. **Transformer identity** (`AstTransformerIdentityTest`): the stock
   `AstTransformer` applied to every corpus-built `Script` yields dump-identical
   output — guards the rebuild plumbing every Phase 4 rule will inherit.

## 7. Packaging and integration seam

| Package | Contents |
|---|---|
| `ast/` | ~40 node records, `AstVisitor<R>`, `AstDumper`, `AstTransformer`, enums |
| `parser/` | `AstBuilderFacade`, 3 thin builders, `AstBuilderSupport` (+ existing `ParserFacade`, `CollectingErrorListener`) |
| `analysis/` | `Catalog`, `TableSchema`, `ColumnInfo`, `CatalogBuilder` |
| `core/` | + `SourcePosition`, `UnsupportedFeatureException` (join `Dialect`, `ParseException`, `SyntaxError`) |

No new dependencies. Phase 4 consumes `AstBuilderFacade.buildScript(...)` and
`CatalogBuilder.build(...)` via the future `Translator` facade.

## 8. Freeze rule and out-of-scope

- The node set **freezes** at the end of Phase 3 (D9). Phase 4+ needs become
  rules, not nodes.
- Explicitly not modeled (refuse-list unchanged): CTEs, window functions,
  `INSERT … SELECT`, `MERGE`, 3-arg `CONVERT`, `TOP PERCENT/WITH TIES`,
  collation/case-sensitivity semantics, `NCHAR` as a distinct generic type,
  hex literals, quoted (case-sensitive) function names.

## 9. Deliverables (maps to ROADMAP Phase 3 checklist)

1. Grammar fixes (×3) + new corpus cases (incl. `cases/unsupported/` seed)
2. AST node hierarchy + `AstVisitor<R>` + `AstDumper` + `AstTransformer` +
   `SourcePosition` (in `core/`)
3. `AstBuilderSupport` + three thin dialect builders + `AstBuilderFacade`
4. `UnsupportedFeatureException` in `core/`
5. `Catalog` (declaration-ordered columns) + `CatalogBuilder`
6. `EXTENDING.md` — the "how to add a statement" recipe (a ROADMAP Phase 3
   deliverable), written against the finished grammar/node/visitor/builder
   pattern while it is fresh
7. Test layers 1–5 above, all green under `mvn clean verify` / CI
