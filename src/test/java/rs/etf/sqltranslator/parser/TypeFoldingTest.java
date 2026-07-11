package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.CreateTableStatement;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.FixedLength;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Per-dialect type folding (D3, §2.3): dialect type names fold into
 * {@link GenericType} at build time; unknown names, illegal two-word forms, and
 * arguments on non-parameterizable folds are refused, never silently dropped.
 */
class TypeFoldingTest {

    @Test
    void tsqlFolds() {
        assertThat(columnType("BIT", Dialect.TSQL).type()).isEqualTo(GenericType.BOOLEAN);
        assertThat(columnType("FLOAT", Dialect.TSQL).type()).isEqualTo(GenericType.DOUBLE);
        assertThat(columnType("REAL", Dialect.TSQL).type()).isEqualTo(GenericType.FLOAT);
        assertThat(columnType("DATETIME2", Dialect.TSQL).type())
                .isEqualTo(GenericType.TIMESTAMP);
        assertThat(columnType("NCHAR(5)", Dialect.TSQL).type())
                .isEqualTo(GenericType.NVARCHAR);
        assertThat(columnType("IMAGE", Dialect.TSQL).type()).isEqualTo(GenericType.BLOB);
    }

    @Test
    void mysqlFolds() {
        assertThat(columnType("INT", Dialect.MYSQL).type()).isEqualTo(GenericType.INTEGER);
        assertThat(columnType("BOOL", Dialect.MYSQL).type()).isEqualTo(GenericType.BOOLEAN);
        assertThat(columnType("DATETIME", Dialect.MYSQL).type())
                .isEqualTo(GenericType.TIMESTAMP);
        assertThat(columnType("BLOB", Dialect.MYSQL).type()).isEqualTo(GenericType.BLOB);
    }

    @Test
    void pgFolds() {
        assertThat(columnType("INT4", Dialect.POSTGRESQL).type())
                .isEqualTo(GenericType.INTEGER);
        assertThat(columnType("INT8", Dialect.POSTGRESQL).type())
                .isEqualTo(GenericType.BIGINT);
        assertThat(columnType("REAL", Dialect.POSTGRESQL).type())
                .isEqualTo(GenericType.FLOAT);
        assertThat(columnType("FLOAT8", Dialect.POSTGRESQL).type())
                .isEqualTo(GenericType.DOUBLE);
        assertThat(columnType("BYTEA", Dialect.POSTGRESQL).type())
                .isEqualTo(GenericType.BLOB);
    }

    @Test
    void doublePrecisionFoldsInAllThreeDialects() {
        for (Dialect dialect : Dialect.values()) {
            assertThat(columnType("DOUBLE PRECISION", dialect).type())
                    .as("DOUBLE PRECISION in %s", dialect)
                    .isEqualTo(GenericType.DOUBLE);
        }
    }

    @Test
    void pgSerialFamilyFoldsToIntegerTypesWithAutoIncrement() {
        ColumnDefinition serial = column("CREATE TABLE t (id SERIAL);", Dialect.POSTGRESQL);
        assertThat(serial.type().type()).isEqualTo(GenericType.INTEGER);
        assertThat(serial.autoIncrement()).isTrue();
        ColumnDefinition big = column("CREATE TABLE t (id BIGSERIAL);", Dialect.POSTGRESQL);
        assertThat(big.type().type()).isEqualTo(GenericType.BIGINT);
        assertThat(big.autoIncrement()).isTrue();
        ColumnDefinition small = column("CREATE TABLE t (id SMALLSERIAL);",
                Dialect.POSTGRESQL);
        assertThat(small.type().type()).isEqualTo(GenericType.SMALLINT);
        assertThat(small.autoIncrement()).isTrue();
    }

    @Test
    void serialAsCastTargetIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> AstBuilderFacade.buildScript(
                        "SELECT CAST(x AS SERIAL) FROM t;", Dialect.POSTGRESQL))
                .withMessageContaining("SERIAL in CAST");
    }

    @Test
    void lengthAndScaleAreCarriedForParameterizableGenerics() {
        DataType decimal = columnType("DECIMAL(10,2)", Dialect.POSTGRESQL);
        assertThat(decimal.type()).isEqualTo(GenericType.DECIMAL);
        assertThat(decimal.length()).contains(new FixedLength(10));
        assertThat(decimal.scale()).contains(2);
        DataType varchar = columnType("VARCHAR(100)", Dialect.MYSQL);
        assertThat(varchar.length()).contains(new FixedLength(100));
        assertThat(varchar.scale()).isEmpty();
    }

    @Test
    void nvarcharMaxFoldsToMaxLength() {
        DataType type = columnType("NVARCHAR(MAX)", Dialect.TSQL);
        assertThat(type.type()).isEqualTo(GenericType.NVARCHAR);
        assertThat(type.length()).contains(new MaxLength());
    }

    @Test
    void varbinaryMaxFoldsToBlobConsumingTheMax() {
        DataType type = columnType("VARBINARY(MAX)", Dialect.TSQL);
        assertThat(type.type()).isEqualTo(GenericType.BLOB);
        assertThat(type.length()).isEmpty();
    }

    @Test
    void plainVarbinaryIsRefusedAsUnknown() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> columnType("VARBINARY(100)", Dialect.TSQL))
                .withMessageContaining("type VARBINARY");
    }

    @Test
    void unknownTypeIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> columnType("GEOGRAPHY", Dialect.TSQL))
                .withMessageContaining("type GEOGRAPHY");
    }

    @Test
    void illegalTwoWordTypeIsRefusedWithOffendingText() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> columnType("BIG NUMBER", Dialect.MYSQL))
                .withMessageContaining("type BIG NUMBER");
    }

    @Test
    void lengthArgumentOnNonParameterizableFoldIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> columnType("FLOAT(24)", Dialect.TSQL))
                .withMessageContaining("length argument on type FLOAT");
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> columnType("DOUBLE PRECISION(10)", Dialect.POSTGRESQL))
                .withMessageContaining("length argument on type DOUBLE PRECISION");
    }

    @Test
    void maxLengthOutsideStringTypesIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> columnType("DECIMAL(MAX)", Dialect.TSQL))
                .withMessageContaining("MAX length on type DECIMAL");
    }

    @Test
    void scaleOutsideDecimalIsRefused() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> columnType("VARCHAR(10,2)", Dialect.MYSQL))
                .withMessageContaining("scale argument on type VARCHAR");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static DataType columnType(String declaredType, Dialect dialect) {
        return column("CREATE TABLE t (c " + declaredType + ");", dialect).type();
    }

    private static ColumnDefinition column(String sql, Dialect dialect) {
        return ((CreateTableStatement) AstBuilderFacade.buildScript(sql, dialect)
                .statements().get(0)).columns().get(0);
    }
}
