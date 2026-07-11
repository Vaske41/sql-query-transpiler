package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;
import org.antlr.v4.runtime.tree.TerminalNode;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.CaseExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FixedLength;
import rs.etf.sqltranslator.ast.ForeignKeyRef;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.TypeLength;
import rs.etf.sqltranslator.ast.WhenClause;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The shared core behind the three thin dialect builders (D4): node-construction
 * helpers, {@code RowLimit} unification, per-dialect type-folding tables (D3),
 * identifier/string unescaping, function-name uppercasing, and every refusal.
 * The builders do mechanical extraction only; each normalization and bug-fix
 * lands once, here, where it is unit-testable in isolation (D11).
 */
final class AstBuilderSupport {

    /** Length/scale arguments are carried only into these generics (§2.3). */
    private static final Set<GenericType> PARAMETERIZABLE = Set.of(
            GenericType.CHAR, GenericType.VARCHAR, GenericType.NVARCHAR,
            GenericType.DECIMAL, GenericType.TIME, GenericType.TIMESTAMP);

    private final Dialect dialect;
    private final Map<String, Fold> typeTable;

    AstBuilderSupport(Dialect dialect) {
        this.dialect = dialect;
        this.typeTable = typeTable(dialect);
    }

    // ------------------------------------------------------------------
    // Positions and refusals
    // ------------------------------------------------------------------

    static SourcePosition pos(ParserRuleContext ctx) {
        return pos(ctx.getStart());
    }

    static SourcePosition pos(Token token) {
        return new SourcePosition(token.getLine(), token.getCharPositionInLine());
    }

    void refuse(String construct, SourcePosition position) {
        throw new UnsupportedFeatureException(construct, position);
    }

    void refuseIf(boolean condition, String construct, SourcePosition position) {
        if (condition) {
            refuse(construct, position);
        }
    }

    // ------------------------------------------------------------------
    // Identifiers, names, function names
    // ------------------------------------------------------------------

    /** Unquotes/unescapes one identifier token per this dialect's rules (D6). */
    Identifier identifier(Token token) {
        String text = token.getText();
        SourcePosition p = pos(token);
        char first = text.charAt(0);
        return switch (dialect) {
            case TSQL -> switch (first) {
                case '[' -> new Identifier(undouble(body(text, 1), ']'), true, p);
                case '"' -> new Identifier(undouble(body(text, 1), '"'), true, p);
                default -> new Identifier(text, false, p);
            };
            case MYSQL -> first == '`'
                    ? new Identifier(undouble(body(text, 1), '`'), true, p)
                    : new Identifier(text, false, p);
            case POSTGRESQL -> first == '"'
                    ? new Identifier(undouble(body(text, 1), '"'), true, p)
                    : new Identifier(text, false, p);
        };
    }

    /** Builds a qualified name, refusing more than three parts. */
    QualifiedName qualifiedName(List<Identifier> parts, SourcePosition position) {
        refuseIf(parts.size() > 3, "qualified name with " + parts.size() + " parts", position);
        return new QualifiedName(parts, position);
    }

