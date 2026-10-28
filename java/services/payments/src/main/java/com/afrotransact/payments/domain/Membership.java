package com.afrotransact.payments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A shopper's membership (S323): one live subscription per customer, billed on a saved card or complimentary for the
 * days a program granted. A period is paid for in advance; cancelling or switching to a lower tier takes effect at the
 * end of the period the member paid for. Failed renewals are retried on the staff-set schedule, then the membership
 * lapses.
 *
 * @param priceMinor        what the member pays each period (locked at sign-up or at the last plan change)
 * @param trialEnd          set while (and after) a trial; the first charge is at this moment
 * @param cancelAtPeriodEnd the member canceled: the benefits run to {@code periodEnd} and no charge follows
 * @param pendingTierCode   a lower tier (or another interval) the member chose, applied at {@code periodEnd}
 * @param paymentMethodId   the saved card billed (pm_...), null for complimentary memberships
 * @param retryCount        failed renewal attempts of the current period
 * @param freeDaysGranted   complimentary days added to this membership by programs
 */
public record Membership(UUID id, String customerId, String tierCode, BillingInterval interval,
                         MembershipSource source, MembershipStatus status, long priceMinor, String currency,
                         Instant startedAt, Instant periodStart, Instant periodEnd, Instant trialEnd,
                         boolean cancelAtPeriodEnd, Instant canceledAt, Instant endedAt, String endReason,
                         String pendingTierCode, BillingInterval pendingInterval, String paymentMethodId,
                         int retryCount, Instant nextRetryAt, int freeDaysGranted, Instant createdAt,
                         Instant updatedAt, long version) {

    public static final String ENDED_CANCELED = "CANCELED";
    public static final String ENDED_REFUNDED = "REFUNDED";
    public static final String ENDED_PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String ENDED_FREE_DAYS_OVER = "FREE_DAYS_OVER";

    public Membership {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(tierCode, "tierCode");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
    }

    // ---- creation ------------------------------------------------------------------------------------------------

    /** A paid membership whose first period was just charged. */
    public static Membership paid(UUID id, String customerId, MembershipTier tier, BillingInterval interval,
                                  String paymentMethodId, Instant now) {
        return new Membership(id, customerId, tier.code(), interval, MembershipSource.PAID, MembershipStatus.ACTIVE,
                tier.priceMinor(interval), tier.currency(), now, now, interval.periodEnd(now), null, false, null,
                null, null, null, null, paymentMethodId, 0, null, 0, now, now, 0);
    }

    /** A trial: no charge now, the first charge at the end of the trial. */
    public static Membership trial(UUID id, String customerId, MembershipTier tier, BillingInterval interval,
                                   String paymentMethodId, Instant now) {
        Instant end = now.plus(Duration.ofDays(tier.trialDays()));
        return new Membership(id, customerId, tier.code(), interval, MembershipSource.PAID,
                MembershipStatus.TRIALING, tier.priceMinor(interval), tier.currency(), now, now, end, end, false,
                null, null, null, null, null, paymentMethodId, 0, null, 0, now, now, 0);
    }

    /** Free days from a program: no card, no charge, ends on its date. */
    public static Membership complimentary(UUID id, String customerId, MembershipTier tier, int days, Instant now) {
        return new Membership(id, customerId, tier.code(), BillingInterval.MONTHLY, MembershipSource.COMPLIMENTARY,
                MembershipStatus.ACTIVE, 0, tier.currency(), now, now, now.plus(Duration.ofDays(days)), null, false,
                null, null, null, null, null, null, 0, null, days, now, now, 0);
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    public boolean live() {
        return status.live();
    }

    /** Whether the member has the benefits that are not credit: delivery, early access, support, returns. */
    public boolean benefitsActive() {
        return status.live();
    }

    /** Credit benefits (cashback, monthly credit) start with the first paid period, never during a trial. */
    public boolean creditBenefitsActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public boolean paidByCard() {
        return source == MembershipSource.PAID;
    }

    public boolean dueAt(Instant now) {
        if (!status.live() || periodEnd.isAfter(now)) {
            return false;
        }
        return status != MembershipStatus.PAST_DUE || nextRetryAt == null || !nextRetryAt.isAfter(now);
    }

    // ---- transitions ---------------------------------------------------------------------------------------------

    /**
     * A renewal was paid: the next period starts where the last ended, at the price that was charged (the member's
     * locked price, or the new tier's when a scheduled change just applied).
     */
    public Membership renewed(String nextTierCode, BillingInterval nextInterval, long chargedMinor, Instant now) {
        Instant start = periodEnd;
        return copy().tierCode(nextTierCode).interval(nextInterval).status(MembershipStatus.ACTIVE)
                .priceMinor(chargedMinor).periodStart(start).periodEnd(nextInterval.periodEnd(start))
                .pending(null, null).retryCount(0).nextRetryAt(null).updatedAt(now).build();
    }

    /** A renewal charge failed. After the last retry the membership lapses. */
    public Membership renewalFailed(List<Integer> retryDays, Instant now) {
        int failures = retryCount + 1;
        if (failures > retryDays.size()) {
            return ended(MembershipStatus.LAPSED, ENDED_PAYMENT_FAILED, now).copy().retryCount(failures).build();
        }
        return copy().status(MembershipStatus.PAST_DUE).retryCount(failures)
                .nextRetryAt(now.plus(Duration.ofDays(retryDays.get(failures - 1)))).updatedAt(now).build();
    }

    /** The member canceled: nothing more is charged, the benefits run to the end of the period. */
    public Membership canceled(Instant now) {
        return copy().cancelAtPeriodEnd(true).canceledAt(now).pending(null, null).updatedAt(now).build();
    }

    /** The member changed their mind before the period ended. */
    public Membership resumed(Instant now) {
        return copy().cancelAtPeriodEnd(false).canceledAt(null).updatedAt(now).build();
    }

    /** A lower tier or another interval, applied when the paid period ends. */
    public Membership changeScheduled(String tier, BillingInterval nextInterval, Instant now) {
        return copy().pending(tier, nextInterval).cancelAtPeriodEnd(false).canceledAt(null).updatedAt(now).build();
    }

    public Membership changeCleared(Instant now) {
        return copy().pending(null, null).updatedAt(now).build();
    }

    /** An upgrade takes effect now, in the period already paid (the difference was charged). */
    public Membership upgradedInPeriod(MembershipTier tier, Instant now) {
        return copy().tierCode(tier.code()).priceMinor(tier.priceMinor(interval)).currency(tier.currency())
                .pending(null, null).cancelAtPeriodEnd(false).canceledAt(null).updatedAt(now).build();
    }

    /** An upgrade that also changes the interval starts a new period now. */
    public Membership upgradedNewPeriod(MembershipTier tier, BillingInterval nextInterval, Instant now) {
        return copy().tierCode(tier.code()).interval(nextInterval).priceMinor(tier.priceMinor(nextInterval))
                .currency(tier.currency()).periodStart(now).periodEnd(nextInterval.periodEnd(now))
                .status(MembershipStatus.ACTIVE).trialEnd(trialEnd).pending(null, null).cancelAtPeriodEnd(false)
                .canceledAt(null).retryCount(0).nextRetryAt(null).updatedAt(now).build();
    }

    /** A trial member moved to another tier: the trial and the first charge date stay, the price follows the tier. */
    public Membership trialSwitched(MembershipTier tier, BillingInterval nextInterval, Instant now) {
        return copy().tierCode(tier.code()).interval(nextInterval).priceMinor(tier.priceMinor(nextInterval))
                .currency(tier.currency()).pending(null, null).cancelAtPeriodEnd(false).canceledAt(null)
                .updatedAt(now).build();
    }

    public Membership ended(MembershipStatus finalStatus, String reason, Instant now) {
        return copy().status(finalStatus).endedAt(now).endReason(reason).pending(null, null).nextRetryAt(null)
                .updatedAt(now).build();
    }

    /** Free days added to the current period (and to the trial, which would otherwise end before the period). */
    public Membership extended(int days, Instant now) {
        Instant end = periodEnd.plus(Duration.ofDays(days));
        return copy().periodEnd(end).trialEnd(trialEnd == null ? null : trialEnd.plus(Duration.ofDays(days)))
                .freeDaysGranted(freeDaysGranted + days).updatedAt(now).build();
    }

    /** A complimentary membership continues as a paid one on the member's card once the free days are over. */
    public Membership convertedToPaid(MembershipTier tier, BillingInterval nextInterval, String method, Instant now) {
        return copy().source(MembershipSource.PAID).tierCode(tier.code()).interval(nextInterval)
                .priceMinor(tier.priceMinor(nextInterval)).currency(tier.currency()).paymentMethodId(method)
                .updatedAt(now).build();
    }

    public Membership withPaymentMethod(String method, Instant now) {
        return copy().paymentMethodId(method).updatedAt(now).build();
    }

    // ---- copy ----------------------------------------------------------------------------------------------------

    private Builder copy() {
        return new Builder(this);
    }

    /** Field-by-field copy for the transitions above. */
    private static final class Builder {

        private UUID id;
        private String customerId;
        private String tierCode;
        private BillingInterval interval;
        private MembershipSource source;
        private MembershipStatus status;
        private long priceMinor;
        private String currency;
        private Instant startedAt;
        private Instant periodStart;
        private Instant periodEnd;
        private Instant trialEnd;
        private boolean cancelAtPeriodEnd;
        private Instant canceledAt;
        private Instant endedAt;
        private String endReason;
        private String pendingTierCode;
        private BillingInterval pendingInterval;
        private String paymentMethodId;
        private int retryCount;
        private Instant nextRetryAt;
        private int freeDaysGranted;
        private Instant createdAt;
        private Instant updatedAt;
        private long version;

        Builder(Membership m) {
            this.id = m.id;
            this.customerId = m.customerId;
            this.tierCode = m.tierCode;
            this.interval = m.interval;
            this.source = m.source;
            this.status = m.status;
            this.priceMinor = m.priceMinor;
            this.currency = m.currency;
            this.startedAt = m.startedAt;
            this.periodStart = m.periodStart;
            this.periodEnd = m.periodEnd;
            this.trialEnd = m.trialEnd;
            this.cancelAtPeriodEnd = m.cancelAtPeriodEnd;
            this.canceledAt = m.canceledAt;
            this.endedAt = m.endedAt;
            this.endReason = m.endReason;
            this.pendingTierCode = m.pendingTierCode;
            this.pendingInterval = m.pendingInterval;
            this.paymentMethodId = m.paymentMethodId;
            this.retryCount = m.retryCount;
            this.nextRetryAt = m.nextRetryAt;
            this.freeDaysGranted = m.freeDaysGranted;
            this.createdAt = m.createdAt;
            this.updatedAt = m.updatedAt;
            this.version = m.version;
        }

        Builder tierCode(String v) {
            this.tierCode = v;
            return this;
        }

        Builder interval(BillingInterval v) {
            this.interval = v;
            return this;
        }

        Builder source(MembershipSource v) {
            this.source = v;
            return this;
        }

        Builder status(MembershipStatus v) {
            this.status = v;
            return this;
        }

        Builder priceMinor(long v) {
            this.priceMinor = v;
            return this;
        }

        Builder currency(String v) {
            this.currency = v;
            return this;
        }

        Builder periodStart(Instant v) {
            this.periodStart = v;
            return this;
        }

        Builder periodEnd(Instant v) {
            this.periodEnd = v;
            return this;
        }

        Builder trialEnd(Instant v) {
            this.trialEnd = v;
            return this;
        }

        Builder cancelAtPeriodEnd(boolean v) {
            this.cancelAtPeriodEnd = v;
            return this;
        }

        Builder canceledAt(Instant v) {
            this.canceledAt = v;
            return this;
        }

        Builder endedAt(Instant v) {
            this.endedAt = v;
            return this;
        }

        Builder endReason(String v) {
            this.endReason = v;
            return this;
        }

        Builder pending(String tier, BillingInterval nextInterval) {
            this.pendingTierCode = tier;
            this.pendingInterval = nextInterval;
            return this;
        }

        Builder paymentMethodId(String v) {
            this.paymentMethodId = v;
            return this;
        }

        Builder retryCount(int v) {
            this.retryCount = v;
            return this;
        }

        Builder nextRetryAt(Instant v) {
            this.nextRetryAt = v;
            return this;
        }

        Builder freeDaysGranted(int v) {
            this.freeDaysGranted = v;
            return this;
        }

        Builder updatedAt(Instant v) {
            this.updatedAt = v;
            return this;
        }

        Membership build() {
            return new Membership(id, customerId, tierCode, interval, source, status, priceMinor, currency, startedAt,
                    periodStart, periodEnd, trialEnd, cancelAtPeriodEnd, canceledAt, endedAt, endReason,
                    pendingTierCode, pendingInterval, paymentMethodId, retryCount, nextRetryAt, freeDaysGranted,
                    createdAt, updatedAt, version);
        }
    }
}
