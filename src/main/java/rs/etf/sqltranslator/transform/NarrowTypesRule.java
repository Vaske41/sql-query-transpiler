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
