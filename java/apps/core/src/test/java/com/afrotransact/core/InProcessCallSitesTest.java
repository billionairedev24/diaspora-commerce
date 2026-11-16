package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every in-process call of production code names its callee, so the edge's own timeout, bulkhead and metrics apply:
 * {@code InProcessCalls.as(caller, callee, call)} (or the operation form), never the two-argument form that is bounded under the
 * catch-all name {@code unspecified}. Reads the sources of the services; no database.
 */
class InProcessCallSitesTest {

    private static final String CALL = "InProcessCalls.as(";

    @Test
    void noProductionCallSiteLeavesTheCalleeUnnamed() throws IOException {
        Path services = Path.of("..", "..", "services").normalize();
        if (!Files.isDirectory(services)) {
            services = Path.of("java", "services");
        }
        assertThat(services).isDirectory();
        List<String> unnamed = new ArrayList<>();
        int sites = 0;
        try (Stream<Path> files = Files.walk(services)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("src/main/java")).toList()) {
                String source = Files.readString(file);
                for (int at = source.indexOf(CALL); at >= 0; at = source.indexOf(CALL, at + 1)) {
                    sites++;
                    if (topLevelArguments(source, at + CALL.length()) < 3) {
                        unnamed.add(file.getFileName() + " near offset " + at);
                    }
                }
            }
        }
        assertThat(sites).as("call sites found").isGreaterThanOrEqualTo(8);
        assertThat(unnamed).as("InProcessCalls.as(caller, call) without a callee").isEmpty();
    }

    /** Counts the arguments of the call whose opening parenthesis was just consumed (commas at depth zero). */
    static int topLevelArguments(String source, int from) {
        int depth = 0;
        int args = 1;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                if (depth == 0) {
                    return args;
                }
                depth--;
            } else if (c == '"') {
                i = source.indexOf('"', i + 1);
            } else if (c == ',' && depth == 0) {
                args++;
            }
        }
        return args;
    }
}
