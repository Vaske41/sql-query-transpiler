package rs.etf.sqltranslator.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DialectTest {

    @Test
    void definesExactlyTheThreeThesisDialects() {
        assertThat(Dialect.values())
                .containsExactly(Dialect.TSQL, Dialect.MYSQL, Dialect.POSTGRESQL);
    }
}
