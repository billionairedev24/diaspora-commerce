package com.afrotransact.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The hosted pages ship copies of the web design tokens and the self-hosted Figtree files (identity is built and
 * deployed on its own, without the web workspace). This fails when a copy drifts from its source; refresh with:
 *
 * <pre>
 * cp web/packages/tokens/src/tokens.css java/services/identity/src/main/resources/static/assets/tokens.css
 * cp node_modules/.pnpm/@fontsource+figtree@*&#47;node_modules/@fontsource/figtree/files/figtree-latin-{400,500,600,700,800}-normal.woff2 \
 *    java/services/identity/src/main/resources/static/assets/fonts/
 * </pre>
 *
 * Skipped when the repository's web workspace is not present (for example a container build of this module alone).
 */
class DesignTokensSyncTest {

    private static final Path ASSETS = Path.of("src/main/resources/static/assets");
    private static final Path REPO = Path.of("../../..");
    private static final List<Integer> WEIGHTS = List.of(400, 500, 600, 700, 800);

    @Test
    void tokensCssMatchesTheTokensPackage() throws IOException {
        Path source = REPO.resolve("web/packages/tokens/src/tokens.css");
        Assumptions.assumeTrue(Files.exists(source), "web workspace not checked out");
        assertThat(Files.readString(ASSETS.resolve("tokens.css")))
                .as("static/assets/tokens.css is a copy of web/packages/tokens/src/tokens.css")
                .isEqualTo(Files.readString(source));
    }

    @Test
    void figtreeFilesMatchTheFontsourcePackage() throws IOException {
        Path pnpm = REPO.resolve("node_modules/.pnpm");
        Assumptions.assumeTrue(Files.isDirectory(pnpm), "node_modules not installed");
        Path files;
        try (Stream<Path> dirs = Files.list(pnpm)) {
            files = dirs.filter(d -> d.getFileName().toString().startsWith("@fontsource+figtree@"))
                    .map(d -> d.resolve("node_modules/@fontsource/figtree/files"))
                    .filter(Files::isDirectory)
                    .findFirst().orElse(null);
        }
        Assumptions.assumeTrue(files != null, "@fontsource/figtree not installed");
        for (int weight : WEIGHTS) {
            String name = "figtree-latin-" + weight + "-normal.woff2";
            assertThat(Files.readAllBytes(ASSETS.resolve("fonts").resolve(name))).as(name)
                    .isEqualTo(Files.readAllBytes(files.resolve(name)));
        }
        assertThat(Files.readString(ASSETS.resolve("fonts.css"))).contains(WEIGHTS.stream()
                .map(w -> "figtree-latin-" + w + "-normal.woff2").toArray(String[]::new));
    }

    @Test
    void hostedPageStylesUseDesignTokensOnly() throws IOException {
        String css = Files.readString(ASSETS.resolve("auth.css"));
        assertThat(css).as("raw hex colours in auth.css (use --at-* tokens)").doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b");
        assertThat(css).contains("var(--at-");
    }
}
