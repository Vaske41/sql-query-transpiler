package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-dialect AST equality (§6.2) — the direct evidence for the thesis's "one
 * generalized AST" claim: for case directories whose three dialect inputs express
 * the same logical statement, the three built {@code Script}s must produce identical
 * {@link AstDumper} output. The dump is position-free by design, so line/column
 * differences between the dialect files cannot cause false negatives.
 *
 * <p>Excluded directories, with reasons:
 * <ul>
 *   <li>{@code casts/cast-simple} — deliberately different target types per dialect
 *       file</li>
 *   <li>{@code create-table-types/create-autoincrement} — T-SQL file declares
 *       {@code NVARCHAR}, the others {@code VARCHAR}</li>
 *   <li>{@code create-table-types/create-basic} — dialect-specific types and
 *       defaults ({@code NVARCHAR(MAX)} vs {@code TEXT}, {@code GETDATE()} vs
 *       {@code NOW()}, {@code 1} vs {@code TRUE})</li>
 *   <li>{@code functions/functions-common} — dialect-specific function names;
 *       they unify only through Phase 4's function-mapping rules</li>
 *   <li>{@code select-basic/select-concat} — {@code +} / {@code ||} /
 *       {@code CONCAT()} unify only in Phase 4</li>
 *   <li>{@code select-basic/select-quoted-identifiers} — the T-SQL file selects an
 *       extra column (both quoting styles)</li>
 *   <li>{@code select-basic/select-star-qualified} — different schema qualifiers
 *       ({@code dbo}/{@code shop}/{@code public})</li>
 *   <li>{@code select-basic/select-strings} — different literal sets per dialect</li>
 *   <li>directories with fewer than three dialect files (dialect-specific corpus
 *       cases such as {@code limits/top-n}) are out of scope by construction</li>
 * </ul>
 */
class CrossDialectAstEqualityTest {

    private static final List<String> EQUAL_CASES = List.of(
            "constraints/alter-add-column",
            "constraints/ddl-then-dml-script",
            "constraints/drop-alter",
            "constraints/pk-fk-unique",
            "create-table-types/double-precision",
            "functions/count-star-distinct",
            "functions/left-right",
            "functions/max-aggregate",
            "insert-update-delete/delete-where",
            "insert-update-delete/insert-multi-row",
            "insert-update-delete/insert-no-column-list",
            "insert-update-delete/script-mixed",
            "insert-update-delete/update-where",
            "joins/cross-join",
            "joins/inner-join",
            "joins/left-right-join",
            "joins/multi-join-where",
            "literals/exponent",
            "select-basic/group-having",
            "select-basic/keyword-ish-identifiers",
            "select-basic/select-arithmetic",
            "select-basic/select-case-expr",
            "select-basic/select-columns-where",
            "select-basic/select-distinct-alias",
            "select-basic/select-literal",
            "select-basic/select-negated-predicates",
            "select-basic/select-predicates",
            "set-ops/union",
            "set-ops/union-all-ordered",
            "subqueries/exists-subquery",
            "subqueries/in-subquery",
            "subqueries/scalar-subquery");

    @TestFactory
    Stream<DynamicTest> threeDialectInputsBuildIdenticalAsts() {
        CaseFiles corpus = CaseFiles.under("/cases",
                p -> p.getFileName().toString().startsWith("input."));
        Path root = corpus.files().get(0);   // any file; used only to resolve the root
        Path casesRoot = rootOf(root);
        AstDumper dumper = new AstDumper();
        return EQUAL_CASES.stream()
                .map(dir -> DynamicTest.dynamicTest(dir, () -> {
                    String tsql = dump(dumper, casesRoot, dir, "tsql", Dialect.TSQL);
                    String mysql = dump(dumper, casesRoot, dir, "mysql", Dialect.MYSQL);
                    String postgresql =
                            dump(dumper, casesRoot, dir, "postgresql", Dialect.POSTGRESQL);
                    assertThat(mysql).as("MySQL vs T-SQL AST for %s", dir).isEqualTo(tsql);
                    assertThat(postgresql).as("PostgreSQL vs T-SQL AST for %s", dir)
                            .isEqualTo(tsql);
                }));
    }

    private static String dump(AstDumper dumper, Path casesRoot, String dir, String tag,
                               Dialect dialect) throws Exception {
        Path file = casesRoot.resolve(dir).resolve("input." + tag + ".sql");
        assertThat(file).as("curated case must have all three dialect files").exists();
        return dumper.dump(AstBuilderFacade.buildScript(Files.readString(file), dialect));
    }

    /** Walks up from any corpus file to the {@code cases} root directory. */
    private static Path rootOf(Path someCaseFile) {
        Path p = someCaseFile;
        while (p != null && !p.getFileName().toString().equals("cases")) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("cases root not found from " + someCaseFile);
        }
        return p;
    }
}
