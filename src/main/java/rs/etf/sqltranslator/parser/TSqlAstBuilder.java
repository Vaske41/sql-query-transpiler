package rs.etf.sqltranslator.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import rs.etf.sqltranslator.ast.AlterAction;
import rs.etf.sqltranslator.ast.AddColumn;
import rs.etf.sqltranslator.ast.AlterTableStatement;
import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.BetweenPredicate;
import rs.etf.sqltranslator.ast.BinaryOp;
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
import rs.etf.sqltranslator.grammar.TSqlBaseVisitor;
import rs.etf.sqltranslator.grammar.TSqlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin T-SQL parse-tree → AST builder (D4): method bodies extract children, tokens
 * and positions and delegate every decision to {@link AstBuilderSupport}. The bodies
 * are deliberately near-identical to the other two builders — the compiler verifies
 * each against its own grammar.
 */
final class TSqlAstBuilder extends TSqlBaseVisitor<Object> {

    private final AstBuilderSupport support = new AstBuilderSupport(Dialect.TSQL);

    // --- script and statements ---

    @Override
    public Object visitScript(TSqlParser.ScriptContext ctx) {
        List<Statement> statements = ctx.statement().stream()
                .map(s -> (Statement) visit(s)).toList();
        return new Script(statements, pos(ctx));
    }

    @Override
    public Object visitSelectStatement(TSqlParser.SelectStatementContext ctx) {
        return new SelectStatement((Query) visit(ctx.queryExpression()), pos(ctx));
    }

    // --- query shape ---

    @Override
    public Object visitQueryExpression(TSqlParser.QueryExpressionContext ctx) {
        List<TSqlParser.QuerySpecificationContext> specs = ctx.querySpecification();
        boolean hasArms = specs.size() > 1;
        List<AstBuilderSupport.ExtractedTop> tops = specs.stream()
                .map(spec -> {
                    TSqlParser.TopClauseContext topClause = spec.topClause();
                    if (topClause == null) {
                        return null;
                    }
                    return new AstBuilderSupport.ExtractedTop(topExpression(topClause),
                            pos(topClause));
                })
                .toList();
        AstBuilderSupport.ExtractedTop top = support.extractTsqlTop(tops, hasArms);
        QuerySpecification first = (QuerySpecification) visit(specs.get(0));
        List<UnionArm> arms = support.unionArms(ctx, TSqlParser.UNION, TSqlParser.ALL, this);
        List<OrderItem> orderBy = new ArrayList<>();
        Expression offset = null;
        Expression fetch = null;
        SourcePosition offsetPos = null;
        TSqlParser.OrderByClauseContext orderByClause = ctx.orderByClause();
        if (orderByClause != null) {
            for (TSqlParser.OrderItemContext item : orderByClause.orderItem()) {
                orderBy.add((OrderItem) visit(item));
            }
            if (orderByClause.OFFSET() != null) {
                offsetPos = pos(orderByClause.OFFSET().getSymbol());
                offset = expr(orderByClause.expression(0));
                if (orderByClause.FETCH() != null) {
                    fetch = expr(orderByClause.expression(1));
                }
            }
        }
        Optional<RowLimit> limit = support.tsqlRowLimit(
                top == null ? null : top.count(),
                top == null ? null : top.position(),
                offset, fetch, offsetPos);
        return new Query(first, arms, orderBy, limit, pos(ctx));
    }

    private Expression topExpression(TSqlParser.TopClauseContext ctx) {
        return ctx.INTEGER_LITERAL() != null
                ? support.numeric(ctx.INTEGER_LITERAL().getSymbol(), false)
                : expr(ctx.expression());
    }

