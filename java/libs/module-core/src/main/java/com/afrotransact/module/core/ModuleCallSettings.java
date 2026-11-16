package com.afrotransact.module.core;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;

import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

/**
 * The bounded wait and the bulkhead of each in-process edge, as configuration: {@code at.<caller>.<callee>.timeout} (module names
 * without dashes), {@code at.<caller>.<callee>.<operation>-timeout}, the packaged defaults in
 * {@code at-module-call-timeouts.properties} (the read timeout each HTTP adapter used), then 10 s for an edge with ai-gateway on either
 * side and 5 s for the rest; and {@code at.modules.<callee>.call-concurrency} for the bulkhead, by default twice the callee's pool.
 */
final class ModuleCallSettings {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration AI_TIMEOUT = Duration.ofSeconds(10);
    static final String AI_GATEWAY = "ai-gateway";
    private static final int MIN_CONCURRENCY = 8;

    private final Environment environment;
    private final ModuleRegistry modules;
    private final Properties defaults;

    ModuleCallSettings(Environment environment, ModuleRegistry modules) {
        this.environment = environment;
        this.modules = modules;
        this.defaults = packagedDefaults();
    }

    Duration timeout(String caller, String callee, String operation) {
        String edge = "at." + key(caller) + "." + key(callee);
        if (operation != null && !operation.isBlank()) {
            Duration set = duration(edge + "." + operation + "-timeout");
            if (set != null) {
                return set;
            }
        }
        Duration set = duration(edge + ".timeout");
        if (set == null) {
            set = duration("at." + key(caller) + ".upstreams.timeout");
        }
        if (set != null) {
            return set;
        }
        String base = caller + "." + callee;
        if (operation != null && defaults.containsKey(base + "." + operation)) {
            return DurationStyle.detectAndParse(defaults.getProperty(base + "." + operation));
        }
        if (defaults.containsKey(base)) {
            return DurationStyle.detectAndParse(defaults.getProperty(base));
        }
        return AI_GATEWAY.equals(caller) || AI_GATEWAY.equals(callee) ? AI_TIMEOUT : DEFAULT_TIMEOUT;
    }

    int concurrency(String callee) {
        Integer set = environment.getProperty("at.modules." + callee + ".call-concurrency", Integer.class);
        if (set != null) {
            return Math.max(1, set);
        }
        int pool = modules.mounted(callee) ? modules.properties(callee).datasource().maximumPoolSize() : 0;
        return Math.max(MIN_CONCURRENCY, 2 * pool);
    }

    private Duration duration(String property) {
        String value = environment.getProperty(property);
        return value == null || value.isBlank() ? null : DurationStyle.detectAndParse(value.trim());
    }

    /** {@code tax-returns} becomes {@code taxreturns}, as in {@code at.taxreturns.mode}. */
    static String key(String module) {
        return module.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static Properties packagedDefaults() {
        Properties properties = new Properties();
        try (InputStream in = ModuleCallSettings.class.getResourceAsStream("/at-module-call-timeouts.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read at-module-call-timeouts.properties", e);
        }
        return properties;
    }
}
