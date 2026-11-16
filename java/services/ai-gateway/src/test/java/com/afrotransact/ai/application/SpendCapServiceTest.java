package com.afrotransact.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import com.afrotransact.ai.application.AiSpendCapPolicy.Source;
import com.afrotransact.ai.application.SpendCapService.SpendCapView;
import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.SpendCapPreconditionException;
import com.afrotransact.ai.domain.SpendCapSettings;
import com.afrotransact.ai.domain.UsageTotals;
import com.afrotransact.ai.port.UsageLedger;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

class SpendCapServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private static final Caller ADMIN = new Caller("staff-admin", "STAFF", null, Set.of("ai:manage", "ai:cap.manage"), null);
    private static final Caller VIEWER = new Caller("staff-view", "STAFF", null, Set.of("ai:manage"), null);

    private final InMemorySpendCapStore store = new InMemorySpendCapStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final UsageLedger ledger = mock(UsageLedger.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final AiSpendCapPolicyImpl policy = new AiSpendCapPolicyImpl(store,
            new SpendCapSettings(2_000_000, 20_000_000, Duration.ofSeconds(10)), clock);
    private final TransactionOperations tx = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    };
    private final SpendCapServiceImpl service = new SpendCapServiceImpl(policy, store, ledger, events, tx, clock);

    @BeforeEach
    void spent() {
        when(ledger.globalTotals(TODAY)).thenReturn(new UsageTotals(10, 1_400_000));
    }

    @Test
    void viewShowsDefaultCeilingSpendAndRemaining() {
        SpendCapView v = service.view(VIEWER);

        assertThat(v.capMicros()).isEqualTo(2_000_000);
        assertThat(v.source()).isEqualTo(Source.DEFAULT);
        assertThat(v.defaultMicros()).isEqualTo(2_000_000);
        assertThat(v.ceilingMicros()).isEqualTo(20_000_000);
        assertThat(v.version()).isZero();
        assertThat(v.spentMicros()).isEqualTo(1_400_000);
        assertThat(v.remainingMicros()).isEqualTo(600_000);
        assertThat(v.day()).isEqualTo(TODAY);
        assertThat(v.resetsAt()).isEqualTo(Instant.parse("2026-11-17T00:00:00Z"));
        assertThat(v.canChange()).isFalse();
        assertThat(v.history()).isEmpty();
    }

    @Test
    void savingChangesTheCapInForceAndTheNextReadSeesItAtOnce() {
        assertThat(policy.capMicros()).isEqualTo(2_000_000);

        SpendCapView v = service.update(ADMIN, 0L, 5_000_000, "  Launch week  ");

        assertThat(v.capMicros()).isEqualTo(5_000_000);
        assertThat(v.source()).isEqualTo(Source.SAVED);
        assertThat(v.version()).isEqualTo(1);
        assertThat(v.changedBy()).isEqualTo("staff-admin");
        assertThat(v.reason()).isEqualTo("Launch week");
        assertThat(v.canChange()).isTrue();
        assertThat(policy.capMicros()).as("cache dropped by the save").isEqualTo(5_000_000);
        assertThat(v.history()).singleElement().satisfies(c -> {
            assertThat(c.previousWasDefault()).isTrue();
            assertThat(c.previousMicros()).isNull();
            assertThat(c.newMicros()).isEqualTo(5_000_000);
            assertThat(c.changedBy()).isEqualTo("staff-admin");
            assertThat(c.reason()).isEqualTo("Launch week");
        });
    }

    @Test
    void everyChangeIsPublishedForTheAuditLogWithBeforeAfterAndReason() {
        service.update(ADMIN, 0L, 5_000_000, "Launch week");
        service.update(ADMIN, 1L, 1_000_000, "Back down");

        verify(events).publish(eq(AiEvents.SPEND_CAP_CHANGED), eq(AiEvents.AGGREGATE_SPEND_CAP), eq("1"),
                eq(new SpendCapServiceImpl.SpendCapChangedPayload(1, "DEFAULT", 2_000_000L, 5_000_000, "staff-admin",
                        "Launch week", clock.instant())));
        verify(events).publish(eq(AiEvents.SPEND_CAP_CHANGED), eq(AiEvents.AGGREGATE_SPEND_CAP), eq("2"),
                eq(new SpendCapServiceImpl.SpendCapChangedPayload(2, "SAVED", 5_000_000L, 1_000_000, "staff-admin",
                        "Back down", clock.instant())));
    }

    @Test
    void loweringBelowTodaysSpendIsAllowedAndLeavesNothingRemaining() {
        SpendCapView v = service.update(ADMIN, 0L, 1_000_000, "Too much today");

        assertThat(v.capMicros()).isEqualTo(1_000_000);
        assertThat(v.spentMicros()).isEqualTo(1_400_000);
        assertThat(v.remainingMicros()).isZero();
    }

    @Test
    void zeroSwitchesAiOff() {
        assertThat(service.update(ADMIN, 0L, 0, "Pause AI").capMicros()).isZero();
    }

    @Test
    void theCeilingAndTheBoundsAreEnforced() {
        assertThat(service.update(ADMIN, 0L, 20_000_000, "At the ceiling").capMicros()).isEqualTo(20_000_000);
        assertRule(() -> service.update(ADMIN, 1L, 20_010_000, "Above"), "ai.spend-cap.above-ceiling");
        assertRule(() -> service.update(ADMIN, 1L, -10_000, "Negative"), "ai.spend-cap.invalid");
        assertRule(() -> service.update(ADMIN, 1L, 1_234_567, "Not cents"), "ai.spend-cap.invalid");
        assertThat(policy.capMicros()).isEqualTo(20_000_000);
    }

    @Test
    void theReasonIsRequiredThreeToThreeHundredCharacters() {
        assertRule(() -> service.update(ADMIN, 0L, 3_000_000, null), "ai.spend-cap.reason-invalid");
        assertRule(() -> service.update(ADMIN, 0L, 3_000_000, "  ok "), "ai.spend-cap.reason-invalid");
        assertRule(() -> service.update(ADMIN, 0L, 3_000_000, "x".repeat(301)), "ai.spend-cap.reason-invalid");
        assertThat(service.update(ADMIN, 0L, 3_000_000, "x".repeat(300)).version()).isEqualTo(1);
        verify(events, never()).publish(any(), any(), eq("2"), any());
    }

    @Test
    void ifMatchIsRequiredAndAStaleVersionIsRefused() {
        assertThatThrownBy(() -> service.update(ADMIN, null, 3_000_000, "No version"))
                .isInstanceOfSatisfying(SpendCapPreconditionException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(428);
                    assertThat(ex.code()).isEqualTo(SpendCapPreconditionException.REQUIRED);
                });
        service.update(ADMIN, 0L, 3_000_000, "First");
        assertThatThrownBy(() -> service.update(ADMIN, 0L, 4_000_000, "Stale"))
                .isInstanceOfSatisfying(SpendCapPreconditionException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(412);
                    assertThat(ex.code()).isEqualTo(SpendCapPreconditionException.MISMATCH);
                });
        assertThat(policy.capMicros()).isEqualTo(3_000_000);
    }

    @Test
    void onlyStaffWithTheCapPermissionMayChangeIt() {
        for (Caller caller : new Caller[] {VIEWER,
            new Caller("u", "CUSTOMER", null, Set.of("ai:cap.manage"), null),
            new Caller("svc", "SERVICE", null, Set.of("ai:cap.manage", "ai:usage.read"), null),
            new Caller("m", "SELLER_MEMBER", "org_1", Set.of("ai:use"), null)}) {
            assertThatThrownBy(() -> service.update(caller, 0L, 3_000_000, "Not allowed"))
                    .isInstanceOf(ForbiddenActionException.class);
        }
        assertThat(policy.capMicros()).isEqualTo(2_000_000);
    }

    @Test
    void viewIsForStaffWithEitherPermissionOnly() {
        assertThat(service.view(new Caller("s", "STAFF", null, Set.of("ai:cap.manage"), null)).canChange()).isTrue();
        for (Caller caller : new Caller[] {new Caller("s", "STAFF", null, Set.of("ai:use"), null),
            new Caller("u", "CUSTOMER", null, Set.of("ai:manage"), null)}) {
            assertThatThrownBy(() -> service.view(caller)).isInstanceOf(ForbiddenActionException.class);
        }
    }

    private static void assertRule(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AiRuleViolation.class,
                ex -> assertThat(ex.code()).isEqualTo(code));
    }
}
