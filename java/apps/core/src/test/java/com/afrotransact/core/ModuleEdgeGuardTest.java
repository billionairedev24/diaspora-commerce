package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * The regression guard for ADR 0011 point 4 ("a monolith makes no HTTP call to itself"): no module-to-module HTTP adapter may be
 * selected in core when an in-process one exists. Two parts, both run without a database:
 *
 * <ul>
 *   <li>every HTTP adapter that is switched by {@link ConditionalOnModuleMode} has an in-process partner for the same edge
 *   (caller and callee), and the other way round, so exactly one of the two is a bean for each edge and mode;</li>
 *   <li>every other {@code Http*} adapter component under {@code adapter.out} is on the list of edges that stay HTTP
 *   ({@code http-edges.txt}, with the reason), so a new HTTP adapter to another module cannot slip in unnoticed, and a listed
 *   edge that disappeared or was converted is removed from the list.</li>
 * </ul>
 *
 * <p>The beans that are really chosen in core are checked on a running context in {@link ModuleEdgesInCoreTest}.
 */
class ModuleEdgeGuardTest {

    @Test
    void everyHttpAdapterHasItsInProcessPartnerAndNothingUnlistedStaysHttp() throws Exception {
        List<Class<?>> components = components();
        Set<String> http = new TreeSet<>();
        Set<String> inProcess = new TreeSet<>();
        for (Class<?> c : components) {
            ConditionalOnModuleMode mode = c.getAnnotation(ConditionalOnModuleMode.class);
            if (mode == null) {
                continue;
            }
            (mode.value() == ModuleMode.HTTP ? http : inProcess).add(mode.caller() + " -> " + mode.callee() + " ("
                    + c.getSimpleName() + ")");
        }
        Set<String> httpEdges = edges(http);
        Set<String> inProcessEdges = edges(inProcess);
        assertThat(httpEdges).as("HTTP adapters whose edge has no in-process adapter").isSubsetOf(inProcessEdges);
        assertThat(inProcessEdges).as("in-process adapters whose edge has no HTTP adapter (detach would be lost)")
                .isSubsetOf(httpEdges);

        Map<String, String> staysHttp = allowList();
        Set<String> unlisted = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        for (Class<?> c : components) {
            String name = c.getSimpleName();
            boolean httpAdapter = c.getPackageName().contains(".adapter.out.") && name.startsWith("Http");
            if (!httpAdapter || c.isAnnotationPresent(ConditionalOnModuleMode.class) || switchedByExpression(c)) {
                continue;
            }
            seen.add(name);
            if (!staysHttp.containsKey(name)) {
                unlisted.add(c.getName());
            }
        }
        assertThat(unlisted).as("HTTP adapters that are always on in core but are not on the list of edges that stay HTTP "
                + "(convert them to an in-process adapter, or list them in http-edges.txt with the reason)").isEmpty();
        assertThat(staysHttp.keySet()).as("edges on the stay-HTTP list that no longer exist as unconditional HTTP adapters")
                .isSubsetOf(seen);
    }

    /** A hand-written switch on {@code at.<caller>.<callee>.mode}, as orders uses. */
    private static boolean switchedByExpression(Class<?> c) {
        ConditionalOnExpression expression = c.getAnnotation(ConditionalOnExpression.class);
        return expression != null && expression.value().contains(".mode");
    }

    private static Set<String> edges(Set<String> described) {
        Set<String> out = new TreeSet<>();
        for (String d : described) {
            out.add(d.substring(0, d.indexOf(" (")));
        }
        return out;
    }

    private static List<Class<?>> components() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.afrotransact")) {
            out.add(Class.forName(definition.getBeanClassName(), false, ModuleEdgeGuardTest.class.getClassLoader()));
        }
        return out;
    }

    static Map<String, String> allowList() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = ModuleEdgeGuardTest.class.getResourceAsStream("/http-edges.txt")) {
            assertThat(in).as("http-edges.txt").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                assertThat(eq).as("line '%s' must be Name=reason", t).isPositive();
                assertThat(t.substring(eq + 1).strip()).as("reason of %s", t.substring(0, eq)).isNotBlank();
                out.put(t.substring(0, eq).strip(), t.substring(eq + 1).strip());
            }
        }
        return out;
    }
}
