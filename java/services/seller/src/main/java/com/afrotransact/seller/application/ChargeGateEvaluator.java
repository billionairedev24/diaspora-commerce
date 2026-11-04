package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.seller.domain.ChargeGate;

/** Reads an organisation's charge gate against its sales and listings. */
public interface ChargeGateEvaluator {

    /** What the gate decides for a period starting at {@code now}; empty when the organisation has no active gate. */
    Optional<ChargeGate.Decision> decide(String orgId, Instant now);

    /** After an invoice was issued under {@code decision}: records a met START_AFTER gate, clears an expired one. */
    void afterIssue(String orgId, ChargeGate.Decision decision, Instant now);

    /** The metric the gate reads, now (sales since the gate was set, or live listings; 0 for WAIVE_UNTIL). */
    long progress(String orgId, ChargeGate gate, Instant now);

    /** Job: marks START_AFTER gates that reached their threshold. Returns how many were met. */
    int markMetGates();
}
