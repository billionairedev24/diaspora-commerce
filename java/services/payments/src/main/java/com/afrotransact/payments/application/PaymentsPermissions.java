package com.afrotransact.payments.application;

/** Permission keys checked by payments use cases (granted by identity-service roles and client scopes). */
public final class PaymentsPermissions {

    /** Staff: payment splits, ledger balances and journals, reconciliation runs (Finance, Support leads). */
    public static final String READ = "payments:read";
    /** Super Admin / Finance: new fee-schedule versions, connected accounts, manual reconciliation. */
    public static final String CONFIGURE = "payments:configure";
    /** Machine-to-machine (orders, bookings, fulfilment services; SERVICE principals): intents, captures, refunds,
     *  delivery notices. */
    public static final String SERVICE = "payments:service";
    /** Read-only fee schedules for service clients that show fees to users (gateway-bff); grants nothing else. */
    public static final String FEES_READ = "payments:fees.read";
    /**
     * The seller service (S298): withhold a plan invoice from an organisation's earnings and release it again; grants
     * nothing else.
     */
    public static final String PLAN_FEES = "payments:plan-fees";
    /** The seller service: map an organisation to its Stripe Connect account id for settlement transfers. */
    public static final String CONNECTED_ACCOUNTS = "payments:connected-accounts";
    /** Seller / provider owner and finance roles (org-scoped) or Finance staff: request a payout. */
    public static final String PAYOUTS_REQUEST = "payouts:request";
    /** Payouts and statements of the organisation (owner, admin, manager, finance; Finance staff). */
    public static final String PAYOUTS_READ = "payouts:read";
    /** Finance staff: approve, hold and release payout requests. */
    public static final String PAYOUTS_APPROVE = "payouts:approve";

    /** Finance staff: propose a correcting ledger adjustment and mark reconciliation differences explained. */
    public static final String LEDGER_ADJUST = "ledger:adjust";
    /** Finance staff: approve or reject a proposed ledger adjustment (never their own). */
    public static final String LEDGER_APPROVE = "ledger:approve";

    private PaymentsPermissions() {
    }
}
