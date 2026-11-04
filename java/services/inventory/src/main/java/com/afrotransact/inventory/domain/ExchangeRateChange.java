package com.afrotransact.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of the append-only rate audit: who set which rate for which day, what it was before and why.
 *
 * @param id      monotonic id, newest first in listings
 * @param oldRate the rate before an UPDATE; null for a CREATE
 */
public record ExchangeRateChange(long id, Instant occurredAt, String currency, LocalDate rateDate, RateAction action,
                                 BigDecimal oldRate, BigDecimal newRate, String reason, String actor) {
}
