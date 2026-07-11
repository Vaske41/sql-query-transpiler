# Phase 4 — Analysis & Transformation (Catalyst-style Rule Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `RuleEngine` that rewrites a dialect-agnostic `Script` into a target-legal `Script` through a fixed, ordered sequence of small AST→AST rules, accumulating warnings in a `TranslationReport` — the thesis's core chapter.

**Architecture:** Mirrors Catalyst: rules are `Script → Script` functions applied once in fixed order (no fixed-point iteration — single-pass guarantees determinism). Each rule subclasses the frozen Phase 3 `AstTransformer` (identity-rebuild visitor) and overrides only the nodes it matches. Catalog-aware rules extend a shared `ScopedTransformer` that tracks FROM-clause alias scopes and resolves `ColumnRef → ColumnInfo` against the `Catalog`. Mapping tables are data (`Map` constants), not code.

**Tech Stack:** Java 17, Maven, JUnit 5 + AssertJ. Zero new dependencies.

## Global Constraints

- Java 17; build must stay green under `./mvnw --batch-mode clean verify` (CI runs exactly this).
- **No new dependencies** in `pom.xml`.
- **Node set is frozen (D9):** no new record types in `ast/`. New needs become rules.
- **No switch-based dispatch over AST node types (D1):** rules subclass `AstTransformer`; visitors everywhere. (Switches over *enums* and *strings* are fine.)
- **Grammars are untouched** in Phase 4.
- **Determinism:** stable iteration orders only (`List`, `LinkedHashMap`); no `HashMap` iteration anywhere that affects output or warning order.
- Every `Warning` carries a stable `code`, a human message, and a `SourcePosition`.
- Refusals are `UnsupportedFeatureException(construct, position)` — never a silent wrong translation.
- Commit messages: conventional-commit style, **no attribution trailers** of any kind.
- All new production code lives in `rs.etf.sqltranslator.transform`; tests mirror the package under `src/test/java`.

## Phase 3 API this plan consumes (already on `main`)

- `Script AstBuilderFacade.buildScript(String sql, Dialect dialect)` — throws `ParseException` / `UnsupportedFeatureException`.
- `Catalog CatalogBuilder.build(Script script)`; `Optional<TableSchema> Catalog.table(String name)` (lowercased last-identifier key); `List<ColumnInfo> TableSchema.columns()` (declaration order); `Optional<ColumnInfo> TableSchema.column(String name)`; `ColumnInfo(Identifier name, DataType type, boolean autoIncrement)`.
- `AstTransformer` — `Script transform(Script)`, protected `rebuild / rebuildList / rebuildOptional`; every `visitX` is identity-rebuild.
- `String new AstDumper().dump(AstNode)` — canonical position-free dump; equality of dumps ⇒ same logical AST.
- `SourcePosition(int line, int column)`; `UnsupportedFeatureException(String construct, SourcePosition pos)`; `Dialect { TSQL, MYSQL, POSTGRESQL }`.
- Node records exactly as rebuilt in `AstTransformer` (see `src/main/java/rs/etf/sqltranslator/ast/AstTransformer.java` for every constructor shape).
- `GenericType { TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, FLOAT, DOUBLE, CHAR, VARCHAR, NVARCHAR, TEXT, BOOLEAN, DATE, TIME, TIMESTAMP, BLOB }`; `BinaryOperator { OR, AND, EQ, NEQ, LT, LTE, GT, GTE, ADD, SUB, MUL, DIV, MOD, CONCAT }`.

## Rule sequence (fixed order — the engine's spine)

| # | Batch | Rule | Purpose |
|---|---|---|---|
| 1 | Validate | `ValidateTargetCapabilitiesRule` | Refuse target-impossible constructs (FULL JOIN→MySQL; OFFSET w/o ORDER BY→T-SQL; OFFSET w/o LIMIT→MySQL) |
| 2 | Normalize | `NormalizeSourceFunctionsRule` | Fold source-specific function spellings into canonical names (`ISNULL/IFNULL→COALESCE`, `GETDATE→NOW`, `LEN/LENGTH→CHAR_LENGTH`, `CONCAT()→CONCAT op chain`, MySQL 1-arg `ISNULL(x)→x IS NULL`, PG `DATE_PART('year',x)→YEAR(x)`) |
| 3 | Rewrite | `ResolveConcatRule` | T-SQL source `+` → `CONCAT` op when string evidence exists (literal or catalog) |
| 4 | Rewrite | `InsertCastsRule` | PG target: wrap mismatched literals in explicit `CAST` (catalog-resolved column vs literal); T-SQL target: warning only |
| 5 | Rewrite | `RewriteBooleanSemanticsRule` | Truthiness wrapping in boolean contexts, `TRUE/FALSE→1/0` for T-SQL, PG boolean-column forms, boolean DEFAULT/INSERT harmonization |
| 6 | Rewrite | `NarrowTypesRule` | Target-representable generic types (`NVARCHAR→VARCHAR/TEXT`, `TEXT→NVARCHAR(MAX)`, `TINYINT→SMALLINT` for PG) with loss warnings |
| 7 | Rewrite | `RenderTargetFunctionsRule` | Canonical → target function names + arg adapters (`NOW→GETDATE`, `CHAR_LENGTH→LEN`, 2-arg `SUBSTRING` adapter, `YEAR→DATE_PART`, `CONCAT` op → MySQL `CONCAT()` / T-SQL operand casts); pass-through warning for unknown functions |
| 8 | Rewrite | `DropNullsOrderingRule` | Drop `NULLS FIRST/LAST` for non-PG targets with warning |

Ordering rationale: validation fails fast; normalization gives later rules one canonical spelling to match; concat resolution must precede function rendering (so a resolved `CONCAT` op can become MySQL `CONCAT()`); cast insertion and boolean rewriting need canonical forms but must run before type narrowing changes `DataType`s they copy from the catalog (the catalog is built once from the *source* script and is never narrowed — rules read source-typed columns by design).

---

### Task 1: Transform scaffolding — report, context, rule, engine

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/Warning.java`
- Create: `src/main/java/rs/etf/sqltranslator/transform/TranslationReport.java`
- Create: `src/main/java/rs/etf/sqltranslator/transform/TranslationContext.java`
- Create: `src/main/java/rs/etf/sqltranslator/transform/Rule.java`
- Create: `src/main/java/rs/etf/sqltranslator/transform/TranslationResult.java`
- Create: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/package-info.java` (replace placeholder text)
- Test: `src/test/java/rs/etf/sqltranslator/transform/RuleEngineTest.java`

