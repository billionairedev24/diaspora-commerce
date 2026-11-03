package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxMethodChange;

/** Platform tax settings changed at runtime. */
public interface TaxSettingsRepository {

    /** The method staff chose; empty until someone does (the configured default applies). */
    Optional<TaxMethod> method();

    /** Who set it last and when; empty until someone does. */
    Optional<Stamp> methodStamp();

    /** Stores the method and records the change in the history. */
    void changeMethod(TaxMethod to, String reason, String by, Instant at);

    /** Newest first. */
    List<TaxMethodChange> history(int limit);

    record Stamp(String by, Instant at) {
    }
}
