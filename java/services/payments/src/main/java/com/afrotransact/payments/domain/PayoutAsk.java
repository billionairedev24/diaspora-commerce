package com.afrotransact.payments.domain;

/**
 * What the requester asked for: a specific amount, or everything available ({@code all}).
 *
 * @param amount null when {@code all} is true
 */
public record PayoutAsk(Money amount, boolean all) {
}
