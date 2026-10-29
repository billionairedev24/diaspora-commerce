package com.afrotransact.payments.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipCharge;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PaymentMethodType;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.SavedPaymentMethod;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.payments.port.MembershipChargeRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.BalanceTxn;
import com.afrotransact.payments.port.StripeGateway.IntentSnapshot;
import com.afrotransact.payments.port.StripeGateway.OffSessionAttempt;
import com.afrotransact.payments.port.WalletRepository;
import com.afrotransact.payments.port.WalletRepository.StripeCustomer;
import org.springframework.stereotype.Component;

/**
 * Charges the membership fee on the member's saved card (the plan billing machinery of seller plans, for shoppers):
 * an off-session PaymentIntent confirmed on the saved card and captured at once, the charge recorded and the revenue
 * journal posted. Every Stripe call carries an idempotency key derived from the membership, the period and the attempt,
 * so a retried transaction never charges twice. Runs inside the caller's SERIALIZABLE transaction.
 */
@Component
class MembershipCharger {

    private final StripeGateway stripe;
    private final WalletRepository wallet;
    private final MembershipChargeRepository charges;
    private final FeeScheduleRepository feeSchedules;
    private final LedgerPoster poster;
    private final Clock clock;

    MembershipCharger(StripeGateway stripe, WalletRepository wallet, MembershipChargeRepository charges,
                      FeeScheduleRepository feeSchedules, LedgerPoster poster, Clock clock) {
        this.stripe = stripe;
        this.wallet = wallet;
        this.charges = charges;
        this.feeSchedules = feeSchedules;
        this.poster = poster;
        this.clock = clock;
    }

    /**
     * What a charge attempt came to. {@code charge} is the stored row (a failed one only when the caller asked for
     * failures to be kept); {@code code} and {@code message} say why a charge failed.
     */
    record Outcome(boolean paid, MembershipCharge charge, String code, String message) {

        boolean authenticationRequired() {
            return "authentication_required".equals(code);
        }
    }

    /** What to charge for which period; {@code key} identifies this attempt for Stripe and the database. */
    record Request(Membership membership, MembershipCharge.Kind kind, int attempt, Money amount,
                   Instant periodStart, Instant periodEnd, String key) {
    }

    /** The customer's saved card to bill: the one named, else the Stripe Customer's default. */
    Optional<SavedPaymentMethod> cardOf(String customerId, String requestedMethodId) {
        Optional<StripeCustomer> customer = wallet.findCustomer(customerId);
        if (customer.isEmpty()) {
            return Optional.empty();
        }
        String methodId = requestedMethodId == null || requestedMethodId.isBlank()
                ? customer.get().defaultPaymentMethodId() : requestedMethodId;
        if (methodId == null) {
            return Optional.empty();
        }
        return wallet.findMethod(methodId).filter(m -> m.customerId().equals(customerId)
                && m.type() == PaymentMethodType.CARD && m.verified());
    }

    /** Charges the card; records the charge (a failure only when {@code keepFailure}). */
    Outcome charge(Request r, boolean keepFailure) {
        Optional<MembershipCharge> earlier = charges.findByKey(r.key());
        if (earlier.isPresent() && earlier.get().status() != MembershipCharge.Status.FAILED) {
            return new Outcome(true, earlier.get(), null, null);
        }
        Membership m = r.membership();
        Optional<SavedPaymentMethod> card = cardOf(m.customerId(), m.paymentMethodId());
        Optional<StripeCustomer> customer = wallet.findCustomer(m.customerId());
        if (card.isEmpty() || customer.isEmpty()) {
            return failed(r, null, "no_saved_card", "The member has no saved card to charge", keepFailure);
        }
        Instant now = Instant.now(clock);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("kind", "membership");
        metadata.put("membership_id", m.id().toString());
        metadata.put("membership_tier", m.tierCode());
        metadata.put("membership_charge", r.kind().name());
        metadata.put("afrotransact_customer_id", m.customerId());
        OffSessionAttempt attempt = stripe.createOffSessionIntent(new StripeGateway.OffSessionIntent(r.amount(),
                customer.get().stripeCustomerId(), card.get().id(), "AfroTransact " + m.tierCode()
                + " membership", metadata, "membership-" + m.id()), r.key() + ":intent");
        IntentSnapshot intent = attempt.intent();
        if (attempt.authenticationRequired()) {
            return failed(r, intent, OffSessionAttempt.AUTHENTICATION_REQUIRED, attempt.errorMessage(), keepFailure);
        }
        if (attempt.errorCode() != null) {
            return failed(r, intent, attempt.errorCode(), attempt.errorMessage(), keepFailure);
        }
        String chargeId;
        switch (intent.status()) {
            case "requires_capture" -> chargeId = stripe.capture(intent.id(), r.amount(), null, metadata,
                    r.key() + ":capture").latestChargeId();
            case "succeeded" -> chargeId = intent.latestChargeId();
            default -> {
                return failed(r, intent, "payment_" + intent.status(), intent.failureMessage(), keepFailure);
            }
        }
        MembershipCharge charge = MembershipCharge.succeeded(chargeIdOf(r.key()), m, r.kind(), r.attempt(),
                r.amount(), intent.id(), chargeId, r.periodStart(), r.periodEnd(), r.key(), now);
        charges.insert(charge);
        Money fee = cardFee(chargeId, r.amount(), now);
        poster.post(Postings.membershipCharged(Fingerprint.id("journal", "membership-charge", charge.id().toString()),
                now, "membership-charge:" + charge.id(), charge, fee), "membership:" + m.id());
        return new Outcome(true, charge, null, null);
    }

    private Outcome failed(Request r, IntentSnapshot intent, String code, String message, boolean keep) {
        MembershipCharge row = null;
        if (keep) {
            row = charges.findByKey(r.key()).orElse(null);
            if (row == null) {
                row = MembershipCharge.failed(chargeIdOf(r.key()), r.membership(), r.kind(), r.attempt(), r.amount(),
                        intent == null ? null : intent.id(), code, message, r.periodStart(), r.periodEnd(), r.key(),
                        Instant.now(clock));
                charges.insert(row);
            }
        }
        return new Outcome(false, row, code, message);
    }

    private static UUID chargeIdOf(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private Money cardFee(String chargeId, Money amount, Instant now) {
        if (chargeId != null) {
            List<BalanceTxn> txns = stripe.chargeBalanceTransactions(chargeId);
            if (!txns.isEmpty()) {
                return Money.of(txns.stream().mapToLong(BalanceTxn::fee).sum(), amount.currency());
            }
        }
        return FeeSchedule.activeAt(feeSchedules.all(), now).map(s -> s.estimateCardFee(amount))
                .orElse(Money.zero(amount.currency()));
    }
}
