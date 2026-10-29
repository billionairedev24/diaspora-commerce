package com.afrotransact.deliveryruns.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class RunActionTest {

    private static final Instant AT = Runs.NOW.plusSeconds(60);

    @Test
    void aCancellationAndAMinimumOverrideAlwaysSayWhy() {
        Run open = Runs.withOrders(Runs.open(3, Capacity.UNLIMITED, CutoffPolicy.CANCEL), 1);
        Run cancelled = open.cancel("Storm", AT);
        for (RunActionKind kind : new RunActionKind[] {RunActionKind.CANCEL, RunActionKind.CONFIRM_OVERRIDE}) {
            assertThatThrownBy(() -> RunAction.of(kind, "  ", "staff-1", RunStatus.OPEN, cancelled, null, AT))
                    .isInstanceOf(RuleViolation.class).extracting("code").isEqualTo("delivery-runs.reason-required");
            assertThatThrownBy(() -> RunAction.of(kind, null, "staff-1", RunStatus.OPEN, cancelled, null, AT))
                    .isInstanceOf(RuleViolation.class);
        }
        RunAction entry = RunAction.of(RunActionKind.CANCEL, "  Storm  ", "staff-1", RunStatus.OPEN, cancelled, null, AT);
        assertThat(entry.reason()).isEqualTo("Storm");
        assertThat(entry.actor()).isEqualTo("staff-1");
        assertThat(entry.fromStatus()).isEqualTo(RunStatus.OPEN);
        assertThat(entry.toStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(entry.orders()).isEqualTo(1);
        assertThat(entry.minimum()).isEqualTo(3);
    }

    @Test
    void aPlainConfirmationAndAnExtensionNeedNoReasonAndAnonymousActorsAreTheSystem() {
        Run open = Runs.withOrders(Runs.open(1, Capacity.UNLIMITED, CutoffPolicy.CANCEL), 1);
        Run confirmed = open.cutOff(AT).confirm(AT);
        RunAction confirmation = RunAction.of(RunActionKind.CONFIRM, null, null, RunStatus.OPEN, confirmed, null, AT);
        assertThat(confirmation.reason()).isNull();
        assertThat(confirmation.actor()).isEqualTo(RunAction.SYSTEM);

        Instant later = open.terms().cutoffAt().plus(Duration.ofDays(2));
        Run extended = open.extend(later, open.terms().window().shift(Duration.ofDays(2)), null, AT);
        RunAction extension = RunAction.of(RunActionKind.EXTEND, null, "staff-2", RunStatus.OPEN, extended,
                open.terms().cutoffAt(), AT);
        assertThat(extension.fromCutoff()).isEqualTo(open.terms().cutoffAt());
        assertThat(extension.toCutoff()).isEqualTo(later);
    }

    @Test
    void aReasonIsAtMostFiveHundredCharacters() {
        Run cancelled = Runs.open(1, Capacity.UNLIMITED, CutoffPolicy.CANCEL).cancel("x", AT);
        assertThatThrownBy(() -> RunAction.of(RunActionKind.CANCEL, "x".repeat(501), "staff", RunStatus.OPEN,
                cancelled, null, AT)).isInstanceOf(RuleViolation.class).extracting("code")
                .isEqualTo("delivery-runs.reason-too-long");
    }

    @Test
    void notesAreTrimmedBlankMeansNoneAndTheyAreCapped() {
        RunTerms base = Runs.terms(2, Capacity.UNLIMITED, CutoffPolicy.CANCEL);
        assertThat(base.notes()).isNull();
        RunTerms noted = new RunTerms(base.window(), base.cutoffAt(), null, base.sellerPickupAt(), 799, "USD", 2,
                Capacity.UNLIMITED, DropMode.DOORSTEP, null, CutoffPolicy.CANCEL, 7, "  Bring cold bags ");
        assertThat(noted.notes()).isEqualTo("Bring cold bags");
        assertThat(noted.reschedule(base.cutoffAt().plusSeconds(1), base.window().shift(Duration.ofSeconds(1)), null)
                .notes()).as("an extension keeps the note").isEqualTo("Bring cold bags");
        RunTerms blank = new RunTerms(base.window(), base.cutoffAt(), null, base.sellerPickupAt(), 799, "USD", 2,
                Capacity.UNLIMITED, DropMode.DOORSTEP, null, CutoffPolicy.CANCEL, 7, "   ");
        assertThat(blank.notes()).isNull();
        assertThatThrownBy(() -> new RunTerms(base.window(), base.cutoffAt(), null, base.sellerPickupAt(), 799, "USD",
                2, Capacity.UNLIMITED, DropMode.DOORSTEP, null, CutoffPolicy.CANCEL, 7, "x".repeat(501)))
                .isInstanceOf(RuleViolation.class).extracting("code").isEqualTo("delivery-runs.notes-too-long");
    }
}
