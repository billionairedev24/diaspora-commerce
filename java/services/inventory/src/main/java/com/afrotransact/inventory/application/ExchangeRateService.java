package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.ExchangeRateChange;
import com.afrotransact.inventory.port.Page;

/**
 * The currencies stock can be received in and the staff-managed table of exchange rates to USD (ADR 0005 §7). The table
 * only suggests a default at receiving; the receiver can always enter another rate. Every change is audited with a
 * reason.
 */
public interface ExchangeRateService {

    /** Currencies stock can be sourced and costed in (USD first); inventory:read. */
    List<CostCurrency> currencies();

    /** Rates newest day first, one currency or all; staff with inventory:read. */
    Page<ExchangeRate> list(String currency, int page, int size);

    /**
     * The rate that would be suggested on {@code asOf} (today when null) for every currency that has one: the latest on
     * or before that day; staff with inventory:read.
     */
    List<ExchangeRate> current(LocalDate asOf);

    /**
     * Creates or replaces the rate of {@code currency} on {@code rateDate}; inventory:adjust. {@code reason} is
     * required and recorded in the audit with the old and new rate.
     */
    ExchangeRate set(String currency, LocalDate rateDate, BigDecimal rateToUsd, String note, String reason);

    /** The audit trail, newest first, one currency or all; staff with inventory:read. */
    Page<ExchangeRateChange> history(String currency, int page, int size);

    /**
     * A currency stock can be costed in.
     *
     * @param minorUnits digits after the decimal point (2 for USD, 0 for XOF)
     * @param base       true for the US dollar, in which customers pay and everything is valued
     */
    record CostCurrency(String code, String name, int minorUnits, boolean base) {
    }
}
