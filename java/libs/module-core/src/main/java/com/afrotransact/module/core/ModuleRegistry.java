package com.afrotransact.module.core;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.util.ClassUtils;

/** The modules mounted in this JVM, with their settings, and the question every piece of infrastructure asks: whose is this class? */
public final class ModuleRegistry {

    /** The name and service label of everything that belongs to no module (the framework, the platform libraries). */
    public static final String CORE = "core";

    private final Map<String, ModuleDefinition> modules = new LinkedHashMap<>();
    private final Map<String, ModuleProperties> properties = new LinkedHashMap<>();
    private final String coreService;

    public ModuleRegistry(String coreService) {
        this.coreService = coreService;
    }

    void add(ModuleDefinition definition, ModuleProperties settings) {
        modules.put(definition.name(), definition);
        properties.put(definition.name(), settings);
    }

    public Collection<ModuleDefinition> modules() {
        return modules.values();
    }

    public Optional<ModuleDefinition> module(String name) {
        return Optional.ofNullable(modules.get(name));
    }

    public ModuleProperties properties(String name) {
        ModuleProperties found = properties.get(name);
        if (found == null) {
            throw new IllegalStateException("Module '" + name + "' is not mounted (disabled or unknown)");
        }
        return found;
    }

    /** Position of the module in mount order (used to pick between two modules that map the same route). */
    public int indexOf(String name) {
        int i = 0;
        for (String key : modules.keySet()) {
            if (key.equals(name)) {
                return i;
            }
            i++;
        }
        return Integer.MAX_VALUE;
    }

    public boolean mounted(String name) {
        return modules.containsKey(name);
    }

    /** The module whose code {@code type} is, ignoring proxies, or empty for framework and platform classes. */
    public Optional<ModuleDefinition> moduleOf(Class<?> type) {
        return moduleOfClassName(ClassUtils.getUserClass(type).getName());
    }

    public Optional<ModuleDefinition> moduleOfClassName(String className) {
        for (ModuleDefinition definition : modules.values()) {
            if (definition.owns(className)) {
                return Optional.of(definition);
            }
        }
        return Optional.empty();
    }

    /** The name an object reports: its module, or {@link #CORE}. */
    public String nameOf(Object bean) {
        if (bean instanceof ModuleOwned owned) {
            return owned.module();
        }
        return moduleOf(bean.getClass()).map(ModuleDefinition::name).orElse(CORE);
    }

    /** The {@code service} label value of a module, or the core application's for {@link #CORE}. */
    public String serviceOf(String moduleName) {
        return module(moduleName).map(ModuleDefinition::service).orElse(coreService);
    }
}
