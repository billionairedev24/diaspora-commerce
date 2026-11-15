package com.afrotransact.module.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Loads each enabled module's own configuration ({@code modules/<name>/application*.yml} inside the module jar, a copy of its
 * standalone {@code application*.yml}) at the lowest precedence, keeping only what belongs to the module.
 *
 * <p>A standalone service's YAML also holds process-wide settings (datasource, Kafka group, server port, metric tags, security
 * chain, actuator). They are meaningless or wrong inside a shared JVM: two modules would overwrite each other, and the Kafka group
 * id in particular would silently move one module's consumers into another module's group. They are dropped here; the core
 * application owns them. The one setting that is merged instead of dropped is the list of anonymous paths
 * ({@code at.security.public-paths}), which is the union of the modules' lists.
 */
class ModuleConfigPostProcessor implements EnvironmentPostProcessor {

    private static final Log LOG = LogFactory.getLog(ModuleConfigPostProcessor.class);

    private static final Pattern MODULE_FILE = Pattern.compile(".*/modules/([^/]+)/application\\.yml$");
    /** {@code at.<group>} sub-trees that are process-wide, not the module's. */
    private static final Set<String> PROCESS_WIDE_AT = Set.of("consumers", "security", "events", "api-docs", "modules");
    /**
     * Library settings only one module uses (Temporal and Redis for orders, Spring AI for ai-gateway and mcp-server, the session
     * store for identity); they are kept so the module's client library is configured as it is standalone. Nothing here is
     * shared by two modules with different values.
     */
    private static final List<String> MODULE_LIBRARY_PREFIXES = List.of("spring.ai.", "spring.temporal.",
            "spring.data.redis.", "spring.session.");
    private static final Set<String> UNIONED_LISTS = Set.of("at.security.public-paths", "at.security.public-post-paths");
    private static final Pattern LIST_ITEM = Pattern.compile("^(.*)\\[\\d+]$");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(
                application.getClassLoader());
        Resource[] bases;
        try {
            bases = resolver.getResources("classpath*:modules/*/application.yml");
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot scan module configuration", ex);
        }
        List<String> profiles = activeProfiles(environment);
        Map<String, Set<String>> unions = new LinkedHashMap<>();
        Map<String, Object> health = new LinkedHashMap<>();
        List<String> readiness = new ArrayList<>(List.of("readinessState"));
        for (Resource base : bases) {
            Matcher matcher = MODULE_FILE.matcher(url(base));
            if (!matcher.matches()) {
                continue;
            }
            String module = matcher.group(1);
            if (!environment.getProperty("at.modules." + module + ".enabled", Boolean.class, true)) {
                switchOffLibraries(environment, resolver, module);
                continue;
            }
            Map<String, Object> merged = new LinkedHashMap<>(load(base));
            for (String profile : profiles) {
                Resource overlay = resolver.getResource(
                        "classpath:modules/" + module + "/application-" + profile + ".yml");
                if (overlay.exists()) {
                    overlay(merged, load(overlay));
                }
            }
            Map<String, Object> kept = new LinkedHashMap<>();
            int dropped = 0;
            for (Map.Entry<String, Object> entry : merged.entrySet()) {
                String union = unionKey(entry.getKey());
                if (union != null) {
                    unions.computeIfAbsent(union, k -> new LinkedHashSet<>()).add(String.valueOf(entry.getValue()));
                } else if (belongsToModule(entry.getKey())) {
                    kept.put(entry.getKey(), entry.getValue());
                } else {
                    dropped++;
                }
            }
            environment.getPropertySources().addLast(new MapPropertySource("moduleConfig[" + module + "]", kept));
            addHealth(environment, module, health, readiness);
            LOG.debug("Module '" + module + "': " + kept.size() + " properties loaded, " + dropped
                    + " process-wide properties ignored (profiles " + profiles + ")");
        }
        if (!health.isEmpty()) {
            health.put("management.endpoint.health.group.readiness.include", String.join(",", readiness));
            environment.getPropertySources().addLast(new MapPropertySource("moduleConfig[health]", health));
        }
        if (!unions.isEmpty()) {
            Map<String, Object> merged = new LinkedHashMap<>();
            unions.forEach((key, values) -> {
                List<String> list = new ArrayList<>(values);
                for (int i = 0; i < list.size(); i++) {
                    merged.put(key + "[" + i + "]", list.get(i));
                }
            });
            environment.getPropertySources().addLast(new MapPropertySource("moduleConfig[merged]", merged));
        }
    }

    /**
     * A disabled module whose jar is still on the classpath (the optional ones) may bring auto-configuration that fails without its
     * settings (Spring AI models without an API key). Such a module ships {@code modules/<name>/module-disabled.properties} with the
     * properties that switch its libraries off; they apply only while the module is disabled.
     */
    private static void switchOffLibraries(ConfigurableEnvironment environment, PathMatchingResourcePatternResolver resolver,
                                           String module) {
        Resource off = resolver.getResource("classpath:modules/" + module + "/module-disabled.properties");
        if (!off.exists()) {
            return;
        }
        try (InputStream in = off.getInputStream()) {
            Properties properties = new Properties();
            properties.load(in);
            Map<String, Object> values = new LinkedHashMap<>();
            properties.forEach((k, v) -> values.put(String.valueOf(k), v));
            environment.getPropertySources().addLast(new MapPropertySource("moduleDisabled[" + module + "]", values));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + off, ex);
        }
    }

    /**
     * Health groups are generated because Spring Boot refuses to start when a group names a contributor that does not exist,
     * which is what a static list would do the moment a module is switched off. Readiness lists each mounted module's start
     * state; the module's own group adds its live database check. A module with no database has no database indicator.
     */
    private static void addHealth(ConfigurableEnvironment environment, String module, Map<String, Object> health,
                                  List<String> readiness) {
        String key = module.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        boolean database = !environment.getProperty("at.modules." + module + ".datasource.url", "").isBlank();
        if (!database) {
            return;
        }
        readiness.add(key + "Module");
        health.put("management.endpoint.health.group." + module + ".include", key + "Module," + key + "Database");
    }

    /** Applies a profile file over the base the way Spring Boot does: a list in the profile replaces the whole base list. */
    static void overlay(Map<String, Object> base, Map<String, Object> profile) {
        for (String key : profile.keySet()) {
            Matcher m = LIST_ITEM.matcher(key);
            if (m.matches()) {
                String prefix = m.group(1) + "[";
                base.keySet().removeIf(k -> k.startsWith(prefix));
            }
        }
        base.putAll(profile);
    }

    static boolean belongsToModule(String key) {
        if (MODULE_LIBRARY_PREFIXES.stream().anyMatch(key::startsWith)) {
            return true;
        }
        if (!key.startsWith("at.")) {
            return false;
        }
        String rest = key.substring("at.".length());
        int dot = rest.indexOf('.');
        String group = dot < 0 ? rest : rest.substring(0, dot);
        int bracket = group.indexOf('[');
        if (bracket >= 0) {
            group = group.substring(0, bracket);
        }
        return !PROCESS_WIDE_AT.contains(group);
    }

    private static String unionKey(String key) {
        Matcher m = LIST_ITEM.matcher(key);
        if (m.matches() && UNIONED_LISTS.contains(m.group(1))) {
            return m.group(1);
        }
        return null;
    }

    private static List<String> activeProfiles(ConfigurableEnvironment environment) {
        String[] active = environment.getActiveProfiles();
        if (active.length > 0) {
            return List.of(active);
        }
        String fallback = environment.getProperty("spring.profiles.default", "local");
        List<String> out = new ArrayList<>();
        for (String p : fallback.split(",")) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private static Map<String, Object> load(Resource resource) {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(resource.getDescription(), resource)) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        out.put(name, enumerable.getProperty(name));
                    }
                }
            }
            return out;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read module configuration " + resource.getDescription(), ex);
        }
    }

    private static String url(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException ex) {
            return resource.getDescription();
        }
    }
}
