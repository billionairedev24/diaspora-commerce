package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.MembershipCharger.Outcome;
import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.BillingInterval;
import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipBenefitUse;
import com.afrotransact.payments.domain.MembershipCharge;
import com.afrotransact.payments.domain.MembershipEvent;
import com.afrotransact.payments.domain.MembershipSettings;
import com.afrotransact.payments.domain.MembershipStatus;
import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.Proration;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.payments.port.MembershipActivityRepository;
import com.afrotransact.payments.port.MembershipChargeRepository;
import com.afrotransact.payments.port.MembershipRepository;
import com.afrotransact.payments.port.MembershipSettingsRepository;
import com.afrotransact.payments.port.MembershipTierRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.RefundSnapshot;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class MembershipServiceImpl implements MembershipService {

    private static final String SCOPE = "membership";
    private static final int MAX_LIST = 200;

    private final MembershipRepository memberships;
    private final MembershipTierRepository tiers;
    private final MembershipChargeRepository charges;
    private final MembershipActivityRepository activity;
    private final MembershipSettingsRepository settings;
    private final MembershipCharger charger;
    private final MembershipCredits credits;
    private final MembershipRecorder recorder;
    private final PaymentMethodService paymentMethods;
    private final StripeGateway stripe;
    private final LedgerPoster poster;
    private final IdempotencyStore idempotency;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    MembershipServiceImpl(MembershipRepository memberships, MembershipTierRepository tiers,
                          MembershipChargeRepository charges, MembershipActivityRepository activity,
                          MembershipSettingsRepository settings, MembershipCharger charger,
                          MembershipCredits credits, MembershipRecorder recorder, PaymentMethodService paymentMethods,
                          StripeGateway stripe, LedgerPoster poster, IdempotencyStore idempotency,
                          MoneyTransactions tx, PaymentsAccess access, Clock clock) {
        this.memberships = memberships;
        this.tiers = tiers;
        this.charges = charges;
        this.activity = activity;
        this.settings = settings;
        this.charger = charger;
        this.credits = credits;
        this.recorder = recorder;
        this.paymentMethods = paymentMethods;
        this.stripe = stripe;
        this.poster = poster;
        this.idempotency = idempotency;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    @Override
    public MemberView me() {
        return view(access.customer().subject());
    }

    @Override
    public MemberView ofCustomer(String customerId) {
        access.reader();
        return view(requireCustomer(customerId));
    }

    @Override
    public List<MembershipBenefitUse> myUses(int limit) {
        return activity.usesOf(access.customer().subject(), Math.max(1, Math.min(limit, MAX_LIST)));
    }

    @Override
    public List<Membership> members(String tierCode, MembershipStatus status, Instant before, int limit) {
        access.reader();
        return memberships.list(tierCode == null || tierCode.isBlank() ? null : tierCode.trim(), status, before,
                Math.max(1, Math.min(limit, MAX_LIST)));
    }

    @Override
    public List<MembershipEvent> timeline(UUID membershipId) {
        access.reader();
        return activity.eventsOf(membershipId);
    }

    private MemberView view(String customerId) {
        Instant now = Instant.now(clock);
        MembershipSettings rules = settings.get();
        Optional<Membership> found = memberships.liveOf(customerId)
                .or(() -> memberships.historyOf(customerId, 1).stream().findFirst());
        boolean trialAvailable = !activity.hadMembership(customerId);
        if (found.isEmpty()) {
            return new MemberView(null, null, null, List.of(), List.of(), new RefundRule(false, "NOT_PAID", 0, "USD",
                    null), trialAvailable, null, rules.refundWindowDays());
        }
        Membership m = found.get();
        MembershipTier tier = tiers.find(m.tierCode()).orElse(null);
        MembershipTier pending = m.pendingTierCode() == null ? null : tiers.find(m.pendingTierCode()).orElse(null);
        List<MembershipCharge> recent = charges.ofMembership(m.id(), 20);
        Card card = m.paymentMethodId() == null ? null : charger.cardOf(customerId, m.paymentMethodId())
                .map(c -> new Card(c.id(), c.brand(), c.last4(), c.expMonth(), c.expYear())).orElse(null);
        return new MemberView(m, tier, pending, recent, usage(customerId), refundRule(m, rules, now), trialAvailable,
                card, rules.refundWindowDays());
    }

    private List<BenefitUsage> usage(String customerId) {
        Map<BenefitCode, long[]> sums = new EnumMap<>(BenefitCode.class);
        String currency = "USD";
        for (MembershipBenefitUse u : activity.usesOf(customerId, MAX_LIST)) {
            long[] s = sums.computeIfAbsent(u.benefit(), k -> new long[2]);
            s[0]++;
            s[1] += u.amount().minor();
            currency = u.amount().currency();
        }
        String cur = currency;
        return sums.entrySet().stream().map(e -> new BenefitUsage(e.getKey(), e.getValue()[0], e.getValue()[1], cur))
                .toList();
    }

    /** The refund rule at this moment for this membership (see {@link RefundRule}). */
    private RefundRule refundRule(Membership m, MembershipSettings rules, Instant now) {
        Optional<MembershipCharge> latest = m.live() ? charges.latestPaid(m.id()) : Optional.empty();
        if (latest.isEmpty()) {
            return new RefundRule(false, "NOT_PAID", 0, m.currency(), null);
        }
        MembershipCharge c = latest.get();
        Instant until = c.createdAt().plus(Duration.ofDays(rules.refundWindowDays()));
        if (rules.refundWindowDays() == 0) {
            return new RefundRule(false, "DISABLED", c.amount().minor(), m.currency(), until);
        }
        if (c.kind() == MembershipCharge.Kind.UPGRADE) {
            // an upgrade top-up is not refunded on its own; the period fee that came before it decides
            return new RefundRule(false, "NOT_PAID", 0, m.currency(), null);
        }
        if (now.isAfter(until)) {
            return new RefundRule(false, "WINDOW_PASSED", c.amount().minor(), m.currency(), until);
        }
        if (activity.anyUseSince(m.id(), c.createdAt())) {
            return new RefundRule(false, "BENEFIT_USED", c.amount().minor(), m.currency(), until);
        }
        return new RefundRule(true, "OK", c.amount().minor(), m.currency(), until);
    }

    // ------------------------------------------------------------------ join

    @Override
    public MemberView join(JoinCommand c, String key) {
        Actor actor = access.customer();
        String customer = actor.subject();
        requireKey(key);
        if (c == null || c.tierCode() == null || c.interval() == null) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Choose a tier and monthly or yearly",
                    Map.of("tierCode", "Required", "interval", "Required"));
        }
        SavedPaymentMethod card = cardOf(customer, c.paymentMethodId());
        tx.serializable(() -> {
            Instant now = Instant.now(clock);
            String fingerprint = Fingerprint.of(customer + "|" + c);
            if (idempotency.claim(SCOPE, "join:" + customer + ":" + key, fingerprint, StoredRef.class).isPresent()) {
                return null;
            }
            MembershipTier tier = joinableTier(c.tierCode());
            Optional<Membership> live = memberships.lockLiveOf(customer);
            UUID id = Fingerprint.id("membership", customer, key);
            if (live.isPresent()) {
                joinFromComplimentary(live.get(), tier, c.interval(), card, customer, now);
            } else if (trialOpen(tier, customer, card, id, now)) {
                Membership m = Membership.trial(id, customer, tier, c.interval(), card.id(), now);
                memberships.insert(m);
                recorder.record(m, MembershipEvent.Type.TRIAL_STARTED, customer, "Trial of " + tier.trialDays()
                        + " days", 0);
            } else {
                Membership m = Membership.paid(id, customer, tier, c.interval(), card.id(), now);
                memberships.insert(m);
                Outcome outcome = charger.charge(new MembershipCharger.Request(m, MembershipCharge.Kind.JOIN, 1,
                        tier.price(c.interval()), m.periodStart(), m.periodEnd(), "membership:" + id + ":join"), false);
                requirePaid(outcome);
                recorder.record(m, MembershipEvent.Type.JOINED, customer, tier.name() + " " + c.interval(),
                        m.priceMinor());
                credits.monthly(m, tier, now);
            }
            idempotency.complete(SCOPE, "join:" + customer + ":" + key, new StoredRef(id));
            return null;
        });
        return view(customer);
    }

    /** A complimentary member who joins continues on their card when the free days are over: nothing is charged now. */
    private void joinFromComplimentary(Membership live, MembershipTier tier, BillingInterval interval,
                                       SavedPaymentMethod card, String customer, Instant now) {
        if (live.paidByCard()) {
            throw new PaymentsStateConflict("payments.membership-exists", "You are already a member; change your "
                    + "plan instead");
        }
        Membership m = memberships.update(live.convertedToPaid(tier, interval, card.id(), now));
        recorder.record(m, MembershipEvent.Type.CONVERTED_TO_PAID, customer, tier.name() + " " + interval
                + " from " + m.periodEnd(), 0);
    }

    /**
     * The abuse guard: a trial is for a first-time member (no earlier membership or trial of any tier) on a card that
     * never started one. Claims the trial for this customer and card when it is open.
     */
    private boolean trialOpen(MembershipTier tier, String customer, SavedPaymentMethod card, UUID id, Instant now) {
        return tier.trialDays() > 0 && !activity.hadMembership(customer)
                && activity.claimTrial(customer, cardKey(card), id, now);
    }

    // ------------------------------------------------------------------ change

    @Override
    public MemberView change(JoinCommand c, String key) {
        String customer = access.customer().subject();
        requireKey(key);
        if (c == null || c.tierCode() == null || c.interval() == null) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Choose a tier and monthly or yearly",
                    Map.of("tierCode", "Required", "interval", "Required"));
        }
        tx.serializable(() -> {
            Instant now = Instant.now(clock);
            String idemKey = "change:" + customer + ":" + key;
            if (idempotency.claim(SCOPE, idemKey, Fingerprint.of(customer + "|" + c), StoredRef.class).isPresent()) {
                return null;
            }
            Membership m = liveOrThrow(customer);
            requireCardMember(m);
            MembershipTier current = tiers.find(m.tierCode()).orElseThrow();
            MembershipTier target = joinableOrCurrent(c.tierCode(), m);
            if (target.code().equals(m.tierCode()) && c.interval() == m.interval()) {
                if (m.pendingTierCode() == null) {
                    throw new PaymentsStateConflict("payments.membership-no-change", "That is your current plan");
                }
                Membership cleared = memberships.update(m.changeCleared(now));
                recorder.record(cleared, MembershipEvent.Type.CANCEL_RESUMED, customer, "Kept the current plan", 0);
            } else if (m.status() == MembershipStatus.TRIALING) {
                Membership switched = memberships.update(m.trialSwitched(target, c.interval(), now));
                recorder.record(switched, target.sortOrder() > current.sortOrder() ? MembershipEvent.Type.UPGRADED
                        : MembershipEvent.Type.DOWNGRADED, customer, "Trial moved to " + target.name(), 0);
            } else if (m.status() == MembershipStatus.PAST_DUE) {
                throw new PaymentsStateConflict("payments.membership-past-due", "Your last payment failed; update "
                        + "your card and wait for the retry before changing plan");
            } else if (target.sortOrder() > current.sortOrder()) {
                upgrade(m, target, c.interval(), customer, key, now);
            } else {
                Membership scheduled = memberships.update(m.changeScheduled(target.code(), c.interval(), now));
                recorder.record(scheduled, MembershipEvent.Type.DOWNGRADE_SCHEDULED, customer, target.name() + " "
                        + c.interval() + " from " + m.periodEnd(), 0);
            }
            idempotency.complete(SCOPE, idemKey, new StoredRef(m.id()));
            return null;
        });
        return view(customer);
    }

    private void upgrade(Membership m, MembershipTier target, BillingInterval interval, String customer, String key,
                         Instant now) {
        boolean sameInterval = interval == m.interval();
        long amount = Proration.upgradeCharge(m.priceMinor(), target.priceMinor(interval), sameInterval,
                m.periodStart(), m.periodEnd(), now);
        Membership upgraded = sameInterval ? m.upgradedInPeriod(target, now) : m.upgradedNewPeriod(target, interval,
                now);
        if (amount > 0) {
            Outcome outcome = charger.charge(new MembershipCharger.Request(m, MembershipCharge.Kind.UPGRADE, 1,
                    Money.of(amount, target.currency()), upgraded.periodStart(), upgraded.periodEnd(),
                    "membership:" + m.id() + ":upgrade:" + key), false);
            requirePaid(outcome);
        }
        Membership stored = memberships.update(upgraded);
        recorder.record(stored, MembershipEvent.Type.UPGRADED, customer, target.name() + " " + interval, amount);
        credits.monthly(stored, target, now);
    }

    // ------------------------------------------------------------------ cancel, resume

    @Override
    public MemberView cancel(boolean refund) {
        String customer = access.customer().subject();
        tx.serializable(() -> {
            Instant now = Instant.now(clock);
            Membership m = liveOrThrow(customer);
            if (!m.paidByCard()) {
                throw new PaymentsStateConflict("payments.membership-complimentary", "Your complimentary days end on "
                        + m.periodEnd() + "; there is nothing to cancel");
            }
            if (refund) {
                RefundRule rule = refundRule(m, settings.get(), now);
                if (!rule.eligible()) {
                    throw new PaymentsStateConflict("payments.membership-refund-not-eligible",
                            "The fee can no longer be refunded (" + rule.reason() + "); you can cancel and keep "
                            + "your benefits until the period ends");
                }
                MembershipCharge latest = charges.latestPaid(m.id()).orElseThrow();
                refundCharge(charges.lock(latest.id()).orElseThrow(), "Member canceled within the refund window",
                        customer);
                Membership ended = memberships.update(m.ended(MembershipStatus.CANCELED, Membership.ENDED_REFUNDED,
                        now));
                recorder.record(ended, MembershipEvent.Type.ENDED, customer, "Canceled with a refund", 0);
            } else if (!m.cancelAtPeriodEnd()) {
                Membership canceled = memberships.update(m.canceled(now));
                recorder.record(canceled, MembershipEvent.Type.CANCEL_SCHEDULED, customer, "Benefits until "
                        + m.periodEnd(), 0);
            }
            return null;
        });
        return view(customer);
    }

    @Override
    public MemberView resume() {
        String customer = access.customer().subject();
        tx.serializable(() -> {
            Instant now = Instant.now(clock);
            Membership m = liveOrThrow(customer);
            if (m.cancelAtPeriodEnd()) {
                recorder.record(memberships.update(m.resumed(now)), MembershipEvent.Type.CANCEL_RESUMED, customer,
                        "Kept the membership", 0);
            } else if (m.pendingTierCode() != null) {
                recorder.record(memberships.update(m.changeCleared(now)), MembershipEvent.Type.CANCEL_RESUMED,
                        customer, "Kept the current plan", 0);
            } else {
                throw new PaymentsStateConflict("payments.membership-no-change", "There is nothing to take back");
            }
            return null;
        });
        return view(customer);
    }

    // ------------------------------------------------------------------ refunds

    @Override
    public MembershipCharge refund(UUID chargeId, String reason) {
        Actor actor = access.configure();
        if (reason == null || reason.trim().length() < MembershipTierServiceImpl.MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why (at least "
                    + MembershipTierServiceImpl.MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        return tx.serializable(() -> {
            MembershipCharge charge = charges.lock(chargeId).orElseThrow(() -> new NotFoundException(
                    "payments.membership-charge-not-found", "No membership charge " + chargeId));
            MembershipCharge refunded = refundCharge(charge, reason.trim(), actor.subject());
            memberships.lock(charge.membershipId()).ifPresent(m -> {
                boolean paysCurrentPeriod = m.live() && charge.kind() != MembershipCharge.Kind.UPGRADE
                        && charge.periodEnd() != null && !charge.periodEnd().isBefore(m.periodEnd());
                if (paysCurrentPeriod) {
                    recorder.record(memberships.update(m.ended(MembershipStatus.CANCELED, Membership.ENDED_REFUNDED,
                            Instant.now(clock))), MembershipEvent.Type.ENDED, actor.subject(), "Staff refund: "
                            + reason.trim(), 0);
                }
            });
            return refunded;
        });
    }

    /** Refunds the whole fee to the card it was charged on and books it. Call with the charge locked. */
    private MembershipCharge refundCharge(MembershipCharge charge, String reason, String by) {
        if (charge.status() != MembershipCharge.Status.SUCCEEDED) {
            throw new PaymentsStateConflict("payments.membership-charge-not-refundable", "This charge is "
                    + charge.status() + " and cannot be refunded");
        }
        Instant now = Instant.now(clock);
        RefundSnapshot snapshot = stripe.refund(charge.paymentIntentId(), charge.amount(), Map.of("kind",
                "membership", "membership_charge", charge.id().toString()), "membership-refund:" + charge.id());
        MembershipCharge refunded = charges.update(charge.refunded(snapshot.id(), reason, by, now));
        poster.post(Postings.membershipRefunded(Fingerprint.id("journal", "membership-refund",
                charge.id().toString()), now, "membership-refund:" + charge.id(), refunded, snapshot.id()),
                "membership:" + charge.membershipId());
        memberships.find(charge.membershipId()).ifPresent(m -> recorder.record(m, MembershipEvent.Type.REFUNDED,
                by, reason, charge.amount().minor()));
        return refunded;
    }

    // ------------------------------------------------------------------ helpers

    private SavedPaymentMethod cardOf(String customer, String requested) {
        Optional<SavedPaymentMethod> card = charger.cardOf(customer, requested);
        if (card.isEmpty()) {
            // the local copy of the wallet may be behind Stripe (a card saved a moment ago): read it once
            paymentMethods.list();
            card = charger.cardOf(customer, requested);
        }
        return card.orElseThrow(() -> new PaymentsRuleViolation("payments.no-saved-method",
                "Save a card in Your Payments first; membership is billed to a saved card",
                Map.of("paymentMethodId", "No saved card")));
    }

    private static String cardKey(SavedPaymentMethod card) {
        return Fingerprint.of("card|" + card.brand() + "|" + card.last4() + "|" + card.expMonth() + "|"
                + card.expYear());
    }

    private MembershipTier joinableTier(String code) {
        MembershipTier tier = tiers.find(code).orElseThrow(() -> new NotFoundException(
                "payments.membership-tier-not-found", "No membership tier " + code));
        if (!tier.joinable()) {
            throw new PaymentsStateConflict("payments.membership-tier-unavailable", tier.name() + " is not open "
                    + "to new members");
        }
        return tier;
    }

    /** The tier a member moves to: a published tier, or the one they are on (to undo a scheduled change). */
    private MembershipTier joinableOrCurrent(String code, Membership m) {
        if (code.equals(m.tierCode())) {
            return tiers.find(code).orElseThrow();
        }
        return joinableTier(code);
    }

    private Membership liveOrThrow(String customer) {
        return memberships.lockLiveOf(customer).orElseThrow(() -> new NotFoundException(
                "payments.membership-not-found", "You are not a member"));
    }

    private static void requireCardMember(Membership m) {
        if (!m.paidByCard()) {
            throw new PaymentsStateConflict("payments.membership-complimentary", "Your membership is on "
                    + "complimentary days; join to continue it on your card");
        }
    }

    private static void requirePaid(Outcome outcome) {
        if (outcome.paid()) {
            return;
        }
        if (outcome.authenticationRequired()) {
            throw new PaymentsStateConflict("payments.membership-authentication-required", "Your bank needs you "
                    + "to confirm this payment; try another card");
        }
        throw new PaymentsRuleViolation("payments.membership-charge-failed", "The card was not charged"
                + (outcome.message() == null ? "" : ": " + outcome.message()), Map.of("paymentMethodId",
                outcome.code() == null ? "Declined" : outcome.code()));
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required", "This needs an Idempotency-Key",
                    Map.of("idempotencyKey", "Required"));
        }
    }

    private static String requireCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Name the customer",
                    Map.of("customerId", "Required"));
        }
        return customerId.trim();
    }
}
