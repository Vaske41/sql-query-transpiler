package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import rs.etf.sqltranslator.ast.AddColumn;
import rs.etf.sqltranslator.ast.AlterAction;
import rs.etf.sqltranslator.ast.AlterTableStatement;
import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.BetweenPredicate;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.DeleteStatement;
import rs.etf.sqltranslator.ast.DropColumn;
import rs.etf.sqltranslator.ast.DropTableStatement;
import rs.etf.sqltranslator.ast.ExistsPredicate;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.ForeignKeyConstraint;
import rs.etf.sqltranslator.ast.ForeignKeyRef;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.InListPredicate;
import rs.etf.sqltranslator.ast.InSubqueryPredicate;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.LikePredicate;
import rs.etf.sqltranslator.ast.NullLiteral;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.PrimaryKeyConstraint;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.SelectStar;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.ast.SetQuantifier;
import rs.etf.sqltranslator.ast.SortDirection;
import rs.etf.sqltranslator.ast.Statement;
import rs.etf.sqltranslator.ast.SubqueryExpression;
import rs.etf.sqltranslator.ast.TableConstraint;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UnaryOp;
import rs.etf.sqltranslator.ast.UnaryOperator;
import rs.etf.sqltranslator.ast.UnionArm;
import rs.etf.sqltranslator.ast.UniqueConstraint;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.grammar.MySqlBaseVisitor;
import rs.etf.sqltranslator.grammar.MySqlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin MySQL parse-tree → AST builder (D4): mechanical extraction only; all logic
 * lives in {@link AstBuilderSupport}. Deliberately near-identical to the other two
 * builders.
 */
final class MySqlAstBuilder extends MySqlBaseVisitor<Object> {

    private final AstBuilderSupport support = new AstBuilderSupport(Dialect.MYSQL);

    // --- script and statements ---

    @Override
    public Object visitScript(MySqlParser.ScriptContext ctx) {
        List<Statement> statements = ctx.statement().stream()
                .map(s -> (Statement) visit(s)).toList();
        return new Script(statements, pos(ctx));
    }

    @Override
    public Object visitSelectStatement(MySqlParser.SelectStatementContext ctx) {
        return new SelectStatement((Query) visit(ctx.queryExpression()), pos(ctx));
    }

    // --- query shape ---

    @Override
    public Object visitQueryExpression(MySqlParser.QueryExpressionContext ctx) {
        QuerySpecification first = (QuerySpecification) visit(ctx.querySpecification(0));
        List<UnionArm> arms = unionArms(ctx);
        List<OrderItem> orderBy = ctx.orderByClause() == null
                ? List.of()
                : ctx.orderByClause().orderItem().stream()
                        .map(i -> (OrderItem) visit(i)).toList();
        return new Query(first, arms, orderBy, rowLimit(ctx.rowLimitClause()), pos(ctx));
    }

