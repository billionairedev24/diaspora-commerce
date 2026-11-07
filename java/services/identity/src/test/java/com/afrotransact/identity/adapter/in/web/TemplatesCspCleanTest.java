package com.afrotransact.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The hosted pages are served under {@code script-src 'self'; style-src 'self'} (no 'unsafe-inline'). This fails the
 * build when a template or asset brings inline script/style, an event-handler attribute, a {@code javascript:} URL or a
 * third-party host back, instead of finding out as a blank sign-in page in a browser.
 */
class TemplatesCspCleanTest {

    private static final Path MAIN = Path.of("src/main/resources");

    private static final List<Pattern> FORBIDDEN_IN_TEMPLATES = List.of(
            Pattern.compile("<style\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\sstyle\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("th:style\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("th:attr\\s*=\\s*\"[^\"]*\\bstyle\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("th:on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[\\[|\\[\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<script\\b(?![^>]*\\b(th:)?src\\s*=)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<(link|script|img|iframe|object|embed)\\b[^>]*\\b(src|href)\\s*=\\s*\"(https?:)?//",
                    Pattern.CASE_INSENSITIVE));

    @Test
    void noTemplateHasInlineScriptStyleOrEventHandlers() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("templates"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(file);
                for (Pattern pattern : FORBIDDEN_IN_TEMPLATES) {
                    if (pattern.matcher(html).find()) {
                        violations.add(file.getFileName() + " matches " + pattern.pattern());
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void thereAreTemplatesToScan() throws IOException {
        try (Stream<Path> files = Files.list(MAIN.resolve("templates"))) {
            assertThat(files.filter(f -> f.toString().endsWith(".html")).count()).isGreaterThanOrEqualTo(8);
        }
    }

    @Test
    void assetsLoadNothingFromOtherHostsAndCreateNoInlineStyleAttributes() throws IOException {
        List<String> violations = new ArrayList<>();
        Pattern remote = Pattern.compile("@import\\s+(url|[\"'])|url\\(\\s*['\"]?(https?:)?//", Pattern.CASE_INSENSITIVE);
        Pattern inlineStyle = Pattern.compile("setAttribute\\(\\s*[\"']style[\"']|cssText|innerHTML|insertAdjacentHTML"
                + "|\\beval\\(|new Function\\(");
        try (Stream<Path> files = Files.list(MAIN.resolve("static/assets"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".css") || f.toString().endsWith(".js")).toList()) {
                String text = Files.readString(file);
                if (file.toString().endsWith(".css") && remote.matcher(text).find()) {
                    violations.add(file.getFileName() + " loads a remote resource");
                }
                if (file.toString().endsWith(".js") && inlineStyle.matcher(text).find()) {
                    violations.add(file.getFileName() + " writes inline styles/markup or evaluates strings");
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
