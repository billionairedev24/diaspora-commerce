package com.afrotransact.seller.domain;

/**
 * What the applicant typed on "payouts". Account and routing numbers are only validated and reduced to the last
 * 4 digits; {@code externalAccountToken} ({@code btok_…}, created by Stripe.js) carries the real details to Stripe.
 */
public record PayoutInput(String accountHolderName, String accountType, String bankName, String routingNumber,
                          String accountNumber, String accountNumberConfirmation, String externalAccountToken) {
}
