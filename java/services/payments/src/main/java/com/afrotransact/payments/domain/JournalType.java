package com.afrotransact.payments.domain;

/** Business event a journal records. */
public enum JournalType {
    CAPTURE,
    REFUND,
    REFUND_SETTLED,
    TRANSFER,
    TRANSFER_REVERSAL,
    DISPUTE_OPENED,
    DISPUTE_WON,
    DISPUTE_LOST,
    PROVIDER_FEE,
    PROVIDER_FEE_COLLECTED,
    LABEL_CHARGE,
    LABEL_CREDIT,
    LABEL_RECOVERY,
    /** A seller plan invoice withheld from earnings (S298): Dr 2000:org (no order) / Cr 4600. */
    PLAN_FEE_WITHHELD,
    /** A withheld plan fee credited back (voided or refunded invoice): Dr 4600 / Cr 2000:org (no order). */
    PLAN_FEE_RELEASED,
    /** A withheld plan fee recovered from (or a released one paid back with) an order's transfer. */
    PLAN_FEE_SETTLED,
    /** AfroTransact Retail supplier invoices released for payment (retail.payment.released, S226). */
    SUPPLIER_PAYABLE,
    /** A released supplier payment paid from the operating bank account. */
    SUPPLIER_PAYMENT,
    /** A correcting journal proposed and approved by Finance staff (ledger adjustment). */
    ADJUSTMENT,
    /** Credit granted by staff, promotion, referral or membership: Dr 5200 / Cr 2600 (S320). */
    CREDIT_GRANT,
    /** A staff correction that takes credit back: Dr 2600 / Cr 5200. */
    CREDIT_ADJUSTMENT,
    /** A gift card bought with a card: Dr 1000, Dr 5000 / Cr 2650 (S321). */
    GIFT_CARD_SOLD,
    /** A gift card issued by staff: Dr 5200 / Cr 2650. */
    GIFT_CARD_ISSUED,
    /** A gift card redeemed into credit: Dr 2650 / Cr 2600. */
    GIFT_CARD_REDEEMED,
    /** An unredeemed gift card voided: Dr 2650 / Cr 1000 (bought, refunded to the card) or Cr 5200 (issued). */
    GIFT_CARD_VOIDED,
    /** A membership fee charged to a shopper's card: Dr 1000, Dr 5000 / Cr 4700 (S323). */
    MEMBERSHIP_CHARGE,
    /** A membership fee refunded to the card: Dr 4700 / Cr 1000 (S323). */
    MEMBERSHIP_REFUND,
    /**
     * Stripe refused to reverse a seller's transfer for a refund: the debit moves from the order's payable to the
     * organisation's unattributed payable, which goes negative (the seller owes the platform).
     */
    TRANSFER_RECOVERY_OPENED,
    /** A later transfer of the seller recovered some of it: Dr 2000:org on the order / Cr 2000:org unattributed. */
    TRANSFER_RECOVERY_SETTLED,
    /** The seller paid the platform back outside Stripe: Dr 1050 operating bank / Cr 2000:org. */
    TRANSFER_RECOVERY_RECEIVED,
    /** Finance wrote the rest off: Dr 5300 / Cr 2000:org. */
    TRANSFER_RECOVERY_WRITE_OFF
}
