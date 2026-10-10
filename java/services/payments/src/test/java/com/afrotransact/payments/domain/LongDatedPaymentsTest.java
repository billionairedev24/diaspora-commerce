package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** S220: release after a deposit capture, off-session children, provider fees and effective fees. */
class LongDatedPaymentsTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private static Payment booking() {
        return Payment.create(UUID.randomUUID(), "BK-1", "cus_1", Fixtures.usd(10_000), UUID.randomUUID(), NOW,
                Map.of(), List.of(Fixtures.line("deposit", "org_p", "services", Fulfilment.SELF, 1, 2000, 0, 0),
                        Fixtures.line("balance", "org_p", "services", Fulfilment.SELF, 1, 8000, 0, 0)), NOW)
                .syncStatus(PaymentStatus.AUTHORIZED, null, NOW);
    }

    private static Payment depositCaptured() {
        LineSplit split = new LineSplit("deposit", "org_p", 1000, Fixtures.usd(200), Fixtures.usd(0), false);
        return booking().capture(UUID.randomUUID(), Fixtures.usd(2000), List.of(split), false, NOW);
    }

    @Test
    void releasingAfterTheDepositCaptureKeepsTheCaptureAndReleasesTheRest() {
        Payment p = depositCaptured();
        assertThat(p.status()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
        assertThat(p.released().minor()).isZero();
        assertThatThrownBy(() -> p.cancel(NOW)).isInstanceOfSatisfying(PaymentsStateConflict.class,
                e -> assertThat(e.code()).isEqualTo("payments.already-captured"));

        Payment released = p.releaseRemaining(NOW);
        assertThat(released.status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(released.captured().minor()).isEqualTo(2000);
        assertThat(released.released().minor()).isEqualTo(8000);
        assertThat(released.line("balance").orElseThrow().isCaptured()).isFalse();
        assertThat(released.releaseRemaining(NOW)).as("idempotent").isSameAs(released);
    }

    @Test
    void releasingWithoutCapturesCancels() {
        Payment released = booking().releaseRemaining(NOW);
        assertThat(released.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(released.released().minor()).isEqualTo(10_000);
    }

    @Test
    void offSessionChildSharesTheOrderButNotTheStripeKeys() {
        Payment parent = depositCaptured().releaseRemaining(NOW);
        OrderLine balance = parent.line("balance").orElseThrow().line();
        Payment child = Payment.offSession(UUID.randomUUID(), parent, "cus_1", List.of(balance),
                Map.of("off_session", "true"), NOW);
        assertThat(child.orderId()).isEqualTo("BK-1");
        assertThat(child.amount().minor()).isEqualTo(8000);
        assertThat(child.parentPaymentId()).isEqualTo(parent.id());
        assertThat(child.feeScheduleId()).isEqualTo(parent.feeScheduleId());
        assertThat(child.isOffSession()).isTrue();
        assertThat(parent.stripeKeyPrefix()).isEqualTo("BK-1");
        assertThat(child.stripeKeyPrefix()).isEqualTo("BK-1:os:" + child.id());

        Payment waiting = child.awaitingCustomer("authentication_required", NOW);
        assertThat(waiting.status()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(waiting.syncStatus(PaymentStatus.AUTHORIZED, null, NOW).authorizedAt()).isEqualTo(NOW);
    }

    @Test
    void providerFeeRetriesWithBackoffAndNewKeysOnlyAfterDefinitiveRefusals() {
        ProviderFee fee = ProviderFee.record(UUID.randomUUID(), "b-1", "BK-1", "org_p", Fixtures.usd(1500),
                ProviderFee.LATE_PROVIDER_CANCELLATION, UUID.randomUUID(), null, NOW);
        assertThat(fee.status()).isEqualTo(ProviderFeeStatus.OUTSTANDING);
        assertThat(fee.nextAttemptAt()).isEqualTo(NOW);
        String firstKey = fee.debitKey();

        ProviderFee timeout = fee.attemptFailed("timeout", false, NOW);
        assertThat(timeout.debitKey()).as("same request replayed").isEqualTo(firstKey);
        assertThat(timeout.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        ProviderFee refused = timeout.attemptFailed("balance_insufficient", true, NOW);
        assertThat(refused.debitKey()).isNotEqualTo(firstKey);
        assertThat(refused.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        ProviderFee many = refused;
        for (int i = 0; i < 20; i++) {
            many = many.attemptFailed("x", true, NOW);
        }
        assertThat(many.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));

        ProviderFee collected = refused.collected("py_1", NOW);
        assertThat(collected.status()).isEqualTo(ProviderFeeStatus.COLLECTED);
        assertThat(collected.nextAttemptAt()).isNull();
        assertThatThrownBy(() -> ProviderFee.record(UUID.randomUUID(), "b", "BK", "o", Fixtures.usd(0), "x", null,
                null, NOW)).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void effectiveFeesFollowOverridesCategoriesAndRetail() {
        FeeSchedule s = Fixtures.schedule(1, NOW, List.of(new CommissionOverride("org_vip", "services", 500)));
        EffectiveFees services = s.effectiveFor("org_p", "Services", Fixtures.RETAIL);
        assertThat(services.category()).isEqualTo("services");
        assertThat(services.commissionSource()).isEqualTo("CATEGORY");
        assertThat(services.commissionBps()).isEqualTo(1000);
        assertThat(services.cardPctBps()).isEqualTo(s.cardPctBps());
        assertThat(services.cardFeeOf(Fixtures.usd(10_000))).isEqualTo(s.estimateCardFee(Fixtures.usd(10_000)));

        EffectiveFees vip = s.effectiveFor("org_vip", "services", Fixtures.RETAIL);
        assertThat(vip.commissionBps()).isEqualTo(500);
        assertThat(vip.commissionSource()).isEqualTo("OVERRIDE");

        EffectiveFees retail = s.effectiveFor(Fixtures.RETAIL, "services", Fixtures.RETAIL);
        assertThat(retail.commissionBps()).isZero();
        assertThat(retail.retail()).isTrue();
        assertThat(s.effectiveFor(null, "unknown-category", Fixtures.RETAIL).commissionSource())
                .isEqualTo("DEFAULT");
    }
}
