package com.afrotransact.deliveryruns.application;

import java.util.function.Supplier;

import com.afrotransact.deliveryruns.domain.EconomicsRevision;
import com.afrotransact.deliveryruns.domain.EconomicsSettings;
import com.afrotransact.deliveryruns.port.EconomicsSettingsRepository;
import org.springframework.stereotype.Component;

/**
 * The driver cost model in force: what staff saved, else the environment's defaults. Read at every cost calculation
 * through a short cache (a few seconds across replicas; the replica that saved drops it at once).
 */
@Component
public class EconomicsBook implements Supplier<EconomicsSettings> {

    /** How long a replica keeps what it read (ns). */
    static final long TTL_NANOS = 5_000_000_000L;

    private final EconomicsSettingsRepository repository;
    private final EconomicsSettings defaults;
    private volatile Held held;

    EconomicsBook(EconomicsSettingsRepository repository, EconomicsSettings defaults) {
        this.repository = repository;
        this.defaults = defaults;
    }

    private record Held(EconomicsRevision revision, long readAt) {
    }

    @Override
    public EconomicsSettings get() {
        return revision().settings();
    }

    /** The settings with their version: version 0 while the environment's defaults apply. */
    EconomicsRevision revision() {
        Held now = held;
        long t = System.nanoTime();
        if (now != null && t - now.readAt() < TTL_NANOS) {
            return now.revision();
        }
        EconomicsRevision read = repository.find().orElseGet(() -> EconomicsRevision.defaults(defaults));
        held = new Held(read, t);
        return read;
    }

    /** The environment's defaults, the "before" of the first staff save. */
    EconomicsSettings defaults() {
        return defaults;
    }

    /** Drops what was read, after a save. */
    void forget() {
        held = null;
    }
}
