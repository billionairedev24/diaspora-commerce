package com.afrotransact.seller.domain;

/**
 * Step "payouts": only the bank name, account type, holder and last 4 digits are kept; the full account and routing
 * numbers go to Stripe as an external-account token created client-side.
 */
public record PayoutSection(String accountHolderName, String accountType, String bankName, String routingLast4,
                            String accountLast4, boolean externalAccountLinked) {
}
