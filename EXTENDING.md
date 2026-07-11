# Extending the Translator: How to Add a Statement

Extensibility is a deliverable of this project, not an accident. Adding statement
N+1 is the ordered touch-list below — a ~30-minute checklist, not archaeology.
It is written against the finished Phase 3 pattern; steps 5–7 reference Phase 4/5
components and become concrete when those phases land.

Worked example throughout: **`CREATE INDEX`** (Extension Queue #2).

## 0. Ground rules

- The AST node set is **frozen** (design decision D9). New constructs get new
  *statement* nodes only via this recipe; new *expression-level* needs become
  Phase 4 rules, not nodes, wherever possible.
- Refuse-over-silent: anything the new statement can express but v1 cannot
  translate must throw `UnsupportedFeatureException` with a `SourcePosition`,
  never produce approximate SQL.
- One dispatch style everywhere: the classic Visitor (`AstVisitor<R>` +
  `accept`). No `switch`/`instanceof` chains over AST nodes (D1).

## 1. Grammar rule ×3 (`src/main/antlr4/rs/etf/sqltranslator/grammar/`)

Add the rule with the **same canonical rule name** to `TSql.g4`, `MySql.g4`, and
`PostgreSql.g4`, and add the alternative to each `statement` rule:

```antlr
statement : ... | createIndexStatement ;

createIndexStatement : CREATE UNIQUE? INDEX identifier ON qualifiedName columnList ;
```

- Canonical section 1 should stay textually aligned across the three files;
  dialect-specific shapes go in section 2 of each grammar.
- New keywords go into the **shared keyword block** (section 3) — it must stay
  byte-identical in all three grammars; `GrammarKeywordBlockTest` fails otherwise.
- Prefer labeled alternatives (`# someLabel`) whenever a rule has shapes the
  builder must distinguish — labels generate one typed visit method each.

## 2. AST record + sealed `permits` entry (`ast/`)

```java
public record CreateIndexStatement(Identifier name, boolean unique, QualifiedName table,
                                   List<Identifier> columns, SourcePosition pos)
        implements Statement { ... }
```

- Java 17 record; `SourcePosition` is always the **last** component, populated
  from `ctx.getStart()`.
- Defensive-copy list components in a compact constructor (`List.copyOf`).
- Add the record to `Statement`'s `permits` list. From this moment the compiler
  enumerates everything else that must change — follow the errors.
- Implement `accept` delegating to the new visitor method (step 3).

## 3. `AstVisitor<R>` method (`ast/AstVisitor.java`)

Add `R visitCreateIndexStatement(CreateIndexStatement node);`. The compiler now
forces implementations in:

- **`AstDumper`** — header line + labeled children; scalar components go in the
  header (`key=value`), composite ones as indented `label:` blocks. Keep it
  **position-free**: the cross-dialect equality test depends on that.
- **`AbstractAstVisitor`** — walk/scan base with no-op defaults. Analysis that
  *observes* the tree (e.g. `CatalogBuilder`) subclasses this and overrides only
  the nodes it cares about — no identity rebuild.
- **`AstTransformer`** — the identity rebuild: reconstruct the node from
  recursively-`rebuild(...)`-ed children. Phase 4 rules inherit this traversal
  for pure AST→AST rewrites. Do **not** subclass `AstTransformer` for side-effecting
  walks.
- Any Phase 4/5 visitors that must consciously choose a behavior.

## 4. Builder mapping ×3 + shared support (`parser/`)

Override `visitCreateIndexStatement` in `TSqlAstBuilder`, `MySqlAstBuilder`,
`PostgreSqlAstBuilder`:

- Builder bodies are **mechanical extraction only**: pull children/tokens,
  convert via the shared helpers (`ident`, `qname`, `expr`, `pos`), construct the
  record. The three bodies should be textually near-identical — the generated
  context types differ, the logic must not.
- Every decision — normalization, per-dialect folds, refusals — goes into
  **`AstBuilderSupport`**, once, where it is unit-testable in isolation
  (decisions D4/D11). If you are writing an `if` in a builder that is not a
  structural null-check, it belongs in the support class.
- Dialect-specific syntax variants (e.g. T-SQL's `CLUSTERED`) either fold into
  the one canonical node or are refused; the node never records dialect syntax.

## 5. Rewrite-rule table rows (Phase 4, `transform/`)

Most statements need **data, not code**: rows in the type/function registries.
Only add a rule class when the statement needs a structural rewrite; subclass
`AstTransformer` (not `AbstractAstVisitor`) and override exactly the node types
the rule matches.

## 6. Printer method ×3 (Phase 5, `codegen/`)

Implement the new `visitCreateIndexStatement` in the shared printer base and
override in dialect printers only where rendering genuinely diverges (quoting
style, keyword spelling). Parenthesize-everything is the accepted v1 style.

## 7. Corpus cases (`src/test/resources/cases/`)

- One case directory with `input.tsql.sql` / `input.mysql.sql` /
  `input.postgresql.sql`. The corpus tests pick it up automatically:
  `ParseCasesTest` (parses), `AstBuildCasesTest` (builds),
  `AstTransformerIdentityTest` (identity rebuild).
- If the three inputs express the same logical statement, add the directory to
  `CrossDialectAstEqualityTest`'s curated list — that test is the thesis's
  "one generalized AST" evidence.
- Every new refusal gets a `cases/unsupported/<name>/` input that must parse
  but throw `UnsupportedFeatureException` on build.
- Normalization behavior (folds, escapes, refusal messages) gets unit tests in
  `NormalizationTest` / `TypeFoldingTest`; catalog-relevant DDL gets a
  `CatalogBuilderTest` case.
- From Phase 5 on: golden expected-output files per applicable direction,
  snapshot-bootstrapped and then reviewed by hand.

## The ranked Extension Queue (value-per-hour order)

1. **`INSERT ... SELECT`** — one grammar alternative + one AST field reusing `Query`
2. **`CREATE INDEX`** — small grammar surface, high practical relevance
3. **CTEs (`WITH`)** — near-identical syntax in all three dialects; mostly plumbing
4. **Derived tables in `FROM`** — unlocks the subquery scope line
5. **Window functions** — large expression-grammar surface; last

The queue is the first thing sacrificed when behind schedule: v1 scope never
grows before the day-14 milestones are green.

## Adding a transformation rule (Phase 4)

1. Create `transform/<Name>Rule.java` implementing `Rule`; put the rewrite in a
   private static transformer extending `AstTransformer` (or `ScopedTransformer`
   when you need `resolve(ColumnRef)` / `familyOf(Expression)`).
2. Override only the `visitX` methods you match; always call `super.visitX(node)`
   first so children are rebuilt, then pattern-match on the rebuilt node.
3. Emit warnings via `ctx.report().warn(CODE, message, node.pos())` — stable
   UPPER_SNAKE code, human message, position. Refuse with
   `UnsupportedFeatureException` instead of translating wrong.
4. Register the rule at its position in `RuleEngine.standard()` — order is
   load-bearing; read the sequence table in
   `docs/superpowers/plans/2026-07-11-phase-4-transformation.md`.
5. Test with `TransformTestSupport.runRule(rule, sql, source, target)`: SQL
   snippets in, node assertions out. Add corpus cases when the rule has a
   parseable surface form.
