package com.afrotransact.seller.domain;

/**
 * What Stripe holds for a Price (read when syncing): recurring every {@code intervalMonths} months.
 *
 * @param lookupKey null when the price has none
 */
public record StripePriceFacts(String id, String productId, long amountCents, String currency, int intervalMonths,
                               boolean active, String lookupKey) {

    /** Same amount, currency (USD) and interval as our version's price for {@code cadence}. */
    public boolean matches(long expectedCents, Cadence cadence) {
        return amountCents == expectedCents && "usd".equalsIgnoreCase(currency) && intervalMonths == cadence.months();
    }
}
