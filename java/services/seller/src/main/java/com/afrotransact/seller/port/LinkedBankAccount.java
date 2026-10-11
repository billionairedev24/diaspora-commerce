package com.afrotransact.seller.port;

/** What Stripe reports for a bank account it accepted as the payout account. */
public record LinkedBankAccount(String bankName, String last4, String status) {
}
