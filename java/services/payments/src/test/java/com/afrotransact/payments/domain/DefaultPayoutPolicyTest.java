package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultPayoutPolicyTest {

    private final PayoutPolicy policy = new DefaultPayoutPolicy(2500, DefaultPayoutPolicy.DEFAULT_ROLES);
    private final Requester owner = new Requester("u1", Set.of("seller.owner"), false);
    private final Money available = Fixtures.usd(10_000);

    @Test
    void ownerCanRequestAnAmountOrEverything() {
        assertThat(policy.validate(owner, new PayoutAsk(Fixtures.usd(2500), false), available, List.of()).minor())
                .isEqualTo(2500);
        assertThat(policy.validate(owner, new PayoutAsk(null, true), available, List.of())).isEqualTo(available);
        Requester finance = new Requester("u2", Set.of("provider.finance"), false);
        assertThat(policy.validate(finance, new PayoutAsk(Fixtures.usd(3000), false), available, List.of())
                .minor()).isEqualTo(3000);
        Requester staff = new Requester("ops", Set.of(), true);
        assertThat(policy.validate(staff, new PayoutAsk(Fixtures.usd(3000), false), available, List.of()).minor())
                .isEqualTo(3000);
    }

    @Test
    void minimumIsTwentyFiveDollars() {
        assertThatThrownBy(() -> policy.validate(owner, new PayoutAsk(Fixtures.usd(2499), false), available,
                List.of())).isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.payout-below-minimum"));
        assertThatThrownBy(() -> policy.validate(owner, new PayoutAsk(null, true), Fixtures.usd(2000), List.of()))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.payout-below-minimum"));
    }

    @Test
    void neverMoreThanAvailable() {
        assertThatThrownBy(() -> policy.validate(owner, new PayoutAsk(Fixtures.usd(10_001), false), available,
                List.of())).isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.payout-exceeds-available"));
    }

    @Test
    void onePerDayButFailedPayoutsDoNotCount() {
        PayoutRequest today = PayoutRequest.request(UUID.randomUUID(), "org_a", Fixtures.usd(3000), "u1",
                Instant.now(), LocalDate.now());
        assertThatThrownBy(() -> policy.validate(owner, new PayoutAsk(Fixtures.usd(3000), false), available,
                List.of(today))).isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.payout-daily-limit"));
        PayoutRequest failed = today.approve("fin", Instant.now(), "po_1").failed("account closed");
        assertThat(policy.canRequestToday(List.of(failed))).isTrue();
    }

    @Test
    void onlyOwnerAndFinanceRolesMayRequest() {
        Requester catalogMember = new Requester("u3", Set.of("seller.catalog_manager"), false);
        assertThatThrownBy(() -> policy.validate(catalogMember, new PayoutAsk(Fixtures.usd(3000), false),
                available, List.of())).isInstanceOfSatisfying(PaymentsForbidden.class,
                        e -> assertThat(e.code()).isEqualTo("payments.payout-role"));
    }

    @Test
    void lifecycleTransitions() {
        PayoutRequest r = PayoutRequest.request(UUID.randomUUID(), "org_a", Fixtures.usd(3000), "u1",
                Instant.now(), LocalDate.now());
        PayoutRequest held = r.hold("fin", Instant.now(), "KYC check");
        assertThat(held.status()).isEqualTo(PayoutStatus.HELD);
        assertThatThrownBy(() -> held.approve("fin", Instant.now(), "po")).isInstanceOf(PaymentsStateConflict.class);
        PayoutRequest released = held.release("fin", Instant.now());
        assertThat(released.status()).isEqualTo(PayoutStatus.REQUESTED);
        PayoutRequest paid = released.approve("fin", Instant.now(), "po_9").paid();
        assertThat(paid.status()).isEqualTo(PayoutStatus.PAID);
        assertThat(paid.stripePayoutId()).isEqualTo("po_9");
        assertThat(PayoutStatus.IN_TRANSIT.inFlight()).isTrue();
        assertThat(PayoutStatus.FAILED.consumesBalance()).isFalse();
    }
}
