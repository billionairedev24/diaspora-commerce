package com.afrotransact.taxreturns.port;

import com.afrotransact.taxreturns.domain.shadow.ShadowKind;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/** Counters of the tax engine. Nothing here hides a fallback or a block. */
public interface TaxTelemetry {

    void resolved(String status);

    /** A quote line by its decision status (RESOLVED, MISSING_FACTS ...; EXPLICIT for caller-given categories). */
    void quoteLine(String status, String reason);

    /** A provider quote that fell back to the rules engine, and how many lines the provider could not take. */
    void providerFallback(String provider, String why, int lines);

    void shadow(TaxProviderId provider, ShadowKind kind);

    void shadowFailure(TaxProviderId provider, String why);

    void recordChange(String kind);
}
