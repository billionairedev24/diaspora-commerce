package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.payments.application.LedgerAdjustmentService.Line;
import com.afrotransact.payments.application.LedgerAdjustmentService.ProposeAdjustment;
import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.EntrySide;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.LedgerAdjustment;
import com.afrotransact.payments.domain.PaymentsForbidden;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.ReconciliationDrift;
import com.afrotransact.payments.domain.ReconciliationItem;
import com.afrotransact.payments.domain.ReconciliationStatus;
import com.afrotransact.payments.port.LedgerAdjustmentRepository;
import com.afrotransact.payments.port.ReconciliationItemRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;

class LedgerAdjustmentServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final LedgerAdjustmentRepository adjustments = mock(LedgerAdjustmentRepository.class);
    private final ReconciliationItemRepository items = mock(ReconciliationItemRepository.class);
    private final LedgerPoster poster = mock(LedgerPoster.class);
    private final MoneyTransactions tx = mock(MoneyTransactions.class);
    private final PaymentsAccess access = mock(PaymentsAccess.class);
    private final PaymentsSettings settings = new PaymentsSettings("org_retail", Duration.ofDays(7), ZoneId.of("UTC"),
            "USD", 5, Duration.ofHours(1));
    private final LedgerAdjustmentService service = new LedgerAdjustmentServiceImpl(adjustments, items, poster, tx,
            access, settings, Clock.fixed(NOW, ZoneOffset.UTC));

    LedgerAdjustmentServiceImplTest() {
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get()).when(tx).serializable(any());
    }

    private static Actor staff(String subject) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("*"));
    }

    private static List<Line> balancedLines() {
        return List.of(new Line("1000", "", EntrySide.DEBIT, 250, null), new Line("5000", "", EntrySide.CREDIT, 250, null));
    }

    @Test
    void proposingStoresItWithoutPostingAnything() {
        when(access.adjuster()).thenReturn(staff("alice"));

        LedgerAdjustment a = service.propose(new ProposeAdjustment(null, "ch_123456", "Stripe fee booked twice",
                balancedLines()));

        assertThat(a.status()).isEqualTo(AdjustmentStatus.PROPOSED);
        assertThat(a.proposedBy()).isEqualTo("alice");
        verify(adjustments).insert(a);
        verify(poster, never()).post(any(), anyString());
    }

    @Test
    void approvingPostsTheJournalAndMarksTheItemAdjusted() {
        ReconciliationItem item = ReconciliationItem.open(UUID.randomUUID(), UUID.randomUUID(), NOW,
                new ReconciliationDrift("ch_123456", 1000, 750), "USD", NOW);
        when(access.adjuster()).thenReturn(staff("alice"));
        when(items.lock(item.id())).thenReturn(Optional.of(item));
        when(adjustments.ofItem(item.id())).thenReturn(List.of());
        LedgerAdjustment stored = service.propose(new ProposeAdjustment(item.id(), null, "Stripe fee booked twice",
                balancedLines()));

        when(access.adjustmentApprover()).thenReturn(staff("bob"));
        when(adjustments.lock(stored.id())).thenReturn(Optional.of(stored));

        LedgerAdjustment posted = service.approve(stored.id(), "matches Stripe");

        assertThat(posted.status()).isEqualTo(AdjustmentStatus.POSTED);
        verify(poster).post(any(Journal.class), eq("bob"));
        verify(items).update(argThat(i -> i.status() == ReconciliationStatus.ADJUSTED));
    }

    @Test
    void aProposerCannotApproveTheirOwnAdjustment() {
        when(access.adjuster()).thenReturn(staff("alice"));
        LedgerAdjustment stored = service.propose(new ProposeAdjustment(null, "ch_123456", "Stripe fee booked twice",
                balancedLines()));
        when(access.adjustmentApprover()).thenReturn(staff("alice"));
        when(adjustments.lock(stored.id())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.approve(stored.id(), null)).isInstanceOf(PaymentsForbidden.class);
        verify(poster, never()).post(any(), anyString());
    }

    @Test
    void anUnknownAccountOrAnUnbalancedProposalIsRefused() {
        when(access.adjuster()).thenReturn(staff("alice"));

        assertThatThrownBy(() -> service.propose(new ProposeAdjustment(null, "ch_123456", "Stripe fee booked twice",
                List.of(new Line("9999", "", EntrySide.DEBIT, 5, null), new Line("5000", "", EntrySide.CREDIT, 5, null)))))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> service.propose(new ProposeAdjustment(null, "ch_123456", "Stripe fee booked twice",
                List.of(new Line("1000", "", EntrySide.DEBIT, 5, null), new Line("5000", "", EntrySide.CREDIT, 4, null)))))
                .isInstanceOf(PaymentsRuleViolation.class);
        assertThatThrownBy(() -> service.propose(new ProposeAdjustment(null, null, "Seller payable fix up",
                List.of(new Line("2000", "", EntrySide.DEBIT, 5, null), new Line("5000", "", EntrySide.CREDIT, 5, null)))))
                .isInstanceOf(PaymentsRuleViolation.class);
    }
}
