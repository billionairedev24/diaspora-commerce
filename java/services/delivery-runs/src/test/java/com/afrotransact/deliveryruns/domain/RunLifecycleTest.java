package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunLifecycleTest {

    private static final Instant NOW = Runs.NOW;

    @Test
    void happyPathDraftToCompleted() {
        Run draft = Run.draft(UUID.randomUUID(), UUID.randomUUID(), Runs.terms(2, Capacity.UNLIMITED,
                CutoffPolicy.EXTEND), null, NOW);
        assertThat(draft.status()).isEqualTo(RunStatus.DRAFT);

        Run announced = draft.announce(NOW);
        Run open = announced.open(NOW.plusSeconds(60));
        assertThat(open.status()).isEqualTo(RunStatus.OPEN);
        assertThat(open.milestones().announcedAt()).isEqualTo(NOW);
        assertThat(open.milestones().openedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(open.acceptsOrders(NOW)).isTrue();

        Run cut = Runs.withOrders(open, 2).cutOff(Runs.CUTOFF);
        assertThat(cut.status()).isEqualTo(RunStatus.CUT_OFF);
        assertThat(cut.acceptsOrders(Runs.CUTOFF)).isFalse();

        Run done = cut.confirm(Runs.CUTOFF).startCollecting(Runs.CUTOFF).outForDelivery(Runs.CUTOFF)
                .complete(Runs.CUTOFF);
        assertThat(done.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(done.milestones().completedAt()).isEqualTo(Runs.CUTOFF);
        assertThat(done.status().isTerminal()).isTrue();
    }

    @Test
    void openingFromDraftAlsoRecordsTheAnnouncement() {
        Run open = Runs.open(1, Capacity.UNLIMITED, CutoffPolicy.CANCEL);
        assertThat(open.milestones().announcedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsTransitionsTheStatusDoesNotAllow() {
        Run draft = Run.draft(UUID.randomUUID(), UUID.randomUUID(), Runs.terms(1, Capacity.UNLIMITED,
                CutoffPolicy.CANCEL), null, NOW);
        assertThatThrownBy(() -> draft.confirm(NOW)).isInstanceOf(InvalidTransitionException.class)
                .hasMessage("Cannot move from DRAFT to CONFIRMED");
        assertThatThrownBy(() -> draft.outForDelivery(NOW)).isInstanceOf(InvalidTransitionException.class);
        Run open = draft.open(NOW);
        assertThatThrownBy(() -> open.announce(NOW)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> open.startCollecting(NOW)).isInstanceOf(InvalidTransitionException.class);
        Run cancelled = open.cancel("Weather", NOW);
        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo("Weather");
        assertThatThrownBy(() -> cancelled.open(NOW)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> cancelled.cancel("again", NOW)).isInstanceOf(InvalidTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"COLLECTING", "OUT_FOR_DELIVERY", "COMPLETED", "CANCELLED"})
    void cannotCancelOnceCollectionStartedOrFinished(RunStatus status) {
        assertThat(status.canMoveTo(RunStatus.CANCELLED)).isFalse();
    }

    @Test
    void cannotOpenAfterTheCutoff() {
        Run draft = Run.draft(UUID.randomUUID(), UUID.randomUUID(), Runs.terms(1, Capacity.UNLIMITED,
                CutoffPolicy.CANCEL), null, NOW);
        assertThatThrownBy(() -> draft.open(Runs.CUTOFF)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.cutoff-passed");
    }

    @Test
    void extendMovesCutoffAndTakesOrdersAgain() {
        Run cut = Runs.open(5, Capacity.UNLIMITED, CutoffPolicy.EXTEND).cutOff(Runs.CUTOFF);
        Run extended = cut.extendByPolicy(Runs.CUTOFF);
        assertThat(extended.status()).isEqualTo(RunStatus.EXTENDED);
        assertThat(extended.extensions()).isEqualTo(1);
        assertThat(extended.terms().cutoffAt()).isEqualTo(Runs.CUTOFF.plus(Duration.ofDays(7)));
        assertThat(extended.terms().window().start()).isEqualTo(Runs.WINDOW.start().plus(Duration.ofDays(7)));
        assertThat(extended.terms().sellerPickupAt()).isEqualTo(Runs.CUTOFF.plus(Duration.ofDays(7))
                .plus(Duration.ofHours(10)));
        assertThat(extended.originalCutoffAt()).isEqualTo(Runs.CUTOFF);
        assertThat(extended.acceptsOrders(Runs.CUTOFF.plusSeconds(1))).isTrue();
        assertThat(extended.dueForCutoff(Runs.CUTOFF.plus(Duration.ofDays(7)))).isTrue();
    }

    @Test
    void extendNeedsALaterFutureCutoff() {
        Run open = Runs.open(5, Capacity.UNLIMITED, CutoffPolicy.EXTEND);
        assertThatThrownBy(() -> open.extend(Runs.CUTOFF.minusSeconds(60), Runs.WINDOW, null, NOW))
                .isInstanceOf(RuleViolation.class).extracting("code").isEqualTo("delivery-runs.invalid-extension");
    }

    @Test
    void reminderIsDueOncePerCutoffAndReArmedByAnExtension() {
        Duration lead = Duration.ofHours(24);
        Run open = Runs.open(1, Capacity.UNLIMITED, CutoffPolicy.EXTEND);
        Instant dayBefore = Runs.CUTOFF.minus(Duration.ofHours(23));
        assertThat(open.dueForReminder(Runs.CUTOFF.minus(Duration.ofHours(30)), lead)).isFalse();
        assertThat(open.dueForReminder(dayBefore, lead)).isTrue();
        Run reminded = open.reminderSent(dayBefore);
        assertThat(reminded.dueForReminder(dayBefore, lead)).isFalse();
        Run extended = reminded.extend(Runs.CUTOFF.plus(Duration.ofDays(2)), Runs.WINDOW.shift(Duration.ofDays(2)),
                null, dayBefore);
        assertThat(extended.dueForReminder(Runs.CUTOFF.plus(Duration.ofDays(1)).plusSeconds(60), lead)).isTrue();
    }

    @Test
    void openRunsKeepFeeAndCutoffButCanChangeMinimumAndCapacity() {
        Run open = Runs.withOrders(Runs.open(2, new Capacity(10, null), CutoffPolicy.EXTEND), 3);
        RunTerms t = open.terms();
        RunTerms cheaper = new RunTerms(t.window(), t.cutoffAt(), null, t.sellerPickupAt(), 499, "USD", 2,
                t.capacity(), t.dropMode(), null, t.cutoffPolicy(), 7);
        assertThatThrownBy(() -> open.reschedule(cheaper, NOW)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.run-not-editable");
        RunTerms tooSmall = new RunTerms(t.window(), t.cutoffAt(), null, t.sellerPickupAt(), 799, "USD", 2,
                new Capacity(2, null), t.dropMode(), null, t.cutoffPolicy(), 7);
        assertThatThrownBy(() -> open.reschedule(tooSmall, NOW)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.invalid-capacity");
        RunTerms bigger = new RunTerms(t.window(), t.cutoffAt(), null, t.sellerPickupAt(), 799, "USD", 4,
                new Capacity(20, null), t.dropMode(), null, t.cutoffPolicy(), 7);
        assertThat(open.reschedule(bigger, NOW).terms().minOrders()).isEqualTo(4);
    }

    @Test
    void termsAreValidated() {
        assertThatThrownBy(() -> new RunTerms(Runs.WINDOW, Runs.WINDOW.start(), null, null, 799, "USD", 1, null,
                null, null, null, 7)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.invalid-cutoff");
        assertThatThrownBy(() -> new RunTerms(Runs.WINDOW, Runs.CUTOFF, null, null, 799, "USD", 1, null,
                DropMode.PICKUP_POINT, null, null, 7)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.pickup-point-required");
        assertThatThrownBy(() -> new RunTerms(Runs.WINDOW, Runs.CUTOFF, null, null, 799, "USD", 10,
                new Capacity(5, null), null, null, null, 7)).isInstanceOf(RuleViolation.class)
                .extracting("code").isEqualTo("delivery-runs.invalid-capacity");
        assertThatThrownBy(() -> new DeliveryWindow(Runs.WINDOW.end(), Runs.WINDOW.start()))
                .isInstanceOf(RuleViolation.class);
    }
}
