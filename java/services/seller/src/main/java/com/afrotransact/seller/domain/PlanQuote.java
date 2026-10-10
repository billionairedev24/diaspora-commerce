package com.afrotransact.seller.domain;

/**
 * Price of a plan for one cadence.
 *
 * @param totalCents    amount charged each billing period
 * @param perMonthCents total ÷ months, rounded half-up to the cent
 * @param savingCents   monthly price × months − total
 */
public record PlanQuote(String planCode, Cadence cadence, long totalCents, long perMonthCents, long savingCents) {
}
