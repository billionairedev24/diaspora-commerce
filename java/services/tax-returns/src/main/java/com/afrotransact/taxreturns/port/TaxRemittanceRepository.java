package com.afrotransact.taxreturns.port;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.tax.TaxRemittance;

/** Remittances staff recorded (active and voided). */
public interface TaxRemittanceRepository {

    void insert(TaxRemittance remittance);

    Optional<TaxRemittance> find(UUID id);

    /** Records of the months from..to inclusive, newest first; voided ones only when asked for. */
    List<TaxRemittance> between(YearMonth from, YearMonth to, boolean includeVoided);

    /** Marks the record as withdrawn; false when it was already voided. */
    boolean markVoided(UUID id, String by, Instant at, String reason);
}
