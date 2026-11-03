package com.afrotransact.seller.domain;

/**
 * Step "payouts": the payout bank account Stripe holds for the connected account, mirrored read-only. Stripe collects
 * and changes it in its embedded components (the account is Stripe-collected, so the platform can't attach one);
 * only the bank name, last 4 digits and Stripe's status are kept.
 *
 * @param accountHolderName     legacy (applications that typed their bank before Stripe collected it)
 * @param externalAccountLinked Stripe holds a usable payout account
 * @param bankStatus            Stripe's status: {@code new | validated | verified | verification_failed | errored}
 */
public record PayoutSection(String accountHolderName, String accountType, String bankName, String routingLast4,
                            String accountLast4, boolean externalAccountLinked, String bankStatus) {

    public PayoutSection(String accountHolderName, String accountType, String bankName, String routingLast4,
                         String accountLast4, boolean externalAccountLinked) {
        this(accountHolderName, accountType, bankName, routingLast4, accountLast4, externalAccountLinked, null);
    }

    /** The payout account as Stripe reports it; null when Stripe holds none (an earlier mirror is dropped). */
    public static PayoutSection fromStripe(PayoutBank bank) {
        if (bank == null) {
            return null;
        }
        return new PayoutSection(null, null, bank.bankName(), null, bank.last4(), bank.usable(), bank.status());
    }

    public boolean failed() {
        return "verification_failed".equals(bankStatus) || "errored".equals(bankStatus);
    }
}