    @Override
    public Object visitQuerySpecification(TSqlParser.QuerySpecificationContext ctx) {
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

    private Optional<SetQuantifier> quantifier(TSqlParser.SetQuantifierContext ctx) {
        if (ctx == null) {
            return Optional.empty();
        }
        return Optional.of(ctx.DISTINCT() != null ? SetQuantifier.DISTINCT : SetQuantifier.ALL);
    }

    @Override
    public Object visitSelectStar(TSqlParser.SelectStarContext ctx) {
        return new SelectStar(Optional.empty(), pos(ctx));
    }

    @Override
    public Object visitSelectQualifiedStar(TSqlParser.SelectQualifiedStarContext ctx) {
        return new SelectStar(Optional.of(qname(ctx.qualifiedName())), pos(ctx));
    }

    @Override
    public Object visitSelectExpr(TSqlParser.SelectExprContext ctx) {
        Optional<Identifier> alias = ctx.identifier() == null
                ? Optional.empty() : Optional.of(ident(ctx.identifier()));
        return new SelectExpr(expr(ctx.expression()), alias, pos(ctx));
    }

    @Override
    public Object visitTableSource(TSqlParser.TableSourceContext ctx) {
        List<Join> joins = ctx.joinedTable().stream().map(j -> (Join) visit(j)).toList();
        return new TableSource((TableRef) visit(ctx.tablePrimary()), joins, pos(ctx));
    }

    @Override
    public Object visitTablePrimary(TSqlParser.TablePrimaryContext ctx) {
        Optional<Identifier> alias = ctx.identifier() == null
                ? Optional.empty() : Optional.of(ident(ctx.identifier()));
        return new TableRef(qname(ctx.qualifiedName()), alias, pos(ctx));
    }

    @Override
    public Object visitJoinedTable(TSqlParser.JoinedTableContext ctx) {
        TableRef table = (TableRef) visit(ctx.tablePrimary());
        if (ctx.CROSS() != null) {
            return new Join(JoinKind.CROSS, table, Optional.empty(), pos(ctx));
        }
        return new Join(joinKind(ctx.joinType()), table,
                Optional.of(expr(ctx.expression())), pos(ctx));
    }

    private JoinKind joinKind(TSqlParser.JoinTypeContext ctx) {
        if (ctx.LEFT() != null) {
            return JoinKind.LEFT;
        }
        if (ctx.RIGHT() != null) {
            return JoinKind.RIGHT;
        }
        if (ctx.FULL() != null) {
            return JoinKind.FULL;
        }
        return JoinKind.INNER;
    }

    @Override
    public Object visitOrderItem(TSqlParser.OrderItemContext ctx) {
        SortDirection direction = ctx.DESC() != null ? SortDirection.DESC : SortDirection.ASC;
        return new OrderItem(expr(ctx.expression()), direction, Optional.empty(), pos(ctx));
    }

    @Override
    public Object visitSubquery(TSqlParser.SubqueryContext ctx) {
        return visit(ctx.queryExpression());
    }

    // --- DML ---

    @Override
    public Object visitInsertStatement(TSqlParser.InsertStatementContext ctx) {
        List<Identifier> columns = ctx.identifier().stream().map(this::ident).toList();
        List<List<Expression>> rows = ctx.rowValue().stream()
                .map(row -> row.expression().stream().map(this::expr).toList())
                .toList();
        return new InsertStatement(qname(ctx.qualifiedName()), columns, rows, pos(ctx));
    }

    @Override
    public Object visitUpdateStatement(TSqlParser.UpdateStatementContext ctx) {
        List<Assignment> assignments = ctx.assignment().stream()
                .map(a -> new Assignment(ident(a.identifier()), expr(a.expression()), pos(a)))
                .toList();
        Optional<Expression> where = ctx.whereClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.whereClause().expression()));
        return new UpdateStatement(qname(ctx.qualifiedName()), assignments, where, pos(ctx));
    }

    @Override
    public Object visitDeleteStatement(TSqlParser.DeleteStatementContext ctx) {
        Optional<Expression> where = ctx.whereClause() == null
                ? Optional.empty() : Optional.of(expr(ctx.whereClause().expression()));
        return new DeleteStatement(qname(ctx.qualifiedName()), where, pos(ctx));
    }

    // --- DDL ---

    @Override
    public Object visitCreateTableStatement(TSqlParser.CreateTableStatementContext ctx) {
        List<ColumnDefinition> columns = new ArrayList<>();
        List<TableConstraint> constraints = new ArrayList<>();
        for (TSqlParser.TableElementContext element : ctx.tableElement()) {
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
    public Object visitColumnDefinition(TSqlParser.ColumnDefinitionContext ctx) {
        AstBuilderSupport.FoldedType type = columnType(ctx.dataType());
        AstBuilderSupport.ColumnAttributes attributes = new AstBuilderSupport.ColumnAttributes();
        for (TSqlParser.ColumnConstraintContext constraint : ctx.columnConstraint()) {
            if (constraint.NOT() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.NOT_NULL, null, null);
            } else if (constraint.DEFAULT() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.DEFAULT,
                        expr(constraint.expression()), null);
            } else if (constraint.NULL() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.NULL_ALLOWED, null, null);
            } else if (constraint.PRIMARY() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.PRIMARY_KEY, null, null);
            } else if (constraint.UNIQUE() != null) {
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.UNIQUE, null, null);
            } else if (constraint.REFERENCES() != null) {
                Optional<Identifier> column = constraint.identifier() == null
                        ? Optional.empty() : Optional.of(ident(constraint.identifier()));
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.REFERENCES, null,
                        new ForeignKeyRef(qname(constraint.qualifiedName()), column,
                                pos(constraint)));
            } else if (constraint.autoIncrement() != null) {
                TSqlParser.AutoIncrementContext auto = constraint.autoIncrement();
                support.checkIdentitySeed(auto.INTEGER_LITERAL(0).getText(),
                        auto.INTEGER_LITERAL(1).getText(), pos(auto));
                support.applyColumnConstraint(attributes,
                        AstBuilderSupport.ColumnConstraintKind.AUTO_INCREMENT, null, null);
            }
        }
        return support.columnDefinition(ident(ctx.identifier()), type, attributes, pos(ctx));
    }

    @Override
    public Object visitTableConstraint(TSqlParser.TableConstraintContext ctx) {
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

    private List<Identifier> columns(TSqlParser.ColumnListContext ctx) {
        return ctx.identifier().stream().map(this::ident).toList();
    }

    @Override
    public Object visitDropTableStatement(TSqlParser.DropTableStatementContext ctx) {
        return new DropTableStatement(qname(ctx.qualifiedName()), ctx.IF() != null, pos(ctx));
    }

    @Override
    public Object visitAlterTableStatement(TSqlParser.AlterTableStatementContext ctx) {
        AlterAction action = ctx.ADD() != null
                ? new AddColumn((ColumnDefinition) visit(ctx.columnDefinition()), pos(ctx))
                : new DropColumn(ident(ctx.identifier()), pos(ctx));
        return new AlterTableStatement(qname(ctx.qualifiedName()), action, pos(ctx));
    }

    // --- expression ladder ---

    @Override
    public Object visitOrExpression(TSqlParser.OrExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitAndExpression(TSqlParser.AndExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitNotExpression(TSqlParser.NotExpressionContext ctx) {
        if (ctx.NOT() != null) {
            return new UnaryOp(UnaryOperator.NOT, expr(ctx.notExpression()), pos(ctx));
        }
        return visit(ctx.predicate());
    }

    @Override
    public Object visitConcatExpression(TSqlParser.ConcatExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitAdditiveExpression(TSqlParser.AdditiveExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitMultiplicativeExpression(TSqlParser.MultiplicativeExpressionContext ctx) {
        return support.foldBinaryChain(ctx, this);
    }

    @Override
    public Object visitUnaryExpression(TSqlParser.UnaryExpressionContext ctx) {
        if (ctx.unaryExpression() != null) {
            UnaryOperator op = ctx.getChild(0).getText().equals("-")
                    ? UnaryOperator.NEG : UnaryOperator.POS;
            return new UnaryOp(op, expr(ctx.unaryExpression()), pos(ctx));
        }
        return visit(ctx.primaryExpression());
    }

    // --- predicates ---

    @Override
    public Object visitComparisonPredicate(TSqlParser.ComparisonPredicateContext ctx) {
        return new BinaryOp(support.comparisonOperator(ctx.comparisonOperator().getText()),
                expr(ctx.concatExpression(0)), expr(ctx.concatExpression(1)), pos(ctx));
    }

    @Override
    public Object visitBetweenPredicate(TSqlParser.BetweenPredicateContext ctx) {
        return new BetweenPredicate(expr(ctx.concatExpression(0)),
                expr(ctx.concatExpression(1)), expr(ctx.concatExpression(2)),
                ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitLikePredicate(TSqlParser.LikePredicateContext ctx) {
        return new LikePredicate(expr(ctx.concatExpression(0)),
                expr(ctx.concatExpression(1)), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitInListPredicate(TSqlParser.InListPredicateContext ctx) {
        List<Expression> items = ctx.expression().stream().map(this::expr).toList();
        return new InListPredicate(expr(ctx.concatExpression()), items,
                ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitInSubqueryPredicate(TSqlParser.InSubqueryPredicateContext ctx) {
        return new InSubqueryPredicate(expr(ctx.concatExpression()),
                (Query) visit(ctx.subquery()), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitIsNullPredicate(TSqlParser.IsNullPredicateContext ctx) {
        return new IsNullPredicate(expr(ctx.concatExpression()), ctx.NOT() != null, pos(ctx));
    }

    @Override
    public Object visitExistsPredicate(TSqlParser.ExistsPredicateContext ctx) {
        return new ExistsPredicate((Query) visit(ctx.subquery()), pos(ctx));
    }

    @Override
    public Object visitSimplePredicate(TSqlParser.SimplePredicateContext ctx) {
        return visit(ctx.concatExpression());
    }

    // --- primary expressions ---

    @Override
    public Object visitLiteralExpr(TSqlParser.LiteralExprContext ctx) {
        return visit(ctx.literal());
    }

    @Override
    public Object visitLiteral(TSqlParser.LiteralContext ctx) {
        if (ctx.INTEGER_LITERAL() != null) {
            return support.numeric(ctx.INTEGER_LITERAL().getSymbol(), false);
        }
        if (ctx.DECIMAL_LITERAL() != null) {
            return support.numeric(ctx.DECIMAL_LITERAL().getSymbol(), true);
        }
        if (ctx.HEX_LITERAL() != null) {
            throw support.refuse("hex literal " + ctx.HEX_LITERAL().getText(), pos(ctx));
        }
        if (ctx.STRING_LITERAL() != null) {
            return support.stringLiteral(ctx.STRING_LITERAL().getSymbol());
        }
        return new NullLiteral(pos(ctx));
    }

    @Override
    public Object visitCaseExpr(TSqlParser.CaseExprContext ctx) {
        return visit(ctx.caseExpression());
    }

    @Override
    public Object visitCaseExpression(TSqlParser.CaseExpressionContext ctx) {
        List<Expression> expressions = ctx.expression().stream().map(this::expr).toList();
        List<SourcePosition> whenPositions = ctx.WHEN().stream()
                .map(w -> pos(w.getSymbol())).toList();
        return support.caseExpression(expressions, whenPositions, ctx.ELSE() != null,
                pos(ctx));
    }

    @Override
    public Object visitCastExpr(TSqlParser.CastExprContext ctx) {
        return visit(ctx.castExpression());
    }

    @Override
    public Object visitCastExpression(TSqlParser.CastExpressionContext ctx) {
        return new CastExpression(expr(ctx.expression()), castType(ctx.dataType()), pos(ctx));
    }

    @Override
    public Object visitConvertExpr(TSqlParser.ConvertExprContext ctx) {
        return visit(ctx.convertExpression());
    }

    /** T-SQL 2-arg CONVERT folds into the canonical CAST node (§2.2). */
    @Override
    public Object visitConvertExpression(TSqlParser.ConvertExpressionContext ctx) {
        return new CastExpression(expr(ctx.expression()), castType(ctx.dataType()), pos(ctx));
    }

    @Override
    public Object visitFunctionExpr(TSqlParser.FunctionExprContext ctx) {
        return visit(ctx.functionCall());
    }

    @Override
    public Object visitFunctionCall(TSqlParser.FunctionCallContext ctx) {
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

    private Identifier functionNameIdentifier(TSqlParser.FunctionNameContext ctx) {
        return ctx.identifier() != null
                ? ident(ctx.identifier())
                : new Identifier(ctx.getText(), false, pos(ctx));
    }

    @Override
    public Object visitColumnRefExpr(TSqlParser.ColumnRefExprContext ctx) {
        return new ColumnRef(qname(ctx.qualifiedName()), pos(ctx));
    }

    @Override
    public Object visitScalarSubqueryExpr(TSqlParser.ScalarSubqueryExprContext ctx) {
        return new SubqueryExpression((Query) visit(ctx.subquery()), pos(ctx));
    }

    @Override
    public Object visitParenExpr(TSqlParser.ParenExprContext ctx) {
        return visit(ctx.expression());
    }

    // --- shared extraction helpers ---

    private Expression expr(ParseTree tree) {
        return (Expression) visit(tree);
    }

    private Identifier ident(TSqlParser.IdentifierContext ctx) {
        return support.identifier(ctx.getStart());
    }

    private QualifiedName qname(TSqlParser.QualifiedNameContext ctx) {
        return support.qualifiedName(ctx.identifier().stream().map(this::ident).toList(),
                pos(ctx));
    }

    private AstBuilderSupport.FoldedType columnType(TSqlParser.DataTypeContext ctx) {
        return support.foldColumnType(typeWord(ctx, 0), secondTypeWord(ctx), argTexts(ctx),
                pos(ctx));
    }

    private DataType castType(TSqlParser.DataTypeContext ctx) {
        return support.foldCastType(typeWord(ctx, 0), secondTypeWord(ctx), argTexts(ctx),
                pos(ctx));
    }

    private String typeWord(TSqlParser.DataTypeContext ctx, int index) {
        return support.identifier(ctx.identifier(index).getStart()).value();
    }

    private String secondTypeWord(TSqlParser.DataTypeContext ctx) {
        return ctx.identifier().size() > 1 ? typeWord(ctx, 1) : null;
    }

    private List<String> argTexts(TSqlParser.DataTypeContext ctx) {
        return ctx.dataTypeArg().stream().map(ParserRuleContext::getText).toList();
    }

    private static SourcePosition pos(ParserRuleContext ctx) {
        return AstBuilderSupport.pos(ctx);
    }

    private static SourcePosition pos(Token token) {
        return AstBuilderSupport.pos(token);
    }
}
