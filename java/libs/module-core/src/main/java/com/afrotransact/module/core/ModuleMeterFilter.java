package com.afrotransact.module.core;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;

/**
 * Gives every meter the same two tags, {@code module} and {@code service}. The service value of a module's meters is the short
 * service name the dashboards and alerts already select on; process-level meters (JVM, process, Tomcat) get the core
 * application's name. Every meter always carries both tags because Prometheus refuses meters of one name with different tag
 * sets.
 *
 * <p>Attribution order: a {@code module} tag already set (HTTP server meters, platform meters of a module), the connection pool
 * name, then the module's meter-name and Kafka client-id prefixes.
 */
final class ModuleMeterFilter implements MeterFilter {

    private final ModuleRegistry modules;

    ModuleMeterFilter(ModuleRegistry modules) {
        this.modules = modules;
    }

    @Override
    public Meter.Id map(Meter.Id id) {
        String module = id.getTag("module");
        if (module == null) {
            module = attribute(id);
        }
        String service = modules.serviceOf(module);
        return id.replaceTags(Tags.of(id.getTagsAsIterable()).and(Tag.of("module", module), Tag.of("service", service)));
    }

    private String attribute(Meter.Id id) {
        String name = id.getName();
        if (name.startsWith("hikaricp.")) {
            String pool = id.getTag("pool");
            if (pool != null && modules.mounted(pool)) {
                return pool;
            }
        }
        String clientId = firstNonNull(id.getTag("client.id"), id.getTag("name"), id.getTag("consumer"));
        for (ModuleDefinition module : modules.modules()) {
            for (String prefix : module.meterPrefixes()) {
                if (name.startsWith(prefix) || (clientId != null && clientId.startsWith(prefix))) {
                    return module.name();
                }
            }
        }
        return ModuleRegistry.CORE;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
