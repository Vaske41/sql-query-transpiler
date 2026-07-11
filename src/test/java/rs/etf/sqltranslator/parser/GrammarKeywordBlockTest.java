package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the Phase 2 invariant that the shared keyword block is byte-identical
 * across all three dialect grammars (mitigates keyword-drift risk).
 */
class GrammarKeywordBlockTest {

    private static final Path GRAMMAR_DIR = Path.of("src/main/antlr4/rs/etf/sqltranslator/grammar");

    @Test
    void sharedKeywordBlockIsByteIdenticalAcrossDialects() throws Exception {
        String tsql = keywordBlock(GRAMMAR_DIR.resolve("TSql.g4"));
        String mysql = keywordBlock(GRAMMAR_DIR.resolve("MySql.g4"));
        String postgresql = keywordBlock(GRAMMAR_DIR.resolve("PostgreSql.g4"));

        assertThat(tsql).isNotBlank();
        assertThat(mysql).isEqualTo(tsql);
        assertThat(postgresql).isEqualTo(tsql);
    }

    private static String keywordBlock(Path grammarFile) throws Exception {
        String text = Files.readString(grammarFile);
        String startMarker = "// 3. Keywords";
        String endMarker = "// 4.";
        int start = text.indexOf(startMarker);
        int end = text.indexOf(endMarker);
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException(
                    "Could not locate section-3 keyword block markers in " + grammarFile);
        }
        // Include from the section banner through the line before section 4.
        int bannerStart = text.lastIndexOf("// ===", start);
        if (bannerStart < 0) {
            bannerStart = start;
        }
        return text.substring(bannerStart, end).stripTrailing();
    }
}
