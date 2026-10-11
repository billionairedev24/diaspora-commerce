package com.afrotransact.seller.port;

/**
 * A US bank account for payouts, sent once to Stripe as the connected account's external account and never stored:
 * the application keeps only the last 4 digits. {@code toString} masks the numbers so they never reach a log.
 *
 * @param holderType {@code individual} or {@code company}
 */
public record BankAccountDetails(String holderName, String holderType, String routingNumber, String accountNumber) {

    @Override
    public String toString() {
        return "BankAccountDetails[holderType=" + holderType + ", routing=•••••" + last4(routingNumber)
                + ", account=••" + last4(accountNumber) + "]";
    }

    private static String last4(String s) {
        return s == null || s.length() < 4 ? "" : s.substring(s.length() - 4);
    }
}
