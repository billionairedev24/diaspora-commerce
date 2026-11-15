package com.afrotransact.module.core;

import java.time.Instant;

/** What a module reports about itself once it is up: shown by the module's health indicator. */
public final class ModuleStatus {

    private final String module;
    private final String database;
    private final String schemaVersion;
    private final int migrationsApplied;
    private final Instant startedAt = Instant.now();

    ModuleStatus(String module, String database, String schemaVersion, int migrationsApplied) {
        this.module = module;
        this.database = database;
        this.schemaVersion = schemaVersion;
        this.migrationsApplied = migrationsApplied;
    }

    public String module() {
        return module;
    }

    public String database() {
        return database;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public int migrationsApplied() {
        return migrationsApplied;
    }

    public Instant startedAt() {
        return startedAt;
    }
}