    /**
     * Canonical function name: uppercased (case-insensitive in all three dialects);
     * a quoted name is refused — uppercasing would corrupt a case-sensitive name.
     */
    String functionName(Identifier name) {
        refuseIf(name.quoted(), "quoted function name \"" + name.value() + "\"", name.pos());
        return name.value().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Literals
    // ------------------------------------------------------------------

    NumericLiteral numeric(Token token, boolean decimal) {
        return new NumericLiteral(token.getText(), decimal, pos(token));
    }

    /** Unescapes one string-literal token per this dialect's rules. */
    StringLiteral stringLiteral(Token token) {
        String text = token.getText();
        SourcePosition p = pos(token);
        return switch (dialect) {
            case TSQL -> {
                boolean national = text.charAt(0) == 'N' || text.charAt(0) == 'n';
                yield new StringLiteral(undouble(body(text, national ? 2 : 1), '\''),
                        national, p);
            }
            case MYSQL -> new StringLiteral(
                    unescapeBackslash(body(text, 1), text.charAt(0), true), false, p);
            case POSTGRESQL -> {
                boolean escaped = text.charAt(0) == 'E' || text.charAt(0) == 'e';
                yield new StringLiteral(escaped
                                ? unescapeBackslash(body(text, 2), '\'', false)
                                : undouble(body(text, 1), '\''),
                        false, p);
            }
        };
    }

    /** Strips {@code skip} leading chars plus the single closing delimiter. */
    private static String body(String text, int skip) {
        return text.substring(skip, text.length() - 1);
    }

    /** Undoes delimiter doubling: {@code ]] → ]}, {@code '' → '}, {@code `` → `}, ... */
    private static String undouble(String body, char delimiter) {
        String twice = String.valueOf(delimiter) + delimiter;
        return body.replace(twice, String.valueOf(delimiter));
    }

    /**
     * Backslash unescaping shared by MySQL strings and PostgreSQL {@code E'...'}
     * strings; delimiter doubling is handled in the same pass. When
     * {@code mySqlRules} is set, {@code \%} and {@code \_} keep their backslash
     * (MySQL treats them as LIKE-pattern escapes, not string escapes).
     */
    private static String unescapeBackslash(String body, char delimiter, boolean mySqlRules) {
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                switch (next) {
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '0' -> sb.append(mySqlRules ? '\0' : '0');
                    case 'Z' -> sb.append(mySqlRules ? (char) 0x1A : 'Z');
                    case '%', '_' -> {
                        if (mySqlRules) {
                            sb.append('\\');
                        }
                        sb.append(next);
                    }
                    default -> sb.append(next);   // covers \\ \' \" and everything else
                }
            } else if (c == delimiter && i + 1 < body.length()
                    && body.charAt(i + 1) == delimiter) {
                sb.append(delimiter);
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Expression folding
    // ------------------------------------------------------------------

    /**
     * Left-folds a homogeneous binary-operator chain rule
     * ({@code operand (OP operand)*}) into nested {@link BinaryOp}s. Operators are
     * mapped by token text, so one helper serves every chain rule in all three
     * builders; {@code ||} resolves per dialect (MySQL logical OR, PostgreSQL CONCAT).
     */
    Expression foldBinaryChain(ParserRuleContext ctx, ParseTreeVisitor<Object> builder) {
        Expression result = null;
        BinaryOperator pending = null;
        for (ParseTree child : ctx.children) {
            if (child instanceof TerminalNode terminal) {
                pending = binaryOperator(terminal.getText());
            } else {
                Expression operand = (Expression) child.accept(builder);
                result = result == null
                        ? operand
                        : new BinaryOp(pending, result, operand, pos(ctx));
            }
        }
        return result;
    }

    private BinaryOperator binaryOperator(String text) {
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "OR" -> BinaryOperator.OR;
            case "AND" -> BinaryOperator.AND;
            case "+" -> BinaryOperator.ADD;
            case "-" -> BinaryOperator.SUB;
            case "*" -> BinaryOperator.MUL;
            case "/" -> BinaryOperator.DIV;
            case "%" -> BinaryOperator.MOD;
            // MySQL under default sql_mode: logical OR; PostgreSQL: string concat.
            case "||" -> dialect == Dialect.MYSQL ? BinaryOperator.OR : BinaryOperator.CONCAT;
            default -> throw new IllegalStateException("Unmapped binary operator: " + text);
        };
    }

    BinaryOperator comparisonOperator(String text) {
        return switch (text) {
            case "=" -> BinaryOperator.EQ;
            case "<>", "!=" -> BinaryOperator.NEQ;
            case "<" -> BinaryOperator.LT;
            case "<=" -> BinaryOperator.LTE;
            case ">" -> BinaryOperator.GT;
            case ">=" -> BinaryOperator.GTE;
            default -> throw new IllegalStateException("Unmapped comparison operator: " + text);
        };
    }

    /**
     * Slices the flat expression list of a CASE rule ({@code CASE expression?
     * (WHEN expression THEN expression)+ (ELSE expression)? END}) into its parts.
     */
    CaseExpression caseExpression(List<Expression> expressions,
                                  List<SourcePosition> whenPositions, boolean hasElse,
                                  SourcePosition position) {
        int whenCount = whenPositions.size();
        int withoutOperand = 2 * whenCount + (hasElse ? 1 : 0);
        boolean hasOperand = expressions.size() == withoutOperand + 1;
        int index = hasOperand ? 1 : 0;
        Optional<Expression> operand =
                hasOperand ? Optional.of(expressions.get(0)) : Optional.empty();
        List<WhenClause> whens = new ArrayList<>(whenCount);
        for (int i = 0; i < whenCount; i++) {
            whens.add(new WhenClause(expressions.get(index), expressions.get(index + 1),
                    whenPositions.get(i)));
            index += 2;
        }
        Optional<Expression> elseValue =
                hasElse ? Optional.of(expressions.get(index)) : Optional.empty();
        return new CaseExpression(operand, whens, elseValue, position);
    }

    // ------------------------------------------------------------------
    // RowLimit unification (§2.2)
    // ------------------------------------------------------------------

    /**
     * T-SQL: {@code TOP n} or {@code ORDER BY ... OFFSET m ROWS [FETCH ... n ...]}.
     * Their combination is refused — SQL Server itself rejects it, and merging
     * would invent semantics. ({@code TOP} inside UNION is refused by the builder
     * before spec extraction, where the arm is still identifiable.)
     */
    Optional<RowLimit> tsqlRowLimit(Expression top, SourcePosition topPosition,
                                    Expression offset, Expression fetch,
                                    SourcePosition offsetPosition) {
        refuseIf(top != null && offset != null, "TOP combined with OFFSET/FETCH", topPosition);
        if (top != null) {
            return Optional.of(new RowLimit(Optional.of(top), Optional.empty(), topPosition));
        }
        if (offset != null) {
            return Optional.of(new RowLimit(Optional.ofNullable(fetch), Optional.of(offset),
                    offsetPosition));
        }
        return Optional.empty();
    }

    /** MySQL: {@code LIMIT n [OFFSET m]} or {@code LIMIT m, n} (operand swap). */
    RowLimit mysqlRowLimit(Expression first, Expression second, boolean commaForm,
                           SourcePosition position) {
        if (commaForm) {
            return new RowLimit(Optional.of(second), Optional.of(first), position);
        }
        return new RowLimit(Optional.of(first), Optional.ofNullable(second), position);
    }

    /** PostgreSQL: LIMIT and OFFSET in either order. */
    RowLimit pgRowLimit(Expression first, Expression second, boolean limitFirst,
                        SourcePosition position) {
        if (limitFirst) {
            return new RowLimit(Optional.of(first), Optional.ofNullable(second), position);
        }
        return new RowLimit(Optional.ofNullable(second), Optional.of(first), position);
    }

    // ------------------------------------------------------------------
    // Column definitions and auto-increment
    // ------------------------------------------------------------------

    /** T-SQL {@code IDENTITY(seed, increment)}: only {@code (1,1)} is supported. */
    void checkIdentitySeed(String seed, String increment, SourcePosition position) {
        refuseIf(!"1".equals(seed) || !"1".equals(increment),
                "IDENTITY(" + seed + "," + increment + ")", position);
    }

    /**
     * Assembles a {@link ColumnDefinition}, merging the auto-increment contributed
     * by the type fold (PG SERIAL family) with column-constraint syntax
     * (IDENTITY / AUTO_INCREMENT / GENERATED ... AS IDENTITY).
     */
    ColumnDefinition columnDefinition(Identifier name, FoldedType type,
                                      ColumnAttributes attributes, SourcePosition position) {
        return new ColumnDefinition(name, type.dataType(),
                type.autoIncrement() || attributes.autoIncrement,
                attributes.nullable, Optional.ofNullable(attributes.defaultValue),
                attributes.primaryKey, attributes.unique,
                Optional.ofNullable(attributes.references), position);
    }

    /** Mutable accumulator the builders fill while walking {@code columnConstraint*}. */
    static final class ColumnAttributes {

        private Optional<Boolean> nullable = Optional.empty();
        private Expression defaultValue;
        private boolean primaryKey;
        private boolean unique;
        private boolean autoIncrement;
        private ForeignKeyRef references;

        void notNull() {
            nullable = Optional.of(false);
        }

        void nullAllowed() {
            nullable = Optional.of(true);
        }

        void defaultValue(Expression value) {
            defaultValue = value;
        }

        void primaryKey() {
            primaryKey = true;
        }

        void unique() {
            unique = true;
        }

        void autoIncrement() {
            autoIncrement = true;
        }

        void references(ForeignKeyRef ref) {
            references = ref;
        }
    }

    // ------------------------------------------------------------------
    // Type folding (D3, §2.3)
    // ------------------------------------------------------------------

    /** A folded type plus the auto-increment it implies (PG SERIAL family only). */
    record FoldedType(DataType dataType, boolean autoIncrement) {
    }

    /** Folds a column's declared type; SERIAL folds carry auto-increment. */
    FoldedType foldColumnType(String word1, String word2, List<String> args,
                              SourcePosition position) {
        return fold(word1, word2, args, position);
    }

    /** Folds a CAST/CONVERT target; auto-increment folds (SERIAL) are meaningless here. */
    DataType foldCastType(String word1, String word2, List<String> args,
                          SourcePosition position) {
        FoldedType folded = fold(word1, word2, args, position);
        refuseIf(folded.autoIncrement(), "type " + word1.toUpperCase(Locale.ROOT) + " in CAST",
                position);
        return folded.dataType();
    }

    private FoldedType fold(String word1, String word2, List<String> args,
                            SourcePosition position) {
        String name = word1.toUpperCase(Locale.ROOT);
        if (word2 != null) {
            name = name + " " + word2.toUpperCase(Locale.ROOT);
            // The builder-side whitelist for the two-word grammar form (§3 fix 2).
            refuseIf(!name.equals("DOUBLE PRECISION"), "type " + word1 + " " + word2, position);
        }
        // MAX is part of the lookup key itself and is consumed by the fold (§2.3).
        if (dialect == Dialect.TSQL && name.equals("VARBINARY")
                && args.size() == 1 && args.get(0).equalsIgnoreCase("MAX")) {
            return new FoldedType(
                    new DataType(GenericType.BLOB, Optional.empty(), Optional.empty()), false);
        }
        Fold entry = typeTable.get(name);
        if (entry == null) {
            refuse("type " + name, position);
        }
        GenericType generic = entry.type();
        Optional<TypeLength> length = Optional.empty();
        Optional<Integer> scale = Optional.empty();
        if (!args.isEmpty()) {
            refuseIf(!PARAMETERIZABLE.contains(generic),
                    "length argument on type " + name, position);
            String first = args.get(0);
            if (first.equalsIgnoreCase("MAX")) {
                refuseIf(generic != GenericType.VARCHAR && generic != GenericType.NVARCHAR,
                        "MAX length on type " + name, position);
                length = Optional.of(new MaxLength());
            } else {
                length = Optional.of(new FixedLength(Integer.parseInt(first)));
            }
            if (args.size() > 1) {
                String second = args.get(1);
                refuseIf(generic != GenericType.DECIMAL || second.equalsIgnoreCase("MAX"),
                        "scale argument on type " + name, position);
                scale = Optional.of(Integer.parseInt(second));
            }
        }
        return new FoldedType(new DataType(generic, length, scale), entry.autoIncrement());
    }

    private record Fold(GenericType type, boolean autoIncrement) {

        static Fold of(GenericType type) {
            return new Fold(type, false);
        }

        static Fold auto(GenericType type) {
            return new Fold(type, true);
        }
    }

    private static Map<String, Fold> typeTable(Dialect dialect) {
        return switch (dialect) {
            case TSQL -> Map.ofEntries(
                    Map.entry("INT", Fold.of(GenericType.INTEGER)),
                    Map.entry("INTEGER", Fold.of(GenericType.INTEGER)),
                    Map.entry("BIGINT", Fold.of(GenericType.BIGINT)),
                    Map.entry("SMALLINT", Fold.of(GenericType.SMALLINT)),
                    Map.entry("TINYINT", Fold.of(GenericType.TINYINT)),
                    Map.entry("BIT", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("DECIMAL", Fold.of(GenericType.DECIMAL)),
                    Map.entry("NUMERIC", Fold.of(GenericType.DECIMAL)),
                    Map.entry("FLOAT", Fold.of(GenericType.DOUBLE)),
                    Map.entry("REAL", Fold.of(GenericType.FLOAT)),
                    Map.entry("DOUBLE PRECISION", Fold.of(GenericType.DOUBLE)),
                    Map.entry("NVARCHAR", Fold.of(GenericType.NVARCHAR)),
                    Map.entry("VARCHAR", Fold.of(GenericType.VARCHAR)),
                    // Documented v1 simplification: fixed-width national char folds
                    // to variable-width generic.
                    Map.entry("NCHAR", Fold.of(GenericType.NVARCHAR)),
                    Map.entry("CHAR", Fold.of(GenericType.CHAR)),
                    Map.entry("DATETIME2", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("DATETIME", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("DATE", Fold.of(GenericType.DATE)),
                    Map.entry("TIME", Fold.of(GenericType.TIME)),
                    Map.entry("IMAGE", Fold.of(GenericType.BLOB)));
            case MYSQL -> Map.ofEntries(
                    Map.entry("INT", Fold.of(GenericType.INTEGER)),
                    Map.entry("INTEGER", Fold.of(GenericType.INTEGER)),
                    Map.entry("TINYINT", Fold.of(GenericType.TINYINT)),
                    Map.entry("SMALLINT", Fold.of(GenericType.SMALLINT)),
                    Map.entry("BIGINT", Fold.of(GenericType.BIGINT)),
                    Map.entry("DECIMAL", Fold.of(GenericType.DECIMAL)),
                    Map.entry("NUMERIC", Fold.of(GenericType.DECIMAL)),
                    Map.entry("FLOAT", Fold.of(GenericType.FLOAT)),
                    Map.entry("DOUBLE", Fold.of(GenericType.DOUBLE)),
                    Map.entry("DOUBLE PRECISION", Fold.of(GenericType.DOUBLE)),
                    Map.entry("VARCHAR", Fold.of(GenericType.VARCHAR)),
                    Map.entry("CHAR", Fold.of(GenericType.CHAR)),
                    Map.entry("TEXT", Fold.of(GenericType.TEXT)),
                    Map.entry("BOOLEAN", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("BOOL", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("DATETIME", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("TIMESTAMP", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("DATE", Fold.of(GenericType.DATE)),
                    Map.entry("TIME", Fold.of(GenericType.TIME)),
                    Map.entry("BLOB", Fold.of(GenericType.BLOB)));
            case POSTGRESQL -> Map.ofEntries(
                    Map.entry("INTEGER", Fold.of(GenericType.INTEGER)),
                    Map.entry("INT", Fold.of(GenericType.INTEGER)),
                    Map.entry("INT4", Fold.of(GenericType.INTEGER)),
                    Map.entry("SMALLINT", Fold.of(GenericType.SMALLINT)),
                    Map.entry("INT2", Fold.of(GenericType.SMALLINT)),
                    Map.entry("BIGINT", Fold.of(GenericType.BIGINT)),
                    Map.entry("INT8", Fold.of(GenericType.BIGINT)),
                    Map.entry("DECIMAL", Fold.of(GenericType.DECIMAL)),
                    Map.entry("NUMERIC", Fold.of(GenericType.DECIMAL)),
                    Map.entry("REAL", Fold.of(GenericType.FLOAT)),
                    Map.entry("DOUBLE PRECISION", Fold.of(GenericType.DOUBLE)),
                    Map.entry("FLOAT8", Fold.of(GenericType.DOUBLE)),
                    Map.entry("VARCHAR", Fold.of(GenericType.VARCHAR)),
                    Map.entry("CHAR", Fold.of(GenericType.CHAR)),
                    Map.entry("TEXT", Fold.of(GenericType.TEXT)),
                    Map.entry("BOOLEAN", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("BOOL", Fold.of(GenericType.BOOLEAN)),
                    Map.entry("TIMESTAMP", Fold.of(GenericType.TIMESTAMP)),
                    Map.entry("DATE", Fold.of(GenericType.DATE)),
                    Map.entry("TIME", Fold.of(GenericType.TIME)),
                    Map.entry("BYTEA", Fold.of(GenericType.BLOB)),
                    Map.entry("SERIAL", Fold.auto(GenericType.INTEGER)),
                    Map.entry("BIGSERIAL", Fold.auto(GenericType.BIGINT)),
                    Map.entry("SMALLSERIAL", Fold.auto(GenericType.SMALLINT)));
        };
    }
}
