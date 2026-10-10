package com.afrotransact.seller.domain;

/** How a subscription is paid: ACH debit from a US bank account, or a card (tokenised client-side). */
public enum PaymentMethodKind {
    US_BANK_ACCOUNT,
    CARD
}
