package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** ADR 0005 §4: minimum order count, awaiting decision, proceed, cancel, early close. */
class PreorderMinimumTest {

    private static final Instant DURING = Preorders.OPENS.plusSeconds(3_600);
    private static final Instant AFTER_CLOSE = Preorders.CLOSES.plusSeconds(60);

    private static PreorderWindow windowWithMinimum(int minimum) {
        WindowDetails d = Preorders.lubbock();
        WindowDetails withMin = new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), d.ribbon(), d.headline(), d.smsCopy(), d.categories(), d.door(), d.pickup(),
                d.pickupPointId(), d.pickupAddress(), d.showCount(), d.socialBudget(), d.comms(),
                d.doorFeeOverride(), d.pickupFeeOverride(), d.locationId(), minimum);
        return Preorders.published(withMin);
    }

    @Test
    void theMinimumIsPartOfTheDetailsAndMustBeInRange() {
        assertThat(windowWithMinimum(25).details().minOrders()).isEqualTo(25);
        assertThat(Preorders.lubbock().minOrders()).as("details built without a minimum have none").isZero();
        WindowDetails d = Preorders.lubbock();
        assertThatThrownBy(() -> new WindowDetails(d.name(), d.targets(), d.opensAt(), d.closesAt(), d.deliversOn(),
                d.timeZone(), d.ribbon(), d.headline(), d.smsCopy(), d.categories(), d.door(), d.pickup(), null, null,
                true, d.socialBudget(), d.comms(), null, null, null, -1))
                .isInstanceOf(RuleViolation.class).extracting(e -> ((RuleViolation) e).code())
                .isEqualTo("delivery-runs.invalid-minimum");
    }

    @Test
    void belowMinimumOnlyWhenAMinimumIsSetAndNotReached() {
        PreorderWindow w = windowWithMinimum(10);
        assertThat(w.belowMinimum(9)).isTrue();
        assertThat(w.belowMinimum(10)).isFalse();
        assertThat(windowWithMinimum(0).belowMinimum(0)).as("no minimum, never below").isFalse();
    }

    @Test
    void closingBelowTheMinimumWaitsForStaffInsteadOfCancelling() {
        PreorderWindow w = windowWithMinimum(10);
        assertThatThrownBy(() -> w.awaitingDecision(DURING)).as("still open").isInstanceOf(
                InvalidTransitionException.class);

        PreorderWindow waiting = w.awaitingDecision(AFTER_CLOSE);
        assertThat(waiting.outcome()).isEqualTo(WindowOutcome.AWAITING_DECISION);
        assertThat(waiting.status(AFTER_CLOSE)).isEqualTo(PreorderStatus.AWAITING_DECISION);
        assertThat(waiting.status(Preorders.DELIVERS.plusDays(30).atStartOfDay().toInstant(ZoneOffset.UTC)))
                .as("waiting stays waiting, even after the delivery day").isEqualTo(PreorderStatus.AWAITING_DECISION);
        assertThat(waiting.closeCleared()).isFalse();
        assertThat(waiting.awaitingDecision(AFTER_CLOSE.plusSeconds(5))).as("idempotent").isSameAs(waiting);
        assertThat(waiting.switchable(AFTER_CLOSE)).isFalse();
        assertThatThrownBy(() -> waiting.pause(AFTER_CLOSE)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> waiting.edit(waiting.details(), PaymentSettings.DEFAULT, AFTER_CLOSE))
                .isInstanceOf(RuleViolation.class);
    }

    @Test
    void proceedingGoesAheadAndOnlyFromAwaitingDecision() {
        PreorderWindow w = windowWithMinimum(10);
        assertThatThrownBy(() -> w.proceed(AFTER_CLOSE)).isInstanceOf(InvalidTransitionException.class);

        PreorderWindow proceeded = w.awaitingDecision(AFTER_CLOSE).proceed(AFTER_CLOSE.plusSeconds(60));
        assertThat(proceeded.outcome()).isEqualTo(WindowOutcome.PROCEEDED);
        assertThat(proceeded.closeCleared()).isTrue();
        assertThat(proceeded.status(AFTER_CLOSE.plusSeconds(60))).isEqualTo(PreorderStatus.CLOSED);
        assertThatThrownBy(() -> proceeded.proceed(AFTER_CLOSE)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void cancellingWorksUntilDeliveryAndOnlyOnce() {
        PreorderWindow w = windowWithMinimum(10);
        PreorderWindow cancelled = w.awaitingDecision(AFTER_CLOSE).cancel(AFTER_CLOSE.plusSeconds(60));
        assertThat(cancelled.outcome()).isEqualTo(WindowOutcome.CANCELLED);
        assertThat(cancelled.status(AFTER_CLOSE.plusSeconds(60))).isEqualTo(PreorderStatus.CANCELLED);
        assertThat(cancelled.closeCleared()).isFalse();
        assertThat(cancelled.switchable(AFTER_CLOSE)).isFalse();
        assertThatThrownBy(() -> cancelled.cancel(AFTER_CLOSE)).isInstanceOf(InvalidTransitionException.class);

        assertThat(w.cancel(DURING).status(DURING)).as("an open window can be cancelled too")
                .isEqualTo(PreorderStatus.CANCELLED);
        assertThatThrownBy(() -> Preorders.draft(Preorders.lubbock()).cancel(DURING))
                .isInstanceOf(InvalidTransitionException.class);
        Instant delivered = w.deliveredAt().plusSeconds(1);
        assertThatThrownBy(() -> w.cancel(delivered)).as("a delivered window cannot be cancelled")
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void closingEarlyNeedsAnOpenWindowThatMetItsMinimum() {
        PreorderWindow w = windowWithMinimum(10);
        assertThatThrownBy(() -> w.closeEarly(9, DURING)).isInstanceOf(RuleViolation.class)
                .extracting(e -> ((RuleViolation) e).code()).isEqualTo("delivery-runs.preorder-minimum-not-met");
        assertThatThrownBy(() -> windowWithMinimum(0).closeEarly(50, DURING)).as("no minimum, nothing to meet")
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> w.closeEarly(10, Preorders.OPENS.minusSeconds(1))).as("not open yet")
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> w.closeEarly(10, AFTER_CLOSE)).as("already closed")
                .isInstanceOf(InvalidTransitionException.class);

        PreorderWindow early = w.closeEarly(10, DURING);
        assertThat(early.details().closesAt()).isEqualTo(DURING);
        assertThat(early.status(DURING.plusSeconds(1))).isEqualTo(PreorderStatus.CLOSED);
        assertThat(early.outcome()).as("met: no decision needed").isNull();
    }

    @Test
    void staffDecisionsNeedAReasonAndTheLogKeepsWhoWhenWhy() {
        UUID window = UUID.randomUUID();
        assertThatThrownBy(() -> WindowDecision.byStaff(window, DecisionKind.CANCEL, "  ", "staff-1", 3, 10, DURING))
                .isInstanceOf(RuleViolation.class).extracting(e -> ((RuleViolation) e).code())
                .isEqualTo("delivery-runs.reason-required");
        WindowDecision d = WindowDecision.byStaff(window, DecisionKind.CANCEL, " Not enough demand ", "staff-1", 3, 10,
                DURING);
        assertThat(d.reason()).isEqualTo("Not enough demand");
        assertThat(d.actor()).isEqualTo("staff-1");
        assertThat(d.at()).isEqualTo(DURING);
        assertThat(WindowDecision.awaiting(window, 3, 10, DURING).actor()).isEqualTo(WindowDecision.SYSTEM);
        assertThat(WindowDecision.awaiting(window, 3, 10, DURING).reason()).contains("3 of 10");
    }
}
