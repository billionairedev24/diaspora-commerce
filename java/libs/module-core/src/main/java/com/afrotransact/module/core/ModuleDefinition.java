package com.afrotransact.module.core;

import java.util.List;

/** A module as declared by {@link ModuleSpec}. */
public record ModuleDefinition(String name, String service, String basePackage, String application,
                               List<String> meterPrefixes) {

    public ModuleDefinition {
        meterPrefixes = meterPrefixes == null ? List.of() : List.copyOf(meterPrefixes);
    }

    /** True when {@code className} is the module's own code. */
    public boolean owns(String className) {
        return className.startsWith(basePackage + ".");
    }

    /** The bean name of one of the module's infrastructure beans, for example {@code catalog.dataSource}. */
    public String bean(String suffix) {
        return name + "." + suffix;
    }
}