    /** MySQL: LIMIT n [OFFSET m] | LIMIT m, n — the comma form swaps operands (§2.2). */
    private Optional<RowLimit> rowLimit(MySqlParser.RowLimitClauseContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }
        Expression first = expr(ctx.expression(0));
        Expression second = ctx.expression().size() > 1 ? expr(ctx.expression(1)) : null;
        boolean commaForm = second != null && ctx.OFFSET() == null;
        return Optional.of(support.mysqlRowLimit(first, second, commaForm, pos(ctx)));
    }

    private List<UnionArm> unionArms(MySqlParser.QueryExpressionContext ctx) {
        List<UnionArm> arms = new ArrayList<>();
        boolean afterUnion = false;
        boolean all = false;
        for (ParseTree child : ctx.children) {
            if (child instanceof TerminalNode terminal) {
                if (terminal.getSymbol().getType() == MySqlParser.UNION) {
                    afterUnion = true;
                    all = false;
                } else if (afterUnion && terminal.getSymbol().getType() == MySqlParser.ALL) {
                    all = true;
                }
            } else if (afterUnion
                    && child instanceof MySqlParser.QuerySpecificationContext spec) {
                arms.add(new UnionArm(all, (QuerySpecification) visit(spec), pos(spec)));
                afterUnion = false;
            }
        }
        return arms;
    }

    @Override
    public Object visitQuerySpecification(MySqlParser.QuerySpecificationContext ctx) {
        List<SelectItem> items = ctx.selectItem().stream()
                .map(i -> (SelectItem) visit(i)).toList();
        Optional<TableSource> from = ctx.tableSource() == null
                ? Optional.empty() : Optional.of((TableSource) visit(ctx.tableSource()));
        Optional<Expression> where = ctx.whereClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.whereClause().expression()));
        List<Expression> groupBy = ctx.groupByClause() == null
                ? List.of()
                : ctx.groupByClause().expression().stream().map(this::expr).toList();
        Optional<Expression> having = ctx.havingClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.havingClause().expression()));
        return new QuerySpecification(quantifier(ctx.setQuantifier()), items, from, where,
                groupBy, having, pos(ctx));
    }

    private Optional<SetQuantifier> quantifier(MySqlParser.SetQuantifierContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }
        return Optional.of(ctx.DISTINCT() != null ? SetQuantifier.DISTINCT : SetQuantifier.ALL);
    }

    @Override
    public Object visitSelectStar(MySqlParser.SelectStarContext ctx) {
        return new SelectStar(Optional.empty(), pos(ctx));
    }

    @Override
    public Object visitSelectQualifiedStar(MySqlParser.SelectQualifiedStarContext ctx) {
        return new SelectStar(Optional.of(qname(ctx.qualifiedName())), pos(ctx));
    }

    @Override
    public Object visitSelectExpr(MySqlParser.SelectExprContext ctx) {
        Optional<Identifier> alias = ctx.identifier() == null
                ? Optional.empty() : Optional.of(ident(ctx.identifier()));
        return new SelectExpr(expr(ctx.expression()), alias, pos(ctx));
    }

    @Override
    public Object visitTableSource(MySqlParser.TableSourceContext ctx) {
        List<Join> joins = ctx.joinedTable().stream().map(j -> (Join) visit(j)).toList();
        return new TableSource((TableRef) visit(ctx.tablePrimary()), joins, pos(ctx));
    }

    @Override
    public Object visitTablePrimary(MySqlParser.TablePrimaryContext ctx) {
        Optional<Identifier> alias = ctx.identifier() == null
                ? Optional.empty() : Optional.of(ident(ctx.identifier()));
        return new TableRef(qname(ctx.qualifiedName()), alias, pos(ctx));
    }

    @Override
    public Object visitJoinedTable(MySqlParser.JoinedTableContext ctx) {
        TableRef table = (TableRef) visit(ctx.tablePrimary());
        if (ctx.CROSS() != null) {
            return new Join(JoinKind.CROSS, table, Optional.empty(), pos(ctx));
        }
        return new Join(joinKind(ctx.joinType()), table,
                Optional.of(expr(ctx.expression())), pos(ctx));
    }

    /** MySQL has no FULL [OUTER] JOIN. */
    private JoinKind joinKind(MySqlParser.JoinTypeContext ctx) {
        if (ctx.LEFT() != null) {
            return JoinKind.LEFT;
        }
        if (ctx.RIGHT() != null) {
            return JoinKind.RIGHT;
        }
        return JoinKind.INNER;
    }

    @Override
    public Object visitOrderItem(MySqlParser.OrderItemContext ctx) {
        SortDirection direction = ctx.DESC() != null ? SortDirection.DESC : SortDirection.ASC;
        return new OrderItem(expr(ctx.expression()), direction, Optional.empty(), pos(ctx));
    }

    @Override
    public Object visitSubquery(MySqlParser.SubqueryContext ctx) {
        return visit(ctx.queryExpression());
    }

    // --- DML ---

    @Override
    public Object visitInsertStatement(MySqlParser.InsertStatementContext ctx) {
        List<Identifier> columns = ctx.identifier().stream().map(this::ident).toList();
        List<List<Expression>> rows = ctx.rowValue().stream()
                .map(row -> row.expression().stream().map(this::expr).toList())
                .toList();
        return new InsertStatement(qname(ctx.qualifiedName()), columns, rows, pos(ctx));
    }

    @Override
    public Object visitUpdateStatement(MySqlParser.UpdateStatementContext ctx) {
        List<Assignment> assignments = ctx.assignment().stream()
                .map(a -> new Assignment(ident(a.identifier()), expr(a.expression()), pos(a)))
                .toList();
        Optional<Expression> where = ctx.whereClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.whereClause().expression()));
        return new UpdateStatement(qname(ctx.qualifiedName()), assignments, where, pos(ctx));
    }

    @Override
    public Object visitDeleteStatement(MySqlParser.DeleteStatementContext ctx) {
        Optional<Expression> where = ctx.whereClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.whereClause().expression()));
        return new DeleteStatement(qname(ctx.qualifiedName()), where, pos(ctx));
    }

    // --- DDL ---

    @Override
    public Object visitCreateTableStatement(MySqlParser.CreateTableStatementContext ctx) {
        List<ColumnDefinition> columns = new ArrayList<>();
        List<TableConstraint> constraints = new ArrayList<>();
        for (MySqlParser.TableElementContext element : ctx.tableElement()) {
            if (element.columnDefinition() != null) {
                columns.add((ColumnDefinition) visit(element.columnDefinition()));
            } else {
                constraints.add((TableConstraint) visit(element.tableConstraint()));
            }
        }
        return new CreateTableStatement(qname(ctx.qualifiedName()), columns, constraints,
                pos(ctx));
    }

    @Override
    public Object visitColumnDefinition(MySqlParser.ColumnDefinitionContext ctx) {
        AstBuilderSupport.FoldedType type = columnType(ctx.dataType());
        AstBuilderSupport.ColumnAttributes attributes = new AstBuilderSupport.ColumnAttributes();
        for (MySqlParser.ColumnConstraintContext constraint : ctx.columnConstraint()) {
            if (constraint.NOT() != null) {
                attributes.notNull();
            } else if (constraint.DEFAULT() != null) {
                attributes.defaultValue(expr(constraint.expression()));
            } else if (constraint.NULL() != null) {
                attributes.nullAllowed();
            } else if (constraint.PRIMARY() != null) {
                attributes.primaryKey();
            } else if (constraint.UNIQUE() != null) {
                attributes.unique();
            } else if (constraint.REFERENCES() != null) {
                Optional<Identifier> column = constraint.identifier() == null
                        ? Optional.empty() : Optional.of(ident(constraint.identifier()));
                attributes.references(new ForeignKeyRef(qname(constraint.qualifiedName()),
                        column, pos(constraint)));
            } else if (constraint.autoIncrement() != null) {
                attributes.autoIncrement();
            }
        }
        return support.columnDefinition(ident(ctx.identifier()), type, attributes, pos(ctx));
    }

    @Override
    public Object visitTableConstraint(MySqlParser.TableConstraintContext ctx) {
        Optional<Identifier> name = ctx.identifier() == null
                ? Optional.empty() : Optional.of(ident(ctx.identifier()));
        if (ctx.PRIMARY() != null) {
            return new PrimaryKeyConstraint(name, columns(ctx.columnList(0)), pos(ctx));
        }
        if (ctx.UNIQUE() != null) {
            return new UniqueConstraint(name, columns(ctx.columnList(0)), pos(ctx));
        }
        List<Identifier> refColumns = ctx.columnList().size() > 1
                ? columns(ctx.columnList(1)) : List.of();
        return new ForeignKeyConstraint(name, columns(ctx.columnList(0)),
                qname(ctx.qualifiedName()), refColumns, pos(ctx));
    }

    private List<Identifier> columns(MySqlParser.ColumnListContext ctx) {
        return ctx.identifier().stream().map(this::ident).toList();
    }

    @Override
    public Object visitDropTableStatement(MySqlParser.DropTableStatementContext ctx) {
        return new DropTableStatement(qname(ctx.qualifiedName()), ctx.IF() != null, pos(ctx));
    }

    @Override
    public Object visitAlterTableStatement(MySqlParser.AlterTableStatementContext ctx) {
        AlterAction action = ctx.ADD() != null
                ? new AddColumn((ColumnDefinition) visit(ctx.columnDefinition()), pos(ctx))
                : new DropColumn(ident(ctx.identifier()), pos(ctx));
        return new AlterTableStatement(qname(ctx.qualifiedName()), action, pos(ctx));
    }

    // --- expression ladder ---

    /** MySQL {@code ||} lexes as PIPES and folds to logical OR here (§2.2). */
    @Override
    public Object visitOrExpression(MySqlParser.OrExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitAndExpression(MySqlParser.AndExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitNotExpression(MySqlParser.NotExpressionContext ctx) {
        if (ctx.NOT() != null) {
            return new UnaryOp(UnaryOperator.NOT, expr(ctx.notExpression()), pos(ctx));
        }
        return visit(ctx.predicate());
    }

    @Override
    public Object visitConcatExpression(MySqlParser.ConcatExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitAdditiveExpression(MySqlParser.AdditiveExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitMultiplicativeExpression(MySqlParser.MultiplicativeExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitUnaryExpression(MySqlParser.UnaryExpressionContext ctx) {
        if (ctx.unaryExpression() != null) {
            UnaryOperator op = ctx.getChild(0).getText().equals("-")
                    ? UnaryOperator.NEG : UnaryOperator.POS;
            return new UnaryOp(op, expr(ctx.unaryExpression()), pos(ctx));
        }
        return visit(ctx.primaryExpression());
    }

    // --- predicates ---

    @Override
    public Object visitComparisonPredicate(MySqlParser.ComparisonPredicateContext ctx) {
        return new BinaryOp(support.comparisonOperator(ctx.comparisonOperator().getText()),
                expr(ctx.concatExpression(0)), expr(ctx.concatExpression(1)), pos(ctx));
    }

    @Override
    public Object visitBetweenPredicate(MySqlParser.BetweenPredicateContext ctx) {
        return new BetweenPredicate(expr(ctx.concatExpression(0)),
                expr(ctx.concatExpression(1)), expr(ctx.concatExpression(2)),
                ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitLikePredicate(MySqlParser.LikePredicateContext ctx) {
        return new LikePredicate(expr(ctx.concatExpression(0)),
                expr(ctx.concatExpression(1)), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitInListPredicate(MySqlParser.InListPredicateContext ctx) {
        List<Expression> items = ctx.expression().stream().map(this::expr).toList();
        return new InListPredicate(expr(ctx.concatExpression()), items,
                ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitInSubqueryPredicate(MySqlParser.InSubqueryPredicateContext ctx) {
        return new InSubqueryPredicate(expr(ctx.concatExpression()),
                (Query) visit(ctx.subquery()), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitIsNullPredicate(MySqlParser.IsNullPredicateContext ctx) {
        return new IsNullPredicate(expr(ctx.concatExpression()), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitExistsPredicate(MySqlParser.ExistsPredicateContext ctx) {
        return new ExistsPredicate((Query) visit(ctx.subquery()), pos(ctx));
    }

    @Override
    public Object visitSimplePredicate(MySqlParser.SimplePredicateContext ctx) {
        return visit(ctx.concatExpression());
    }

    // --- primary expressions ---

    @Override
    public Object visitLiteralExpr(MySqlParser.LiteralExprContext ctx) {
        return visit(ctx.literal());
    }

    @Override
    public Object visitLiteral(MySqlParser.LiteralContext ctx) {
        if (ctx.INTEGER_LITERAL() != null) {
            return support.numeric(ctx.INTEGER_LITERAL().getSymbol(), false);
        }
        if (ctx.DECIMAL_LITERAL() != null) {
            return support.numeric(ctx.DECIMAL_LITERAL().getSymbol(), true);
        }
        if (ctx.HEX_LITERAL() != null) {
            support.refuse("hex literal " + ctx.HEX_LITERAL().getText(), pos(ctx));
        }
        if (ctx.STRING_LITERAL() != null) {
            return support.stringLiteral(ctx.STRING_LITERAL().getSymbol());
        }
        if (ctx.NULL() != null) {
            return new NullLiteral(pos(ctx));
        }
        return new BooleanLiteral(ctx.TRUE() != null, pos(ctx));
    }

    @Override
    public Object visitCaseExpr(MySqlParser.CaseExprContext ctx) {
        return visit(ctx.caseExpression());
    }

    @Override
    public Object visitCaseExpression(MySqlParser.CaseExpressionContext ctx) {
        List<Expression> expressions = ctx.expression().stream().map(this::expr).toList();
        List<SourcePosition> whenPositions = ctx.WHEN().stream()
                .map(w -> AstBuilderSupport.pos(w.getSymbol())).toList();
        return support.caseExpression(expressions, whenPositions, ctx.ELSE() != null,
                pos(ctx));
    }

    @Override
    public Object visitCastExpr(MySqlParser.CastExprContext ctx) {
        return visit(ctx.castExpression());
    }

    @Override
    public Object visitCastExpression(MySqlParser.CastExpressionContext ctx) {
        return new CastExpression(expr(ctx.expression()), castType(ctx.dataType()), pos(ctx));
    }

    @Override
    public Object visitFunctionExpr(MySqlParser.FunctionExprContext ctx) {
        return visit(ctx.functionCall());
    }

    @Override
    public Object visitFunctionCall(MySqlParser.FunctionCallContext ctx) {
        String name = support.functionName(functionNameIdentifier(ctx.functionName()));
        boolean star = false;
        for (ParseTree child : ctx.children) {
            if (child instanceof TerminalNode terminal && terminal.getText().equals("*")) {
                star = true;
            }
        }
        List<Expression> args = ctx.expression().stream().map(this::expr).toList();
        return new FunctionCall(name, args, star, quantifier(ctx.setQuantifier()), pos(ctx));
    }

    private Identifier functionNameIdentifier(MySqlParser.FunctionNameContext ctx) {
        return ctx.identifier() != null
                ? ident(ctx.identifier())
                : new Identifier(ctx.getText(), false, AstBuilderSupport.pos(ctx));
    }

    @Override
    public Object visitColumnRefExpr(MySqlParser.ColumnRefExprContext ctx) {
        return new ColumnRef(qname(ctx.qualifiedName()), pos(ctx));
    }

    @Override
    public Object visitScalarSubqueryExpr(MySqlParser.ScalarSubqueryExprContext ctx) {
        return new SubqueryExpression((Query) visit(ctx.subquery()), pos(ctx));
    }

    @Override
    public Object visitParenExpr(MySqlParser.ParenExprContext ctx) {
        return visit(ctx.expression());
    }

    // --- shared extraction helpers ---

    private Expression expr(ParseTree tree) {
        return (Expression) visit(tree);
    }

    private Identifier ident(MySqlParser.IdentifierContext ctx) {
        return support.identifier(ctx.getStart());
    }

    private QualifiedName qname(MySqlParser.QualifiedNameContext ctx) {
        return support.qualifiedName(ctx.identifier().stream().map(this::ident).toList(),
                pos(ctx));
    }

    private AstBuilderSupport.FoldedType columnType(MySqlParser.DataTypeContext ctx) {
        return support.foldColumnType(typeWord(ctx, 0), secondTypeWord(ctx), argTexts(ctx),
                pos(ctx));
    }

    private DataType castType(MySqlParser.DataTypeContext ctx) {
        return support.foldCastType(typeWord(ctx, 0), secondTypeWord(ctx), argTexts(ctx),
                pos(ctx));
    }

    private String typeWord(MySqlParser.DataTypeContext ctx, int index) {
        return support.identifier(ctx.identifier(index).getStart()).value();
    }

    private String secondTypeWord(MySqlParser.DataTypeContext ctx) {
        return ctx.identifier().size() > 1 ? typeWord(ctx, 1) : null;
    }

    private List<String> argTexts(MySqlParser.DataTypeContext ctx) {
        return ctx.dataTypeArg().stream().map(ParserRuleContext::getText).toList();
    }

    private static SourcePosition pos(ParserRuleContext ctx) {
        return AstBuilderSupport.pos(ctx);
    }
}
