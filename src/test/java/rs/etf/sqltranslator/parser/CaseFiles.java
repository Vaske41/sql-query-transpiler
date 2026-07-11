package rs.etf.sqltranslator.parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Discovers SQL corpus files on the test classpath. Fails loudly when a directory is
 * missing or empty, so a resources-path typo can never fake a green build via an
 * empty {@code @TestFactory} stream.
 */
final class CaseFiles {

    private final Path root;
    private final List<Path> files;

    private CaseFiles(Path root, List<Path> files) {
        this.root = root;
        this.files = files;
    }

    static CaseFiles under(String classpathRoot, Predicate<Path> fileFilter) {
        URL rootUrl = CaseFiles.class.getResource(classpathRoot);
        if (rootUrl == null) {
            throw new IllegalStateException("Classpath root not found: " + classpathRoot);
        }
        Path root;
        try {
            root = Path.of(rootUrl.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Unresolvable classpath root: " + classpathRoot, e);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).filter(fileFilter).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (files.isEmpty()) {
            throw new IllegalStateException("No test inputs found under " + classpathRoot);
        }
        return new CaseFiles(root, files);
    }

    List<Path> files() {
        return files;
    }

    String displayName(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
