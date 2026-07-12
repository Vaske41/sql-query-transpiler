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
 * "what column is this?" for type-dependent rewrites. Unresolvable references
 * return empty; callers degrade to warnings, never guesses.
 *
 * <p><b>v1 scope contract:</b> resolution uses the <em>top frame only</em> — no
 * outer-scope correlation through expression-position subqueries. Correlated
 * references to enclosing FROM columns systematically return empty.
 *
 * <p>Subclasses override the {@code afterX} hooks (called with scope still pushed)
 * instead of the corresponding {@code visitX} methods, which are final here.
 */public abstract class ScopedTransformer extends rs.etf.sqltranslator.ast.AstTransformer {

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

    /**
     * Best-effort type family: literals, catalog-resolved columns, CAST targets,
     * and a few operator-result heuristics. Unsupported shapes ({@code FunctionCall},
     * {@code CaseExpression}, {@code COALESCE}, scalar subqueries, …) return empty —
     * callers must degrade (warn / no-op), never invent a type.
     */
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