**Interfaces:**
- Consumes: `AstBuilderFacade.buildScript`, `CatalogBuilder.build`, `AstDumper`.
- Produces: `Rule { String name(); Script apply(Script, TranslationContext); }`; `TranslationContext(Dialect source, Dialect target, Catalog catalog, TranslationReport report)`; `TranslationReport.warn(String code, String message, SourcePosition pos)` / `List<Warning> warnings()`; `RuleEngine(List<Rule>)`, `RuleEngine.standard()` (rule list grows in later tasks), `TranslationResult RuleEngine.run(Script, Dialect source, Dialect target)`. Every later task plugs into these exact signatures.

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    @Test
    void engineWithNoRulesIsIdentityAndReportsNothing() {
        Script script = AstBuilderFacade.buildScript(
                "SELECT id, name FROM users WHERE id > 5;", Dialect.POSTGRESQL);
        TranslationResult result = new RuleEngine(java.util.List.of())
                .run(script, Dialect.POSTGRESQL, Dialect.MYSQL);
        AstDumper dumper = new AstDumper();
        assertThat(dumper.dump(result.script())).isEqualTo(dumper.dump(script));
        assertThat(result.report().warnings()).isEmpty();
    }

    @Test
    void engineAppliesRulesInListOrder() {
        java.util.List<String> applied = new java.util.ArrayList<>();
        Rule a = named("a", applied);
        Rule b = named("b", applied);
        Script script = AstBuilderFacade.buildScript("SELECT 1;", Dialect.MYSQL);
        new RuleEngine(java.util.List.of(a, b)).run(script, Dialect.MYSQL, Dialect.TSQL);
        assertThat(applied).containsExactly("a", "b");
    }

    @Test
    void contextCarriesCatalogBuiltFromTheScript() {
        Script script = AstBuilderFacade.buildScript(
                "CREATE TABLE t (id INT); SELECT id FROM t;", Dialect.MYSQL);
        Rule probe = new Rule() {
            @Override public String name() { return "probe"; }
            @Override public Script apply(Script s, TranslationContext ctx) {
                assertThat(ctx.catalog().table("t")).isPresent();
                assertThat(ctx.source()).isEqualTo(Dialect.MYSQL);
                assertThat(ctx.target()).isEqualTo(Dialect.POSTGRESQL);
                return s;
            }
        };
        new RuleEngine(java.util.List.of(probe))
                .run(script, Dialect.MYSQL, Dialect.POSTGRESQL);
    }

    private static Rule named(String name, java.util.List<String> sink) {
        return new Rule() {
            @Override public String name() { return name; }
            @Override public Script apply(Script s, TranslationContext ctx) {
                sink.add(name);
                return s;
            }
        };
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=RuleEngineTest test`
Expected: COMPILE ERROR — `Rule`, `RuleEngine`, `TranslationContext`, `TranslationResult` do not exist.

- [ ] **Step 3: Write the implementation**

`Warning.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.core.SourcePosition;

/** One translation warning: stable code, human message, source position. */
public record Warning(String code, String message, SourcePosition position) {
}
```

`TranslationReport.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.ArrayList;
import java.util.List;

/** Accumulates warnings in emission order (deterministic: rules run in fixed order). */
public final class TranslationReport {

    private final List<Warning> warnings = new ArrayList<>();

    public void warn(String code, String message, SourcePosition position) {
        warnings.add(new Warning(code, message, position));
    }

    public List<Warning> warnings() {
        return List.copyOf(warnings);
    }
}
```

`TranslationContext.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.Catalog;
import rs.etf.sqltranslator.core.Dialect;

/**
 * Everything a rule may consult: source/target dialect, the catalog built from the
 * source script's DDL (never narrowed — rules read source-typed columns), and the
 * warning sink.
 */
public record TranslationContext(Dialect source, Dialect target, Catalog catalog,
                                 TranslationReport report) {
}
```

`Rule.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Script;

/**
 * One Catalyst-style rewrite: a pure Script → Script function (Strategy pattern).
 * Rules run once, in the fixed order wired in {@link RuleEngine#standard()}; a rule
 * may throw {@link rs.etf.sqltranslator.core.UnsupportedFeatureException} to refuse.
 */
public interface Rule {

    String name();

    Script apply(Script script, TranslationContext ctx);
}
```

`TranslationResult.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Script;

/** The rewritten script plus every warning the rules emitted. */
public record TranslationResult(Script script, TranslationReport report) {
}
```

`RuleEngine.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.CatalogBuilder;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.List;

/**
 * Applies rules once, in fixed list order — no fixed-point iteration (single-pass
 * keeps translation deterministic; ROADMAP Phase 4). Batches are positions in the
 * list: [Validate] → [Normalize] → [TargetRewrite].
 */
public final class RuleEngine {

    private final List<Rule> rules;

    public RuleEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** The standard sequence. Grows task by task; order is load-bearing. */
    public static RuleEngine standard() {
        return new RuleEngine(List.of());
    }

    public TranslationResult run(Script script, Dialect source, Dialect target) {
        TranslationReport report = new TranslationReport();
        TranslationContext ctx =
                new TranslationContext(source, target, CatalogBuilder.build(script), report);
        Script current = script;
        for (Rule rule : rules) {
            current = rule.apply(current, ctx);
        }
        return new TranslationResult(current, report);
    }
}
```

`package-info.java` (replace file contents):
```java
/** Catalyst-style rule engine: fixed-order AST→AST rewrite rules (Phase 4). */
package rs.etf.sqltranslator.transform;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=RuleEngineTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: rule engine scaffolding with context, report, and fixed-order pipeline"
```

---

### Task 2: `TypeFamily` + `ScopedTransformer` (catalog-aware rule base)

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/TypeFamily.java`
- Create: `src/main/java/rs/etf/sqltranslator/transform/ScopedTransformer.java`
- Test: `src/test/java/rs/etf/sqltranslator/transform/ScopedTransformerTest.java`

**Interfaces:**
- Consumes: `AstTransformer`, `Catalog`, `TableSchema`, `ColumnInfo`, node records.
- Produces: `enum TypeFamily { NUMERIC, STRING, BOOLEAN, DATETIME, BINARY }` with `static TypeFamily of(GenericType)`; `abstract class ScopedTransformer extends AstTransformer` with protected `TranslationContext ctx`, `Optional<ColumnInfo> resolve(ColumnRef)`, `Optional<TypeFamily> familyOf(Expression)`, and overridable hooks `Object afterQuerySpecification(QuerySpecification rebuilt)` and `Object afterInsertStatement(InsertStatement rebuilt)` (both called with the scope still pushed; the four scope-owning `visitX` methods are `final`). All other `visitX` methods stay overridable — rules override them directly. Tasks 5–7 subclass this.

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.analysis.CatalogBuilder;
import rs.etf.sqltranslator.analysis.ColumnInfo;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedTransformerTest {

    private static final String DDL = """
            CREATE TABLE products (id INT, name VARCHAR(50), price DECIMAL(10,2));
            CREATE TABLE orders (id INT, product_id INT);
            """;

    /** Probe transformer: records what every ColumnRef resolves to. */
    private static final class Probe extends ScopedTransformer {
        final List<String> resolved = new ArrayList<>();

        Probe(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitColumnRef(ColumnRef node) {
            Optional<ColumnInfo> info = resolve(node);
            resolved.add(node.name().last().value() + "->"
                    + info.map(c -> c.type().type().name()).orElse("UNRESOLVED"));
            return super.visitColumnRef(node);
        }
    }

    private static Probe probe(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        TranslationContext ctx = new TranslationContext(dialect, Dialect.POSTGRESQL,
                CatalogBuilder.build(script), new TranslationReport());
        Probe p = new Probe(ctx);
        p.transform(script);
        return p;
    }

    @Test
    void resolvesAliasQualifiedColumnThroughFromScope() {
        Probe p = probe(DDL + "SELECT p.price FROM products p WHERE p.name = 'x';",
                Dialect.MYSQL);
        assertThat(p.resolved).contains("price->DECIMAL", "name->VARCHAR");
    }

    @Test
    void resolvesUnqualifiedColumnWhenUniqueAcrossScope() {
        Probe p = probe(DDL + "SELECT name FROM products p JOIN orders o ON p.id = o.product_id;",
                Dialect.MYSQL);
        assertThat(p.resolved).contains("name->VARCHAR");
    }

    @Test
    void ambiguousUnqualifiedColumnStaysUnresolved() {
        // `id` exists in both tables — must NOT guess.
        Probe p = probe(DDL + "SELECT id FROM products p JOIN orders o ON p.id = o.product_id;",
                Dialect.MYSQL);
        assertThat(p.resolved).contains("id->UNRESOLVED");
    }

    @Test
    void updateAndDeleteResolveAgainstTheirTargetTable() {
        Probe p = probe(DDL + "UPDATE products SET price = 1 WHERE name = 'x';"
                + "DELETE FROM orders WHERE product_id = 3;", Dialect.MYSQL);
        assertThat(p.resolved).contains("name->VARCHAR", "product_id->INTEGER");
    }

    @Test
    void typeFamilyCoversEveryGenericType() {
        for (GenericType type : GenericType.values()) {
            assertThat(TypeFamily.of(type)).isNotNull();
        }
        assertThat(TypeFamily.of(GenericType.NVARCHAR)).isEqualTo(TypeFamily.STRING);
        assertThat(TypeFamily.of(GenericType.DECIMAL)).isEqualTo(TypeFamily.NUMERIC);
        assertThat(TypeFamily.of(GenericType.TIMESTAMP)).isEqualTo(TypeFamily.DATETIME);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=ScopedTransformerTest test`
Expected: COMPILE ERROR — `ScopedTransformer`, `TypeFamily` do not exist.

- [ ] **Step 3: Write the implementation**

`TypeFamily.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.GenericType;

/** Coarse type families for mismatch detection (cast insertion, concat evidence). */
public enum TypeFamily {
    NUMERIC, STRING, BOOLEAN, DATETIME, BINARY;

    public static TypeFamily of(GenericType type) {
        return switch (type) {
            case TINYINT, SMALLINT, INTEGER, BIGINT, DECIMAL, FLOAT, DOUBLE -> NUMERIC;
            case CHAR, VARCHAR, NVARCHAR, TEXT -> STRING;
            case BOOLEAN -> BOOLEAN;
            case DATE, TIME, TIMESTAMP -> DATETIME;
            case BLOB -> BINARY;
        };
    }
}
```

`ScopedTransformer.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.ColumnInfo;
import rs.etf.sqltranslator.analysis.TableSchema;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.DeleteStatement;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UpdateStatement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Rule base for catalog-aware transforms. Tracks a stack of FROM-clause scopes
 * (alias or table name → TableSchema) so {@link #resolve(ColumnRef)} can answer
 * "what column is this?" — the Catalyst-analyzer move that makes type-dependent
 * rewrites decidable. Unresolvable references return empty; callers degrade to
 * warnings, never guesses.
 *
 * <p>Subclasses override the {@code afterX} hooks (called with scope still pushed)
 * instead of the corresponding {@code visitX} methods, which are final here.
 */
public abstract class ScopedTransformer extends rs.etf.sqltranslator.ast.AstTransformer {

    /** One table visible in the current scope, under its alias or its own name. */
    private record ScopedTable(String key, TableSchema schema) {
    }

    protected final TranslationContext ctx;
    private final Deque<List<ScopedTable>> scopes = new ArrayDeque<>();

    protected ScopedTransformer(TranslationContext ctx) {
        this.ctx = ctx;
    }

    // --- scope lifecycle (final: subclasses use the afterX hooks) ---

    @Override
    public final Object visitQuerySpecification(QuerySpecification node) {
        scopes.push(node.from().map(this::tablesOf).orElse(List.of()));
        try {
            return afterQuerySpecification(
                    (QuerySpecification) super.visitQuerySpecification(node));
        } finally {
            scopes.pop();
        }
    }

    @Override
    public final Object visitUpdateStatement(UpdateStatement node) {
        scopes.push(tableScope(node.table()));
        try {
            return super.visitUpdateStatement(node);
        } finally {
            scopes.pop();
        }
    }

    @Override
    public final Object visitDeleteStatement(DeleteStatement node) {
        scopes.push(tableScope(node.table()));
        try {
            return super.visitDeleteStatement(node);
        } finally {
            scopes.pop();
        }
    }

    @Override
    public final Object visitInsertStatement(InsertStatement node) {
        scopes.push(tableScope(node.table()));
        try {
            return afterInsertStatement((InsertStatement) super.visitInsertStatement(node));
        } finally {
            scopes.pop();
        }
    }

    /** Hook: rebuilt spec, scope still pushed. Default: identity. */
    protected Object afterQuerySpecification(QuerySpecification rebuilt) {
        return rebuilt;
    }

    /** Hook: rebuilt insert, its table scope still pushed. Default: identity. */
    protected Object afterInsertStatement(InsertStatement rebuilt) {
        return rebuilt;
    }

    // --- resolution ---

    /** Resolves a column reference against the current scope stack (top frame only). */
    protected final Optional<ColumnInfo> resolve(ColumnRef ref) {
        List<ScopedTable> frame = scopes.peek();
        if (frame == null) {
            return Optional.empty();
        }
        List<rs.etf.sqltranslator.ast.Identifier> parts = ref.name().parts();
        String column = parts.get(parts.size() - 1).value();
        if (parts.size() == 1) {
            List<ColumnInfo> hits = new ArrayList<>();
            for (ScopedTable table : frame) {
                table.schema().column(column).ifPresent(hits::add);
            }
            return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
        }
        String qualifier = parts.get(parts.size() - 2).value().toLowerCase(Locale.ROOT);
        for (ScopedTable table : frame) {
            if (table.key().equals(qualifier)) {
                return table.schema().column(column);
            }
        }
        return Optional.empty();
    }

    /** Best-effort type family: literals, catalog-resolved columns, CAST targets. */
    protected final Optional<TypeFamily> familyOf(Expression expr) {
        if (expr instanceof NumericLiteral) {
            return Optional.of(TypeFamily.NUMERIC);
        }
        if (expr instanceof StringLiteral) {
            return Optional.of(TypeFamily.STRING);
        }
        if (expr instanceof BooleanLiteral) {
            return Optional.of(TypeFamily.BOOLEAN);
        }
        if (expr instanceof CastExpression cast) {
            return Optional.of(TypeFamily.of(cast.targetType().type()));
        }
        if (expr instanceof ColumnRef ref) {
            return resolve(ref).map(c -> TypeFamily.of(c.type().type()));
        }
        if (expr instanceof rs.etf.sqltranslator.ast.BinaryOp op) {
            return Optional.of(switch (op.op()) {
                case ADD, SUB, MUL, DIV, MOD -> TypeFamily.NUMERIC;
                case CONCAT -> TypeFamily.STRING;
                case OR, AND, EQ, NEQ, LT, LTE, GT, GTE -> TypeFamily.BOOLEAN;
            });
        }
        if (expr instanceof rs.etf.sqltranslator.ast.UnaryOp op) {
            return Optional.of(switch (op.op()) {
                case NEG, POS -> TypeFamily.NUMERIC;
                case NOT -> TypeFamily.BOOLEAN;
            });
        }
        return Optional.empty();
    }

    // --- scope construction ---

    private List<ScopedTable> tablesOf(TableSource from) {
        List<ScopedTable> tables = new ArrayList<>(tableScope(from.first()));
        for (Join join : from.joins()) {
            tables.addAll(tableScope(join.table()));
        }
        return tables;
    }

    private List<ScopedTable> tableScope(TableRef ref) {
        String key = ref.alias().map(a -> a.value())
                .orElse(ref.table().last().value());
        return scopedTable(key, ref.table());
    }

    private List<ScopedTable> tableScope(QualifiedName table) {
        return scopedTable(table.last().value(), table);
    }

    private List<ScopedTable> scopedTable(String key, QualifiedName table) {
        return ctx.catalog().table(table.last().value())
                .map(schema -> List.of(new ScopedTable(key.toLowerCase(Locale.ROOT), schema)))
                .orElse(List.of());
    }
}
```

Note: `instanceof` chains over `Expression` inside a helper are pattern *tests*, not
a dispatch replacement — traversal still goes through the visitor (D1 intact). This
mirrors how `CatalogBuilder` observes nodes.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=ScopedTransformerTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full suite (transformer hooks must not break identity)**

Run: `./mvnw --batch-mode test`
Expected: all green, including `AstTransformerIdentityTest`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: scoped transformer base with catalog column resolution and type families"
```

---

### Task 3: `ValidateTargetCapabilitiesRule` + shared test helper

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/ValidateTargetCapabilitiesRule.java`
- Create: `src/test/java/rs/etf/sqltranslator/transform/TransformTestSupport.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java` (add rule to `standard()`)
- Test: `src/test/java/rs/etf/sqltranslator/transform/ValidateTargetCapabilitiesRuleTest.java`

**Interfaces:**
- Consumes: `Rule`, `TranslationContext`, `AstTransformer`, `UnsupportedFeatureException`.
- Produces: `ValidateTargetCapabilitiesRule implements Rule` (name `"validate-target-capabilities"`); test helper `TransformTestSupport.runRule(Rule, String sql, Dialect source, Dialect target) → TranslationResult` and `TransformTestSupport.selectExpr(Script, int stmtIndex, int itemIndex) → Expression` reused by every later rule test.

- [ ] **Step 1: Write the test helper and the failing test**

`TransformTestSupport.java`:
```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.CatalogBuilder;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import java.util.List;

/** Shared plumbing for rule tests: parse → context → single rule → result. */
final class TransformTestSupport {

    private TransformTestSupport() {
    }

    static TranslationResult runRule(Rule rule, String sql, Dialect source, Dialect target) {
        Script script = AstBuilderFacade.buildScript(sql, source);
        TranslationReport report = new TranslationReport();
        TranslationContext ctx = new TranslationContext(source, target,
                CatalogBuilder.build(script), report);
        return new TranslationResult(rule.apply(script, ctx), report);
    }

    /** Expression of the itemIndex-th select item of the stmtIndex-th statement. */
    static Expression selectExpr(Script script, int stmtIndex, int itemIndex) {
        SelectStatement select = (SelectStatement) script.statements().get(stmtIndex);
        List<rs.etf.sqltranslator.ast.SelectItem> items = select.query().first().items();
        return ((SelectExpr) items.get(itemIndex)).expr();
    }
}
```

`ValidateTargetCapabilitiesRuleTest.java`:
```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class ValidateTargetCapabilitiesRuleTest {

    private final Rule rule = new ValidateTargetCapabilitiesRule();

    @Test
    void fullJoinToMySqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT * FROM a FULL OUTER JOIN b ON a.x = b.x;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("FULL JOIN");
    }

    @Test
    void fullJoinToPostgresIsFine() {
        assertThatCode(() -> runRule(rule,
                "SELECT * FROM a FULL OUTER JOIN b ON a.x = b.x;",
                Dialect.TSQL, Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void offsetWithoutOrderByToTSqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT id FROM t LIMIT 3 OFFSET 5;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("OFFSET requires ORDER BY");
    }

    @Test
    void offsetWithOrderByToTSqlIsFine() {
        assertThatCode(() -> runRule(rule,
                "SELECT id FROM t ORDER BY id LIMIT 3 OFFSET 5;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .doesNotThrowAnyException();
    }

    @Test
    void offsetWithoutLimitToMySqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT id FROM t ORDER BY id OFFSET 5;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("OFFSET without LIMIT");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=ValidateTargetCapabilitiesRuleTest test`
Expected: COMPILE ERROR — `ValidateTargetCapabilitiesRule` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

/**
 * Batch 1 (Validate): refuse constructs the target cannot express. Traverses via
 * the identity transformer and throws instead of rewriting — fail fast, position
 * attached, never a silent wrong translation.
 */
public final class ValidateTargetCapabilitiesRule implements Rule {

    @Override
    public String name() {
        return "validate-target-capabilities";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Validator(ctx.target()).transform(script);
    }

    private static final class Validator extends AstTransformer {

        private final Dialect target;

        private Validator(Dialect target) {
            this.target = target;
        }

        @Override
        public Object visitJoin(Join node) {
            if (node.kind() == JoinKind.FULL && target == Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "FULL JOIN is not supported by MySQL", node.pos());
            }
            return super.visitJoin(node);
        }

        @Override
        public Object visitQuery(Query node) {
            node.limit().ifPresent(limit -> validateLimit(node, limit));
            return super.visitQuery(node);
        }

        private void validateLimit(Query query, RowLimit limit) {
            if (limit.offset().isEmpty()) {
                return;
            }
            if (target == Dialect.TSQL && query.orderBy().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "OFFSET requires ORDER BY on SQL Server", limit.pos());
            }
            if (target == Dialect.MYSQL && limit.count().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "OFFSET without LIMIT is not supported by MySQL", limit.pos());
            }
        }
    }
}
```

In `RuleEngine.standard()` replace the empty list:
```java
    public static RuleEngine standard() {
        return new RuleEngine(List.of(
                new ValidateTargetCapabilitiesRule()));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=ValidateTargetCapabilitiesRuleTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: target-capability validation rule (full join, offset legality)"
```

---

### Task 4: `NormalizeSourceFunctionsRule` (canonical function spellings)

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/NormalizeSourceFunctionsRule.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java` (append to `standard()`)
- Test: `src/test/java/rs/etf/sqltranslator/transform/NormalizeSourceFunctionsRuleTest.java`

**Interfaces:**
- Consumes: `Rule`, `AstTransformer`, `TransformTestSupport.runRule/selectExpr`, node records.
- Produces: `NormalizeSourceFunctionsRule implements Rule` (name `"normalize-source-functions"`). Canonical names later rules rely on: `NOW`, `COALESCE`, `CHAR_LENGTH`, `SUBSTRING`, `CEILING`, `YEAR`, `MONTH`, `DAY`; `CONCAT` exists only as `BinaryOp(CONCAT)` after this rule. Warning code `MYSQL_CHAR_LENGTH_ASSUMED`.

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class NormalizeSourceFunctionsRuleTest {

    private final Rule rule = new NormalizeSourceFunctionsRule();

    @Test
    void tsqlSpellingsFoldToCanonical() {
        TranslationResult r = runRule(rule,
                "SELECT GETDATE(), ISNULL(a, 0), LEN(name) FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("NOW");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("COALESCE");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 2)).name()).isEqualTo("CHAR_LENGTH");
    }

    @Test
    void mysqlSpellingsFoldToCanonical() {
        TranslationResult r = runRule(rule,
                "SELECT IFNULL(a, 0), SUBSTR(name, 2), CEIL(price), LENGTH(name) FROM t;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("COALESCE");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("SUBSTRING");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 2)).name()).isEqualTo("CEILING");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 3)).name()).isEqualTo("CHAR_LENGTH");
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("MYSQL_CHAR_LENGTH_ASSUMED"));
    }

    @Test
    void mysqlOneArgIsNullBecomesIsNullPredicate() {
        TranslationResult r = runRule(rule, "SELECT ISNULL(a) FROM t;",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(selectExpr(r.script(), 0, 0)).isInstanceOf(IsNullPredicate.class);
    }

    @Test
    void concatFunctionFoldsToLeftAssociativeConcatChain() {
        TranslationResult r = runRule(rule, "SELECT CONCAT(a, b, c) FROM t;",
                Dialect.MYSQL, Dialect.TSQL);
        BinaryOp outer = (BinaryOp) selectExpr(r.script(), 0, 0);
        assertThat(outer.op()).isEqualTo(BinaryOperator.CONCAT);
        assertThat(((BinaryOp) outer.left()).op()).isEqualTo(BinaryOperator.CONCAT);
    }

    @Test
    void postgresDatePartWithLiteralFieldFoldsToCanonicalExtractors() {
        TranslationResult r = runRule(rule,
                "SELECT DATE_PART('year', d), DATE_PART('month', d) FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("YEAR");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).args()).hasSize(1);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("MONTH");
    }

    @Test
    void aggregatesAndUnknownNamesPassUntouched() {
        TranslationResult r = runRule(rule, "SELECT COUNT(*), FOO(x) FROM t;",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("COUNT");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("FOO");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=NormalizeSourceFunctionsRuleTest test`
Expected: COMPILE ERROR — `NormalizeSourceFunctionsRule` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;

import java.util.List;
import java.util.Locale;

/**
 * Batch 2 (Normalize): fold source-specific function spellings into one canonical
 * name per concept, so every later rule matches a single spelling. Data lives in
 * small per-dialect rename tables; structural folds (ISNULL/1, CONCAT/n, DATE_PART)
 * are the only code paths.
 */
public final class NormalizeSourceFunctionsRule implements Rule {

    @Override
    public String name() {
        return "normalize-source-functions";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Normalizer(ctx).transform(script);
    }

    private static final class Normalizer extends AstTransformer {

        private final TranslationContext ctx;

        private Normalizer(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            FunctionCall call = (FunctionCall) super.visitFunctionCall(node);
            if (call.star() || call.quantifier().isPresent()) {
                return call;               // COUNT(*) / COUNT(DISTINCT x): aggregates
            }
            return switch (ctx.source()) {
                case TSQL -> tsql(call);
                case MYSQL -> mysql(call);
                case POSTGRESQL -> postgres(call);
            };
        }

        private Object tsql(FunctionCall call) {
            return switch (key(call)) {
                case "GETDATE/0" -> renamed(call, "NOW");
                case "ISNULL/2" -> renamed(call, "COALESCE");
                case "LEN/1" -> renamed(call, "CHAR_LENGTH");
                case "CONCAT/*" -> concatChain(call);
                default -> call;
            };
        }

        private Object mysql(FunctionCall call) {
            return switch (key(call)) {
                case "IFNULL/2" -> renamed(call, "COALESCE");
                case "ISNULL/1" -> new IsNullPredicate(call.args().get(0), false, call.pos());
                case "SUBSTR/2", "SUBSTR/3" -> renamed(call, "SUBSTRING");
                case "CEIL/1" -> renamed(call, "CEILING");
                case "LENGTH/1" -> {
                    ctx.report().warn("MYSQL_CHAR_LENGTH_ASSUMED",
                            "MySQL LENGTH() counts bytes; translated as character length",
                            call.pos());
                    yield renamed(call, "CHAR_LENGTH");
                }
                case "CONCAT/*" -> concatChain(call);
                default -> call;
            };
        }

        private Object postgres(FunctionCall call) {
            return switch (key(call)) {
                case "LENGTH/1" -> renamed(call, "CHAR_LENGTH");
                case "SUBSTR/2", "SUBSTR/3" -> renamed(call, "SUBSTRING");
                case "CEIL/1" -> renamed(call, "CEILING");
                case "DATE_PART/2" -> datePart(call);
                case "CONCAT/*" -> concatChain(call);
                default -> call;
            };
        }

        /** "NAME/argCount", with CONCAT of 2+ args collapsed to "CONCAT/*". */
        private static String key(FunctionCall call) {
            if (call.name().equals("CONCAT") && call.args().size() >= 2) {
                return "CONCAT/*";
            }
            return call.name() + "/" + call.args().size();
        }

        private static FunctionCall renamed(FunctionCall call, String canonical) {
            return new FunctionCall(canonical, call.args(), call.star(),
                    call.quantifier(), call.pos());
        }

        /** CONCAT(a, b, c) → ((a || b) || c) as BinaryOp(CONCAT) — the canonical form. */
        private static Expression concatChain(FunctionCall call) {
            Expression chain = call.args().get(0);
            for (Expression arg : call.args().subList(1, call.args().size())) {
                chain = new BinaryOp(BinaryOperator.CONCAT, chain, arg, call.pos());
            }
            return chain;
        }

        /** DATE_PART('year'|'month'|'day', x) → YEAR/MONTH/DAY(x); other fields pass. */
        private static Object datePart(FunctionCall call) {
            if (call.args().get(0) instanceof StringLiteral field) {
                String canonical = switch (field.value().toLowerCase(Locale.ROOT)) {
                    case "year" -> "YEAR";
                    case "month" -> "MONTH";
                    case "day" -> "DAY";
                    default -> null;
                };
                if (canonical != null) {
                    return new FunctionCall(canonical, List.of(call.args().get(1)),
                            false, call.quantifier(), call.pos());
                }
            }
            return call;
        }
    }
}
```

In `RuleEngine.standard()` append `new NormalizeSourceFunctionsRule()` after the validate rule.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=NormalizeSourceFunctionsRuleTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: source function normalization to canonical spellings"
```

---

### Task 5: `ResolveConcatRule` (T-SQL `+` disambiguation)

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/ResolveConcatRule.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java` (append to `standard()`)
- Test: `src/test/java/rs/etf/sqltranslator/transform/ResolveConcatRuleTest.java`

**Interfaces:**
- Consumes: `ScopedTransformer` (`familyOf`), `TypeFamily`, `BinaryOp`.
- Produces: `ResolveConcatRule implements Rule` (name `"resolve-concat"`). Warning code `AMBIGUOUS_PLUS`. After this rule, every T-SQL string concatenation is `BinaryOp(CONCAT)`.

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class ResolveConcatRuleTest {

    private static final String DDL =
            "CREATE TABLE t (name VARCHAR(50), price DECIMAL(10,2));";

    private final Rule rule = new ResolveConcatRule();

    @Test
    void plusWithStringLiteralBecomesConcat() {
        TranslationResult r = runRule(rule, "SELECT 'Mr ' + name FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 0, 0)).op())
                .isEqualTo(BinaryOperator.CONCAT);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void plusWithCatalogStringColumnBecomesConcat() {
        TranslationResult r = runRule(rule, DDL + "SELECT name + name FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 1, 0)).op())
                .isEqualTo(BinaryOperator.CONCAT);
    }

    @Test
    void numericPlusStaysAddition() {
        TranslationResult r = runRule(rule, DDL + "SELECT price + 1 FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 1, 0)).op())
                .isEqualTo(BinaryOperator.ADD);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void unresolvedOperandsStayAdditionWithWarning() {
        TranslationResult r = runRule(rule, "SELECT a + b FROM unknown_table;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 0, 0)).op())
                .isEqualTo(BinaryOperator.ADD);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("AMBIGUOUS_PLUS"));
    }

    @Test
    void nonTsqlSourceIsUntouched() {
        TranslationResult r = runRule(rule, "SELECT 'a' + 1;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) selectExpr(r.script(), 0, 0)).op())
                .isEqualTo(BinaryOperator.ADD);
        assertThat(r.report().warnings()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=ResolveConcatRuleTest test`
Expected: COMPILE ERROR — `ResolveConcatRule` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.Optional;

/**
 * T-SQL overloads {@code +} as string concatenation. With string evidence (literal
 * operand or catalog-resolved string column) the operator becomes the canonical
 * {@code BinaryOp(CONCAT)}; with full numeric evidence it stays ADD; otherwise it
 * stays ADD with an {@code AMBIGUOUS_PLUS} warning — a documented boundary of the
 * catalog approach, never a guess.
 */
public final class ResolveConcatRule implements Rule {

    @Override
    public String name() {
        return "resolve-concat";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.source() != Dialect.TSQL) {
            return script;
        }
        return new Resolver(ctx).transform(script);
    }

    private static final class Resolver extends ScopedTransformer {

        private Resolver(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (op.op() != BinaryOperator.ADD) {
                return op;
            }
            Optional<TypeFamily> left = familyOf(op.left());
            Optional<TypeFamily> right = familyOf(op.right());
            boolean anyString = left.filter(f -> f == TypeFamily.STRING).isPresent()
                    || right.filter(f -> f == TypeFamily.STRING).isPresent();
            if (anyString) {
                return new BinaryOp(BinaryOperator.CONCAT, op.left(), op.right(), op.pos());
            }
            if (left.isEmpty() || right.isEmpty()) {
                ctx.report().warn("AMBIGUOUS_PLUS",
                        "operand types unknown; T-SQL '+' kept as addition", op.pos());
            }
            return op;
        }
    }
}
```

In `RuleEngine.standard()` append `new ResolveConcatRule()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=ResolveConcatRuleTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: T-SQL plus disambiguation into canonical concat"
```

---

### Task 6: `InsertCastsRule` (the flagship catalog-aware rewrite)

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/InsertCastsRule.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java` (append to `standard()`)
- Test: `src/test/java/rs/etf/sqltranslator/transform/InsertCastsRuleTest.java`

**Interfaces:**
- Consumes: `ScopedTransformer` (`resolve`, `familyOf`), `CastExpression`, `Literal` subtypes.
- Produces: `InsertCastsRule implements Rule` (name `"insert-casts"`). Warning codes `CAST_INSERTED` (PG target, literal wrapped) and `IMPLICIT_CONVERSION` (T-SQL target, detection only). Scope: comparisons, `BETWEEN`, `IN`-list, between one **catalog-resolved column** and **numeric/string literals** — boolean literals belong to Task 7, unresolved columns degrade to no-op (the documented boundary).

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BetweenPredicate;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.InListPredicate;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class InsertCastsRuleTest {

    private static final String DDL =
            "CREATE TABLE products (name VARCHAR(50), price DECIMAL(10,2));";

    private final Rule rule = new InsertCastsRule();

    /** WHERE expression of the statement at stmtIndex. */
    private static Expression whereOf(Script script, int stmtIndex) {
        SelectStatement select = (SelectStatement) script.statements().get(stmtIndex);
        return select.query().first().where().orElseThrow();
    }

    @Test
    void numericLiteralAgainstVarcharColumnGetsCastForPostgres() {
        TranslationResult r = runRule(rule, DDL + "SELECT name FROM products WHERE name = 5;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        CastExpression cast = (CastExpression) where.right();
        assertThat(cast.targetType().type()).isEqualTo(GenericType.VARCHAR);
        assertThat(r.report().warnings()).anyMatch(w -> w.code().equals("CAST_INSERTED"));
    }

    @Test
    void stringLiteralAgainstDecimalColumnGetsCastForPostgres() {
        TranslationResult r = runRule(rule, DDL + "SELECT name FROM products WHERE price > '10';",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        assertThat(((CastExpression) where.right()).targetType().type())
                .isEqualTo(GenericType.DECIMAL);
    }

    @Test
    void matchingFamiliesAreLeftAlone() {
        TranslationResult r = runRule(rule, DDL + "SELECT name FROM products WHERE name = 'x';",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        assertThat(where.right()).isNotInstanceOf(CastExpression.class);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void unresolvedColumnIsLeftAloneWithoutWarning() {
        TranslationResult r = runRule(rule, "SELECT a FROM unknown_table WHERE a = 5;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 0);
        assertThat(where.right()).isNotInstanceOf(CastExpression.class);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void tsqlTargetOnlyWarns() {
        TranslationResult r = runRule(rule, DDL + "SELECT name FROM products WHERE name = 5;",
                Dialect.MYSQL, Dialect.TSQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        assertThat(where.right()).isNotInstanceOf(CastExpression.class);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("IMPLICIT_CONVERSION"));
    }

    @Test
    void betweenBoundsAndInListItemsGetCasts() {
        TranslationResult r = runRule(rule,
                DDL + "SELECT name FROM products WHERE price BETWEEN '1' AND '9';"
                        + "SELECT name FROM products WHERE name IN (1, 'x');",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        BetweenPredicate between = (BetweenPredicate) whereOf(r.script(), 1);
        assertThat(between.low()).isInstanceOf(CastExpression.class);
        assertThat(between.high()).isInstanceOf(CastExpression.class);
        InListPredicate in = (InListPredicate) whereOf(r.script(), 2);
        assertThat(in.items().get(0)).isInstanceOf(CastExpression.class);
        assertThat(in.items().get(1)).isNotInstanceOf(CastExpression.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=InsertCastsRuleTest test`
Expected: COMPILE ERROR — `InsertCastsRule` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.ColumnInfo;
import rs.etf.sqltranslator.ast.BetweenPredicate;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.InListPredicate;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;

import java.util.List;
import java.util.Optional;

/**
 * The flagship catalog rewrite (ROADMAP Phase 4): where the source dialect converts
 * implicitly but PostgreSQL would error, wrap the literal side of a comparison in an
 * explicit CAST to the catalog-resolved column's type. Decidable cases only —
 * unresolved columns are left untouched (documented boundary); T-SQL targets get a
 * warning instead of a rewrite (T-SQL converts implicitly, but by different
 * precedence rules worth surfacing).
 */
public final class InsertCastsRule implements Rule {

    @Override
    public String name() {
        return "insert-casts";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() == Dialect.MYSQL) {
            return script;                       // MySQL converts implicitly; nothing to do
        }
        return new Caster(ctx).transform(script);
    }

    private static final class Caster extends ScopedTransformer {

        private static final List<rs.etf.sqltranslator.ast.BinaryOperator> COMPARISONS =
                List.of(rs.etf.sqltranslator.ast.BinaryOperator.EQ,
                        rs.etf.sqltranslator.ast.BinaryOperator.NEQ,
                        rs.etf.sqltranslator.ast.BinaryOperator.LT,
                        rs.etf.sqltranslator.ast.BinaryOperator.LTE,
                        rs.etf.sqltranslator.ast.BinaryOperator.GT,
                        rs.etf.sqltranslator.ast.BinaryOperator.GTE);

        private Caster(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (!COMPARISONS.contains(op.op())) {
                return op;
            }
            Optional<ColumnInfo> leftColumn = columnOf(op.left());
            Optional<ColumnInfo> rightColumn = columnOf(op.right());
            if (leftColumn.isPresent() && isCastableLiteral(op.right())) {
                return new BinaryOp(op.op(), op.left(),
                        harmonize(op.right(), leftColumn.get()), op.pos());
            }
            if (rightColumn.isPresent() && isCastableLiteral(op.left())) {
                return new BinaryOp(op.op(),
                        harmonize(op.left(), rightColumn.get()), op.right(), op.pos());
            }
            return op;
        }

        @Override
        public Object visitBetweenPredicate(BetweenPredicate node) {
            BetweenPredicate between = (BetweenPredicate) super.visitBetweenPredicate(node);
            return columnOf(between.value())
                    .map(column -> (Object) new BetweenPredicate(between.value(),
                            harmonize(between.low(), column),
                            harmonize(between.high(), column),
                            between.negated(), between.pos()))
                    .orElse(between);
        }

        @Override
        public Object visitInListPredicate(InListPredicate node) {
            InListPredicate in = (InListPredicate) super.visitInListPredicate(node);
            return columnOf(in.value())
                    .map(column -> (Object) new InListPredicate(in.value(),
                            in.items().stream().map(item -> harmonize(item, column)).toList(),
                            in.negated(), in.pos()))
                    .orElse(in);
        }

        /** The expression's catalog column, if it is a resolved ColumnRef. */
        private Optional<ColumnInfo> columnOf(Expression expr) {
            return expr instanceof ColumnRef ref ? resolve(ref) : Optional.empty();
        }

        private static boolean isCastableLiteral(Expression expr) {
            return expr instanceof NumericLiteral || expr instanceof StringLiteral;
        }

        /** Wraps a mismatched literal in CAST (PG) or just warns (T-SQL). */
        private Expression harmonize(Expression literal, ColumnInfo column) {
            if (!isCastableLiteral(literal)) {
                return literal;
            }
            TypeFamily columnFamily = TypeFamily.of(column.type().type());
            TypeFamily literalFamily = familyOf(literal).orElseThrow();
            if (literalFamily == columnFamily) {
                return literal;
            }
            if (ctx.target() == Dialect.TSQL) {
                ctx.report().warn("IMPLICIT_CONVERSION",
                        "comparison of " + literalFamily + " literal with " + columnFamily
                                + " column '" + column.name().value()
                                + "' relies on implicit conversion", literal.pos());
                return literal;
            }
            ctx.report().warn("CAST_INSERTED",
                    "explicit CAST inserted: " + literalFamily + " literal compared with "
                            + columnFamily + " column '" + column.name().value() + "'",
                    literal.pos());
            return new CastExpression(literal, column.type(), literal.pos());
        }
    }
}
```

Note: `Literal` subtypes expose `pos()` via the `AstNode` interface — `literal.pos()`
compiles for any `Expression`.

In `RuleEngine.standard()` append `new InsertCastsRule()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=InsertCastsRuleTest test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: catalog-aware explicit cast insertion for strict targets"
```

---

### Task 7: `RewriteBooleanSemanticsRule`

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/RewriteBooleanSemanticsRule.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java` (append to `standard()`)
- Test: `src/test/java/rs/etf/sqltranslator/transform/RewriteBooleanSemanticsRuleTest.java`

**Interfaces:**
- Consumes: `ScopedTransformer` (`resolve`, `familyOf`, `afterQuerySpecification`, `afterInsertStatement`), node records.
- Produces: `RewriteBooleanSemanticsRule implements Rule` (name `"rewrite-boolean-semantics"`). Warning code `BOOLEAN_CONTEXT_UNRESOLVED`. Behavior matrix:
  - **T-SQL target:** every `BooleanLiteral` → `NumericLiteral "1"/"0"`.
  - **Boolean contexts** (WHERE, HAVING, JOIN…ON, searched-CASE WHEN): descend through AND/OR/NOT; predicates pass; a bare non-predicate expression `e` becomes `e <> 0` — except a catalog-resolved BOOLEAN expression under a PG target, and bare `BooleanLiteral`s under PG/MySQL targets, which stay bare. Unresolved bare columns under a PG target additionally warn.
  - **PG target boolean-column comparisons:** `bool_col = 1` → `bool_col`; `bool_col = 0` / `bool_col <> 1` → `NOT bool_col`; `bool_col <> 0` → `bool_col` (only for exact `0`/`1` numeric literals).
  - **PG target boolean assignments:** numeric `0`/`1` written to a catalog-resolved BOOLEAN column via `DEFAULT`, `INSERT` (positional or named), or `UPDATE SET` → `BooleanLiteral`.

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.ast.UnaryOp;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class RewriteBooleanSemanticsRuleTest {

    private static final String DDL =
            "CREATE TABLE flags (active BOOLEAN, qty INT);";

    private final Rule rule = new RewriteBooleanSemanticsRule();

    private static Expression whereOf(Script script, int stmtIndex) {
        SelectStatement select = (SelectStatement) script.statements().get(stmtIndex);
        return select.query().first().where().orElseThrow();
    }

    @Test
    void bareBooleanColumnBecomesNeqZeroForTsql() {
        TranslationResult r = runRule(rule, DDL + "SELECT qty FROM flags WHERE active;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        BinaryOp where = (BinaryOp) whereOf(r.script(), 1);
        assertThat(where.op()).isEqualTo(BinaryOperator.NEQ);
        assertThat(where.left()).isInstanceOf(ColumnRef.class);
        assertThat(((NumericLiteral) where.right()).text()).isEqualTo("0");
    }

    @Test
    void bareResolvedBooleanStaysBareForPostgres() {
        TranslationResult r = runRule(rule, DDL + "SELECT qty FROM flags WHERE active;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(whereOf(r.script(), 1)).isInstanceOf(ColumnRef.class);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void bareNumericColumnGetsTruthinessWrapForPostgres() {
        TranslationResult r = runRule(rule, DDL + "SELECT qty FROM flags WHERE qty;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) whereOf(r.script(), 1)).op()).isEqualTo(BinaryOperator.NEQ);
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void bareUnresolvedColumnWarnsForPostgres() {
        TranslationResult r = runRule(rule, "SELECT a FROM unknown_table WHERE a;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((BinaryOp) whereOf(r.script(), 0)).op()).isEqualTo(BinaryOperator.NEQ);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("BOOLEAN_CONTEXT_UNRESOLVED"));
    }

    @Test
    void andOrNotDescendIntoOperands() {
        TranslationResult r = runRule(rule,
                DDL + "SELECT qty FROM flags WHERE active AND NOT active;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        BinaryOp and = (BinaryOp) whereOf(r.script(), 1);
        assertThat(((BinaryOp) and.left()).op()).isEqualTo(BinaryOperator.NEQ);
        UnaryOp not = (UnaryOp) and.right();
        assertThat(((BinaryOp) not.operand()).op()).isEqualTo(BinaryOperator.NEQ);
    }

    @Test
    void booleanLiteralBecomesNumericEverywhereForTsql() {
        TranslationResult r = runRule(rule, "SELECT TRUE, FALSE;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        assertThat(((NumericLiteral) selectExpr(r.script(), 0, 0)).text()).isEqualTo("1");
        assertThat(((NumericLiteral) selectExpr(r.script(), 0, 1)).text()).isEqualTo("0");
    }

    @Test
    void booleanColumnComparedToOneOrZeroSimplifiesForPostgres() {
        TranslationResult r = runRule(rule,
                DDL + "SELECT qty FROM flags WHERE active = 1;"
                        + "SELECT qty FROM flags WHERE active = 0;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(whereOf(r.script(), 1)).isInstanceOf(ColumnRef.class);
        UnaryOp not = (UnaryOp) whereOf(r.script(), 2);
        assertThat(not.operand()).isInstanceOf(ColumnRef.class);
    }

    @Test
    void numericAssignmentsToBooleanColumnsBecomeBooleanLiteralsForPostgres() {
        TranslationResult r = runRule(rule,
                DDL + "INSERT INTO flags (active, qty) VALUES (1, 5);"
                        + "INSERT INTO flags VALUES (0, 9);"
                        + "UPDATE flags SET active = 1;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        InsertStatement named = (InsertStatement) r.script().statements().get(1);
        assertThat(named.rows().get(0).get(0)).isInstanceOf(BooleanLiteral.class);
        assertThat(named.rows().get(0).get(1)).isInstanceOf(NumericLiteral.class);
        InsertStatement positional = (InsertStatement) r.script().statements().get(2);
        assertThat(positional.rows().get(0).get(0)).isInstanceOf(BooleanLiteral.class);
        UpdateStatement update = (UpdateStatement) r.script().statements().get(3);
        assertThat(update.assignments().get(0).value()).isInstanceOf(BooleanLiteral.class);
    }

    @Test
    void booleanDefaultHarmonizesForPostgres() {
        TranslationResult r = runRule(rule,
                "CREATE TABLE t (active BOOLEAN DEFAULT 1);",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        CreateTableStatement create = (CreateTableStatement) r.script().statements().get(0);
        assertThat(create.columns().get(0).defaultValue().orElseThrow())
                .isInstanceOf(BooleanLiteral.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=RewriteBooleanSemanticsRuleTest test`
Expected: COMPILE ERROR — `RewriteBooleanSemanticsRule` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.analysis.TableSchema;
import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CaseExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UnaryOp;
import rs.etf.sqltranslator.ast.UnaryOperator;
import rs.etf.sqltranslator.ast.WhenClause;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * Boolean semantics across the three dialects (ROADMAP Phase 4): T-SQL has no
 * boolean literals or bare-boolean predicates; PostgreSQL demands real booleans in
 * predicate positions and refuses integer stand-ins. Bare expressions in boolean
 * contexts get an explicit truthiness comparison ({@code <> 0} — faithful to the
 * MySQL/T-SQL "nonzero is true" convention and identical for 0/1 booleans);
 * catalog-resolved BOOLEAN columns keep/regain idiomatic PG forms.
 */
public final class RewriteBooleanSemanticsRule implements Rule {

    @Override
    public String name() {
        return "rewrite-boolean-semantics";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Rewriter(ctx).transform(script);
    }

    private static final class Rewriter extends ScopedTransformer {

        private Rewriter(TranslationContext ctx) {
            super(ctx);
        }

        // --- literal conversion (T-SQL has no TRUE/FALSE) ---

        @Override
        public Object visitBooleanLiteral(BooleanLiteral node) {
            if (ctx.target() == Dialect.TSQL) {
                return new NumericLiteral(node.value() ? "1" : "0", false, node.pos());
            }
            return node;
        }

        // --- boolean contexts: WHERE / HAVING / JOIN ON / searched-CASE WHEN ---

        @Override
        protected Object afterQuerySpecification(QuerySpecification spec) {
            Optional<TableSource> from = spec.from().map(f -> new TableSource(
                    f.first(),
                    f.joins().stream().map(j -> new Join(j.kind(), j.table(),
                            j.on().map(this::bool), j.pos())).toList(),
                    f.pos()));
            return new QuerySpecification(spec.quantifier(), spec.items(), from,
                    spec.where().map(this::bool), spec.groupBy(),
                    spec.having().map(this::bool), spec.pos());
        }

        @Override
        public Object visitCaseExpression(CaseExpression node) {
            CaseExpression rebuilt = (CaseExpression) super.visitCaseExpression(node);
            if (rebuilt.operand().isPresent()) {
                return rebuilt;                       // simple CASE: WHENs are values
            }
            List<WhenClause> whens = rebuilt.whens().stream()
                    .map(w -> new WhenClause(bool(w.condition()), w.result(), w.pos()))
                    .toList();
            return new CaseExpression(rebuilt.operand(), whens, rebuilt.elseValue(),
                    rebuilt.pos());
        }

        /** Makes an expression legal in a boolean context for the target. */
        private Expression bool(Expression expr) {
            if (expr instanceof BinaryOp op) {
                if (op.op() == BinaryOperator.AND || op.op() == BinaryOperator.OR) {
                    return new BinaryOp(op.op(), bool(op.left()), bool(op.right()), op.pos());
                }
                return op;                            // comparisons are already predicates
            }
            if (expr instanceof UnaryOp op && op.op() == UnaryOperator.NOT) {
                return new UnaryOp(UnaryOperator.NOT, bool(op.operand()), op.pos());
            }
            if (expr instanceof rs.etf.sqltranslator.ast.BetweenPredicate
                    || expr instanceof rs.etf.sqltranslator.ast.LikePredicate
                    || expr instanceof rs.etf.sqltranslator.ast.InListPredicate
                    || expr instanceof rs.etf.sqltranslator.ast.InSubqueryPredicate
                    || expr instanceof rs.etf.sqltranslator.ast.IsNullPredicate
                    || expr instanceof rs.etf.sqltranslator.ast.ExistsPredicate) {
                return expr;
            }
            if (expr instanceof BooleanLiteral) {
                return expr;                          // PG/MySQL targets: bare is legal
            }                                         // (T-SQL: already a NumericLiteral)
            Optional<TypeFamily> family = familyOf(expr);
            boolean resolvedBoolean = family.filter(f -> f == TypeFamily.BOOLEAN).isPresent();
            if (ctx.target() == Dialect.POSTGRESQL && resolvedBoolean) {
                return expr;                          // bare boolean is idiomatic PG
            }
            if (ctx.target() == Dialect.POSTGRESQL && family.isEmpty()) {
                ctx.report().warn("BOOLEAN_CONTEXT_UNRESOLVED",
                        "bare expression in boolean context has unknown type; "
                                + "assumed numeric truthiness (<> 0)", expr.pos());
            }
            return new BinaryOp(BinaryOperator.NEQ, expr,
                    new NumericLiteral("0", false, expr.pos()), expr.pos());
        }

        // --- PG target: boolean-column comparisons and assignments ---

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (ctx.target() != Dialect.POSTGRESQL
                    || (op.op() != BinaryOperator.EQ && op.op() != BinaryOperator.NEQ)) {
                return op;
            }
            Expression column = booleanColumn(op.left()) ? op.left()
                    : booleanColumn(op.right()) ? op.right() : null;
            Expression other = column == op.left() ? op.right() : op.left();
            if (column == null || !(other instanceof NumericLiteral literal)
                    || !(literal.text().equals("0") || literal.text().equals("1"))) {
                return op;
            }
            boolean truthy = literal.text().equals("1") == (op.op() == BinaryOperator.EQ);
            return truthy ? column
                    : new UnaryOp(UnaryOperator.NOT, column, op.pos());
        }

        @Override
        public Object visitColumnDefinition(ColumnDefinition node) {
            ColumnDefinition column = (ColumnDefinition) super.visitColumnDefinition(node);
            if (ctx.target() != Dialect.POSTGRESQL
                    || column.type().type() != GenericType.BOOLEAN) {
                return column;
            }
            Optional<Expression> harmonized = column.defaultValue().map(this::asBooleanLiteral);
            return new ColumnDefinition(column.name(), column.type(), column.autoIncrement(),
                    column.nullable(), harmonized, column.primaryKey(), column.unique(),
                    column.references(), column.pos());
        }

        @Override
        protected Object afterInsertStatement(InsertStatement insert) {
            if (ctx.target() != Dialect.POSTGRESQL) {
                return insert;
            }
            Optional<TableSchema> schema =
                    ctx.catalog().table(insert.table().last().value());
            if (schema.isEmpty()) {
                return insert;
            }
            List<List<Expression>> rows = insert.rows().stream()
                    .map(row -> harmonizeRow(row, insert.columns(), schema.get()))
                    .toList();
            return new InsertStatement(insert.table(), insert.columns(), rows, insert.pos());
        }

        @Override
        public Object visitAssignment(Assignment node) {
            Assignment assignment = (Assignment) super.visitAssignment(node);
            if (ctx.target() != Dialect.POSTGRESQL) {
                return assignment;
            }
            ColumnRef ref = syntheticRef(assignment.column());
            boolean isBoolean = resolve(ref)
                    .filter(c -> c.type().type() == GenericType.BOOLEAN).isPresent();
            if (!isBoolean) {
                return assignment;
            }
            return new Assignment(assignment.column(),
                    asBooleanLiteral(assignment.value()), assignment.pos());
        }

        // --- helpers ---

        private List<Expression> harmonizeRow(List<Expression> row,
                                              List<Identifier> namedColumns,
                                              TableSchema schema) {
            List<Expression> out = new java.util.ArrayList<>(row.size());
            for (int i = 0; i < row.size(); i++) {
                GenericType type = columnTypeAt(i, namedColumns, schema);
                out.add(type == GenericType.BOOLEAN
                        ? asBooleanLiteral(row.get(i)) : row.get(i));
            }
            return out;
        }

        /** Column type at value position i: named list if present, else declaration order. */
        private GenericType columnTypeAt(int i, List<Identifier> namedColumns,
                                         TableSchema schema) {
            if (!namedColumns.isEmpty()) {
                return i < namedColumns.size()
                        ? schema.column(namedColumns.get(i).value())
                                .map(c -> c.type().type()).orElse(null)
                        : null;
            }
            return i < schema.columns().size()
                    ? schema.columns().get(i).type().type() : null;
        }

        private Expression asBooleanLiteral(Expression expr) {
            if (expr instanceof NumericLiteral literal) {
                if (literal.text().equals("1")) {
                    return new BooleanLiteral(true, literal.pos());
                }
                if (literal.text().equals("0")) {
                    return new BooleanLiteral(false, literal.pos());
                }
            }
            return expr;
        }

        private boolean booleanColumn(Expression expr) {
            return expr instanceof ColumnRef ref && resolve(ref)
                    .filter(c -> c.type().type() == GenericType.BOOLEAN).isPresent();
        }

        /** Wraps an assignment's bare Identifier as a ColumnRef so resolve() applies. */
        private static ColumnRef syntheticRef(Identifier column) {
            SourcePosition pos = column.pos();
            return new ColumnRef(new QualifiedName(List.of(column), pos), pos);
        }
    }
}
```

In `RuleEngine.standard()` append `new RewriteBooleanSemanticsRule()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=RewriteBooleanSemanticsRuleTest test`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: boolean semantics rewriting across dialect conventions"
```

---

### Task 8: `NarrowTypesRule`

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/NarrowTypesRule.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java` (append to `standard()`)
- Test: `src/test/java/rs/etf/sqltranslator/transform/NarrowTypesRuleTest.java`

**Interfaces:**
- Consumes: `AstTransformer`, `DataType`, `TypeLength`/`FixedLength`/`MaxLength`, `GenericType`.
- Produces: `NarrowTypesRule implements Rule` (name `"narrow-types"`). Warning codes `TINYINT_WIDENED`, `TINYINT_SIGNEDNESS`. After this rule every `DataType` is target-representable; Phase 5 printers only render names (`BOOLEAN→BIT`, `DOUBLE→FLOAT`, `BLOB→VARBINARY(MAX)` for T-SQL etc. are *rendering*, not narrowing, and stay in Phase 5).

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.FixedLength;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class NarrowTypesRuleTest {

    private final Rule rule = new NarrowTypesRule();

    private static DataType columnType(Script script, int stmtIndex, int columnIndex) {
        CreateTableStatement create =
                (CreateTableStatement) script.statements().get(stmtIndex);
        return create.columns().get(columnIndex).type();
    }

    @Test
    void nvarcharNarrowsForMySqlAndPostgres() {
        String ddl = "CREATE TABLE t (a NVARCHAR(50), b NVARCHAR(MAX), c VARCHAR(MAX));";
        TranslationResult r = runRule(rule, ddl, Dialect.TSQL, Dialect.MYSQL);
        assertThat(columnType(r.script(), 0, 0).type()).isEqualTo(GenericType.VARCHAR);
        assertThat(columnType(r.script(), 0, 0).length().orElseThrow())
                .isEqualTo(new FixedLength(50));
        assertThat(columnType(r.script(), 0, 1).type()).isEqualTo(GenericType.TEXT);
        assertThat(columnType(r.script(), 0, 1).length()).isEmpty();
        assertThat(columnType(r.script(), 0, 2).type()).isEqualTo(GenericType.TEXT);
    }

    @Test
    void textBecomesNvarcharMaxForTSql() {
        TranslationResult r = runRule(rule, "CREATE TABLE t (d TEXT);",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(columnType(r.script(), 0, 0).type()).isEqualTo(GenericType.NVARCHAR);
        assertThat(columnType(r.script(), 0, 0).length().orElseThrow())
                .isEqualTo(new MaxLength());
    }

    @Test
    void tinyintWidensToSmallintForPostgresWithWarning() {
        TranslationResult r = runRule(rule, "CREATE TABLE t (a TINYINT);",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(columnType(r.script(), 0, 0).type()).isEqualTo(GenericType.SMALLINT);
        assertThat(r.report().warnings()).anyMatch(w -> w.code().equals("TINYINT_WIDENED"));
    }

    @Test
    void tinyintAcrossTsqlMysqlWarnsAboutSignedness() {
        TranslationResult r = runRule(rule, "CREATE TABLE t (a TINYINT);",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(columnType(r.script(), 0, 0).type()).isEqualTo(GenericType.TINYINT);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("TINYINT_SIGNEDNESS"));
    }

    @Test
    void castTargetTypesNarrowToo() {
        TranslationResult r = runRule(rule, "SELECT CAST(x AS NVARCHAR(20)) FROM t;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        CastExpression cast = (CastExpression) selectExpr(r.script(), 0, 0);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.VARCHAR);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=NarrowTypesRuleTest test`
Expected: COMPILE ERROR — `NarrowTypesRule` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/**
 * Rewrites generic types the target cannot express into ones it can, with loss
 * warnings. Name rendering (BOOLEAN→BIT, DOUBLE→FLOAT, BLOB→VARBINARY(MAX), …) is
 * Phase 5's job; this rule only changes the generic structure. DataType carries no
 * position, so narrowing happens at the two owners (column definitions, casts) whose
 * positions anchor the warnings.
 */
public final class NarrowTypesRule implements Rule {

    @Override
    public String name() {
        return "narrow-types";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Narrower(ctx).transform(script);
    }

    private static final class Narrower extends AstTransformer {

        private final TranslationContext ctx;

        private Narrower(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitColumnDefinition(ColumnDefinition node) {
            ColumnDefinition column = (ColumnDefinition) super.visitColumnDefinition(node);
            return new ColumnDefinition(column.name(), narrow(column.type(), column.pos()),
                    column.autoIncrement(), column.nullable(), column.defaultValue(),
                    column.primaryKey(), column.unique(), column.references(), column.pos());
        }

        @Override
        public Object visitCastExpression(CastExpression node) {
            CastExpression cast = (CastExpression) super.visitCastExpression(node);
            return new CastExpression(cast.operand(), narrow(cast.targetType(), cast.pos()),
                    cast.pos());
        }

        private DataType narrow(DataType type, SourcePosition pos) {
            boolean toMySqlOrPg = ctx.target() == Dialect.MYSQL
                    || ctx.target() == Dialect.POSTGRESQL;
            boolean maxLength = type.length().filter(l -> l instanceof MaxLength).isPresent();

            if (toMySqlOrPg && type.type() == GenericType.NVARCHAR) {
                return maxLength
                        ? new DataType(GenericType.TEXT, Optional.empty(), Optional.empty())
                        : new DataType(GenericType.VARCHAR, type.length(), type.scale());
            }
            if (toMySqlOrPg && type.type() == GenericType.VARCHAR && maxLength) {
                return new DataType(GenericType.TEXT, Optional.empty(), Optional.empty());
            }
            if (ctx.target() == Dialect.TSQL && type.type() == GenericType.TEXT) {
                return new DataType(GenericType.NVARCHAR,
                        Optional.of(new MaxLength()), Optional.empty());
            }
            if (type.type() == GenericType.TINYINT) {
                if (ctx.target() == Dialect.POSTGRESQL) {
                    ctx.report().warn("TINYINT_WIDENED",
                            "PostgreSQL has no TINYINT; widened to SMALLINT", pos);
                    return new DataType(GenericType.SMALLINT, Optional.empty(),
                            Optional.empty());
                }
                boolean crossesSignedness =
                        (ctx.source() == Dialect.MYSQL && ctx.target() == Dialect.TSQL)
                        || (ctx.source() == Dialect.TSQL && ctx.target() == Dialect.MYSQL);
                if (crossesSignedness) {
                    ctx.report().warn("TINYINT_SIGNEDNESS",
                            "TINYINT is signed in MySQL but unsigned in SQL Server; "
                                    + "value ranges differ", pos);
                }
            }
            return type;
        }
    }
}
```

In `RuleEngine.standard()` append `new NarrowTypesRule()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=NarrowTypesRuleTest test`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: target-conditional generic type narrowing with loss warnings"
```

---

### Task 9: `RenderTargetFunctionsRule`

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/RenderTargetFunctionsRule.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java` (append to `standard()`)
- Test: `src/test/java/rs/etf/sqltranslator/transform/RenderTargetFunctionsRuleTest.java`

**Interfaces:**
- Consumes: `ScopedTransformer` (`familyOf`), canonical names from Task 4, `BinaryOp(CONCAT)` canonical form from Tasks 4–5.
- Produces: `RenderTargetFunctionsRule implements Rule` (name `"render-target-functions"`). Warning codes `FUNCTION_PASSTHROUGH`, `CONCAT_OPERAND_UNRESOLVED`. After this rule every function call and concat is directly printable by the Phase 5 printers (T-SQL `CONCAT` op renders `+`; PG renders `||`; MySQL sees only `CONCAT(...)` calls).

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class RenderTargetFunctionsRuleTest {

    private static final String DDL =
            "CREATE TABLE products (name VARCHAR(50), price DECIMAL(10,2));";

    private final Rule rule = new RenderTargetFunctionsRule();

    @Test
    void canonicalNamesRenameForTSql() {
        TranslationResult r = runRule(rule,
                "SELECT NOW(), CHAR_LENGTH(name) FROM t;", Dialect.MYSQL, Dialect.TSQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("GETDATE");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).name()).isEqualTo("LEN");
    }

    @Test
    void twoArgSubstringGainsLengthArgForTSql() {
        TranslationResult r = runRule(rule,
                "SELECT SUBSTRING(name, 2) FROM t;", Dialect.MYSQL, Dialect.TSQL);
        FunctionCall substring = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(substring.args()).hasSize(3);
        FunctionCall len = (FunctionCall) substring.args().get(2);
        assertThat(len.name()).isEqualTo("LEN");
    }

    @Test
    void yearMonthDayBecomeDatePartForPostgres() {
        TranslationResult r = runRule(rule,
                "SELECT YEAR(d), DAY(d) FROM t;", Dialect.MYSQL, Dialect.POSTGRESQL);
        FunctionCall year = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(year.name()).isEqualTo("DATE_PART");
        assertThat(((StringLiteral) year.args().get(0)).value()).isEqualTo("year");
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 1)).args()).hasSize(2);
    }

    @Test
    void concatChainBecomesConcatCallForMySql() {
        TranslationResult r = runRule(rule,
                "SELECT a || b || c FROM t;", Dialect.POSTGRESQL, Dialect.MYSQL);
        FunctionCall concat = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(concat.name()).isEqualTo("CONCAT");
        assertThat(concat.args()).hasSize(3);
    }

    @Test
    void nonStringConcatOperandGetsCastForTSql() {
        TranslationResult r = runRule(rule,
                DDL + "SELECT 'x' || price FROM products;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        BinaryOp concat = (BinaryOp) selectExpr(r.script(), 1, 0);
        CastExpression cast = (CastExpression) concat.right();
        assertThat(cast.targetType().type()).isEqualTo(GenericType.NVARCHAR);
        assertThat(cast.targetType().length().orElseThrow()).isEqualTo(new MaxLength());
    }

    @Test
    void unresolvedConcatOperandWarnsForTSql() {
        TranslationResult r = runRule(rule, "SELECT a || b FROM unknown_table;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("CONCAT_OPERAND_UNRESOLVED"));
    }

    @Test
    void unknownFunctionPassesThroughWithWarning() {
        TranslationResult r = runRule(rule, "SELECT FOO(x) FROM t;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(((FunctionCall) selectExpr(r.script(), 0, 0)).name()).isEqualTo("FOO");
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("FUNCTION_PASSTHROUGH"));
    }

    @Test
    void aggregatesNeverWarn() {
        TranslationResult r = runRule(rule,
                "SELECT COUNT(*), MAX(price), SUM(price) FROM products;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(r.report().warnings()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=RenderTargetFunctionsRuleTest test`
Expected: COMPILE ERROR — `RenderTargetFunctionsRule` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical → target function rendering: the function-mapping table as data plus the
 * few argument adapters that need code (ROADMAP Phase 4). Also lowers the canonical
 * CONCAT operator into each target's mechanism. Functions outside the table pass
 * through unchanged with a warning — the honest treatment of the vendor long tail.
 */
public final class RenderTargetFunctionsRule implements Rule {

    /** Canonical names with identical spelling and arity in all three targets. */
    private static final Set<String> UNIVERSAL = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX",
            "COALESCE", "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM",
            "REPLACE", "ABS", "ROUND", "FLOOR", "CEILING", "LEFT", "RIGHT", "SUBSTRING");

    /** Canonical names handled by rename tables or adapters below. */
    private static final Set<String> MAPPED = Set.of(
            "NOW", "CHAR_LENGTH", "YEAR", "MONTH", "DAY");

    private static final Map<String, String> TSQL_RENAMES = Map.of(
            "NOW", "GETDATE",
            "CHAR_LENGTH", "LEN");

    @Override
    public String name() {
        return "render-target-functions";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Renderer(ctx).transform(script);
    }

    private static final class Renderer extends ScopedTransformer {

        private Renderer(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            FunctionCall call = (FunctionCall) super.visitFunctionCall(node);
            if (call.star() || call.quantifier().isPresent()) {
                return call;
            }
            String name = call.name();
            if (!UNIVERSAL.contains(name) && !MAPPED.contains(name)) {
                ctx.report().warn("FUNCTION_PASSTHROUGH",
                        "function " + name + " is outside the mapping table; "
                                + "passed through unchanged", call.pos());
                return call;
            }
            if (ctx.target() == Dialect.TSQL) {
                if (name.equals("SUBSTRING") && call.args().size() == 2) {
                    return substringAdapter(call);
                }
                String renamed = TSQL_RENAMES.getOrDefault(name, name);
                if (!renamed.equals(name)) {
                    return new FunctionCall(renamed, call.args(), false,
                            call.quantifier(), call.pos());
                }
                return call;
            }
            if (ctx.target() == Dialect.POSTGRESQL
                    && (name.equals("YEAR") || name.equals("MONTH") || name.equals("DAY"))) {
                List<Expression> args = List.of(
                        new StringLiteral(name.toLowerCase(Locale.ROOT), false, call.pos()),
                        call.args().get(0));
                return new FunctionCall("DATE_PART", args, false,
                        call.quantifier(), call.pos());
            }
            return call;             // MySQL: every canonical spelling is native
        }

        /** T-SQL SUBSTRING requires 3 args: append LEN(<string>) as the length. */
        private static FunctionCall substringAdapter(FunctionCall call) {
            List<Expression> args = new ArrayList<>(call.args());
            args.add(new FunctionCall("LEN", List.of(call.args().get(0)), false,
                    Optional.empty(), call.pos()));
            return new FunctionCall("SUBSTRING", args, false, call.quantifier(), call.pos());
        }

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (op.op() != BinaryOperator.CONCAT) {
                return op;
            }
            if (ctx.target() == Dialect.MYSQL) {
                // Bottom-up: an inner chain is already a CONCAT(...) call — merge it.
                List<Expression> args = new ArrayList<>();
                flatten(op.left(), args);
                flatten(op.right(), args);
                return new FunctionCall("CONCAT", args, false, Optional.empty(), op.pos());
            }
            if (ctx.target() == Dialect.TSQL) {
                return new BinaryOp(BinaryOperator.CONCAT,
                        stringOperand(op.left()), stringOperand(op.right()), op.pos());
            }
            return op;               // PostgreSQL prints ||
        }

        private static void flatten(Expression expr, List<Expression> into) {
            if (expr instanceof FunctionCall call && call.name().equals("CONCAT")
                    && !call.star() && call.quantifier().isEmpty()) {
                into.addAll(call.args());
            } else {
                into.add(expr);
            }
        }

        /** T-SQL '+' concatenates only strings: cast known non-strings, warn on unknown. */
        private Expression stringOperand(Expression operand) {
            Optional<TypeFamily> family = familyOf(operand);
            if (family.isEmpty()) {
                ctx.report().warn("CONCAT_OPERAND_UNRESOLVED",
                        "concat operand type unknown; emitted without CAST — "
                                + "T-SQL '+' may fail or add", operand.pos());
                return operand;
            }
            if (family.get() == TypeFamily.STRING) {
                return operand;
            }
            return new CastExpression(operand,
                    new DataType(GenericType.NVARCHAR, Optional.of(new MaxLength()),
                            Optional.empty()), operand.pos());
        }
    }
}
```

In `RuleEngine.standard()` append `new RenderTargetFunctionsRule()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=RenderTargetFunctionsRuleTest test`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: canonical-to-target function rendering with argument adapters"
```

---

### Task 10: `DropNullsOrderingRule`

**Files:**
- Create: `src/main/java/rs/etf/sqltranslator/transform/DropNullsOrderingRule.java`
- Modify: `src/main/java/rs/etf/sqltranslator/transform/RuleEngine.java` (append to `standard()`)
- Test: `src/test/java/rs/etf/sqltranslator/transform/DropNullsOrderingRuleTest.java`

**Interfaces:**
- Consumes: `AstTransformer`, `OrderItem`.
- Produces: `DropNullsOrderingRule implements Rule` (name `"drop-nulls-ordering"`). Warning code `NULLS_ORDERING_DROPPED`.

- [ ] **Step 1: Write the failing test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class DropNullsOrderingRuleTest {

    private static final String SQL =
            "SELECT id FROM t ORDER BY a DESC NULLS LAST, b;";

    private final Rule rule = new DropNullsOrderingRule();

    private static OrderItem orderItem(Script script, int index) {
        return ((SelectStatement) script.statements().get(0)).query().orderBy().get(index);
    }

    @Test
    void nullsOrderingIsDroppedWithWarningForMySql() {
        TranslationResult r = runRule(rule, SQL, Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(orderItem(r.script(), 0).nulls()).isEmpty();
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("NULLS_ORDERING_DROPPED"));
    }

    @Test
    void nullsOrderingIsKeptForPostgres() {
        TranslationResult r = runRule(rule, SQL, Dialect.POSTGRESQL, Dialect.POSTGRESQL);
        assertThat(orderItem(r.script(), 0).nulls()).isPresent();
        assertThat(r.report().warnings()).isEmpty();
    }

    @Test
    void itemsWithoutNullsOrderingNeverWarn() {
        TranslationResult r = runRule(rule, "SELECT id FROM t ORDER BY b;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        assertThat(r.report().warnings()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw --batch-mode -Dtest=DropNullsOrderingRuleTest test`
Expected: COMPILE ERROR — `DropNullsOrderingRule` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.Optional;

/**
 * MySQL and T-SQL cannot express NULLS FIRST/LAST. The clause is dropped with a
 * warning naming what was lost — the target's default NULL placement takes over
 * (emulation via ISNULL-style sort keys is out of v1 scope, documented limitation).
 */
public final class DropNullsOrderingRule implements Rule {

    @Override
    public String name() {
        return "drop-nulls-ordering";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() == Dialect.POSTGRESQL) {
            return script;
        }
        return new Dropper(ctx).transform(script);
    }

    private static final class Dropper extends AstTransformer {

        private final TranslationContext ctx;

        private Dropper(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitOrderItem(OrderItem node) {
            OrderItem item = (OrderItem) super.visitOrderItem(node);
            if (item.nulls().isEmpty()) {
                return item;
            }
            ctx.report().warn("NULLS_ORDERING_DROPPED",
                    "NULLS " + item.nulls().get() + " is not expressible in "
                            + ctx.target() + "; target default NULL placement applies",
                    item.pos());
            return new OrderItem(item.expr(), item.direction(), Optional.empty(),
                    item.pos());
        }
    }
}
```

In `RuleEngine.standard()` append `new DropNullsOrderingRule()` — the sequence is now complete:

```java
    public static RuleEngine standard() {
        return new RuleEngine(List.of(
                new ValidateTargetCapabilitiesRule(),
                new NormalizeSourceFunctionsRule(),
                new ResolveConcatRule(),
                new InsertCastsRule(),
                new RewriteBooleanSemanticsRule(),
                new NarrowTypesRule(),
                new RenderTargetFunctionsRule(),
                new DropNullsOrderingRule()));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw --batch-mode -Dtest=DropNullsOrderingRuleTest test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/rs/etf/sqltranslator/transform src/test/java/rs/etf/sqltranslator/transform
git commit -m "feat: drop NULLS ordering for targets that cannot express it"
```

---

### Task 11: Engine-level corpus smoke, determinism, and docs

**Files:**
- Modify: `src/test/java/rs/etf/sqltranslator/parser/CaseFiles.java` (make class and its three members `public` so the transform package can reuse the corpus walker)
- Test: `src/test/java/rs/etf/sqltranslator/transform/RuleEngineCorpusTest.java`
- Modify: `EXTENDING.md` (append the add-a-rule recipe)

**Interfaces:**
- Consumes: `RuleEngine.standard()`, `CaseFiles.under(...)`, `AstBuilderFacade`, `AstDumper`.
- Produces: the Phase 4 exit evidence — every corpus case translates in all 3 directions (or refuses per the manifest below), twice-run byte-identical.

**Refusal manifest** (the only legitimate direction-refusals in today's corpus):
- `joins/full-join/input.tsql.sql` × target `MYSQL` — FULL JOIN
- `joins/full-join/input.postgresql.sql` × target `MYSQL` — FULL JOIN
- `limits/limit-offset/input.mysql.sql` × target `TSQL` — its second statement (`LIMIT 20, 10`) has an offset but no ORDER BY

Any refusal outside this manifest is a rule bug — investigate, do not extend the manifest without understanding why.

- [ ] **Step 1: Widen `CaseFiles` visibility**

In `src/test/java/rs/etf/sqltranslator/parser/CaseFiles.java` change `final class CaseFiles` → `public final class CaseFiles`, and make `under`, `files`, `displayName` `public`. No logic changes.

- [ ] **Step 2: Write the failing corpus test**

```java
package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;
import rs.etf.sqltranslator.parser.CaseFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 exit evidence: the standard rule sequence over the whole corpus, all
 * three targets, twice — no unexpected refusal, byte-identical reruns.
 */
class RuleEngineCorpusTest {

    private static final Set<String> EXPECTED_REFUSALS = Set.of(
            "joins/full-join/input.tsql.sql|MYSQL",
            "joins/full-join/input.postgresql.sql|MYSQL",
            "limits/limit-offset/input.mysql.sql|TSQL");

    @TestFactory
    Stream<DynamicTest> everyCaseTranslatesDeterministicallyInAllDirections() {
        CaseFiles corpus = CaseFiles.under("/cases",
                p -> p.getFileName().toString().startsWith("input.")
                        && !p.toString().replace('\\', '/').contains("/unsupported/"));
        return corpus.files().stream().flatMap(file ->
                Stream.of(Dialect.values()).map(target -> DynamicTest.dynamicTest(
                        corpus.displayName(file) + " -> " + target,
                        () -> check(corpus, file, target))));
    }

    private void check(CaseFiles corpus, Path file, Dialect target) throws Exception {
        String sql = Files.readString(file);
        Dialect source = dialectOf(file.getFileName().toString());
        String key = corpus.displayName(file) + "|" + target;
        if (EXPECTED_REFUSALS.contains(key)) {
            assertThatThrownBy(() -> RuleEngine.standard()
                    .run(AstBuilderFacade.buildScript(sql, source), source, target))
                    .isInstanceOf(UnsupportedFeatureException.class);
            return;
        }
        assertThatCode(() -> RuleEngine.standard()
                .run(AstBuilderFacade.buildScript(sql, source), source, target))
                .doesNotThrowAnyException();

        Script first = RuleEngine.standard()
                .run(AstBuilderFacade.buildScript(sql, source), source, target).script();
        Script second = RuleEngine.standard()
                .run(AstBuilderFacade.buildScript(sql, source), source, target).script();
        AstDumper dumper = new AstDumper();
        assertThat(dumper.dump(second)).isEqualTo(dumper.dump(first));
    }

    private static Dialect dialectOf(String fileName) {
        String tag = fileName.substring("input.".length(), fileName.lastIndexOf('.'));
        return switch (tag) {
            case "tsql" -> Dialect.TSQL;
            case "mysql" -> Dialect.MYSQL;
            case "postgresql" -> Dialect.POSTGRESQL;
            default -> throw new IllegalArgumentException(fileName);
        };
    }
}
```

- [ ] **Step 3: Run the corpus test**

Run: `./mvnw --batch-mode -Dtest=RuleEngineCorpusTest test`
Expected: PASS (~400 dynamic tests: ~134 files × 3 targets). If any non-manifest case throws, the failure names the case and target — fix the responsible rule, not the test.

- [ ] **Step 4: Run the full suite**

Run: `./mvnw --batch-mode clean verify`
Expected: BUILD SUCCESS, all Phase 2/3 suites still green.

- [ ] **Step 5: Append the add-a-rule recipe to `EXTENDING.md`**

Append this section verbatim:

```markdown
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
```

- [ ] **Step 6: Commit**

```bash
git add src/test/java/rs/etf/sqltranslator EXTENDING.md
git commit -m "test: corpus-wide translation smoke and determinism for the rule engine"
```
