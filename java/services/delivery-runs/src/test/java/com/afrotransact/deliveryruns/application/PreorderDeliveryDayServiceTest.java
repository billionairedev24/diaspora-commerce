package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.PaymentSettings;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import com.afrotransact.deliveryruns.domain.PreorderOrderStatus;
import com.afrotransact.deliveryruns.domain.PreorderSettings;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderStepLog;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * S262: the customers of a pre-order window hear "out for delivery" once, when the run their orders were handed to
 * leaves. The run is marked as the window's so notifications does not send its generic message as well.
 */
class PreorderDeliveryDayServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T14:05:00Z");
    private static final String STEP = "COMMS_OUT_FOR_DELIVERY";

    private final PreorderWindowRepository windows = mock(PreorderWindowRepository.class);
    private final PreorderOrderRepository orders = mock(PreorderOrderRepository.class);
    private final PreorderSettingsRepository settings = mock(PreorderSettingsRepository.class);
    private final PreorderStepLog steps = mock(PreorderStepLog.class);
    private final PreorderEventPublisher events = mock(PreorderEventPublisher.class);
    private PreorderDeliveryDayService service;
    private Run run;
    private List<RunOrder> onRun;

    @BeforeEach
    void setUp() {
        service = new PreorderDeliveryDayServiceImpl(windows, orders, settings, steps, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(settings.settings()).thenReturn(PreorderSettings.DEFAULT);
        run = PreorderFixtures.run();
        onRun = List.of(PreorderFixtures.slot(run, "o-1", "cust-1", "482913"), PreorderFixtures.slot(run, "o-2", "cust-2", null));
    }

    private PreorderWindow windowOfTheRun(Set<CommKind> comms, boolean active) {
        PreorderWindow w = PreorderFixtures.window(comms, active, run.id());
        when(windows.findByRunId(run.id())).thenReturn(Optional.of(w));
        when(orders.findByWindow(w.id())).thenReturn(List.of(
                PreorderFixtures.order(w, "o-1", "cust-1", PreorderFulfilment.PICKUP, PreorderOrderStatus.PAID),
                PreorderFixtures.order(w, "o-2", "cust-2", PreorderFulfilment.DOOR, PreorderOrderStatus.LATE_ADD),
                PreorderFixtures.order(w, "o-3", "cust-3", PreorderFulfilment.DOOR, PreorderOrderStatus.CANCELLED)));
        when(steps.done(w.id())).thenReturn(Set.of());
        when(steps.markDone(eq(w.id()), eq(STEP), any(), any())).thenReturn(true);
        return w;
    }

    @Test
    void aRunStaffScheduledIsNotAWindowsAndSaysNothing() {
        when(windows.findByRunId(run.id())).thenReturn(Optional.empty());

        assertThat(service.onRunOutForDelivery(run, onRun)).isEmpty();

        verifyNoInteractions(events, steps);
    }

    @Test
    void theWindowsPaidCustomersAreToldOnceWithTheirHandoverCodeAndTheRunIsMarked() {
        PreorderWindow w = windowOfTheRun(EnumSet.allOf(CommKind.class), true);

        assertThat(service.onRunOutForDelivery(run, onRun)).contains("PREORDER LUB-7");

        verify(steps).markDone(eq(w.id()), eq(STEP), eq(NOW), eq(NOW));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PreorderEventPayload.OrderRef>> refs = ArgumentCaptor.forClass(List.class);
        verify(events).commsDueTo(eq(w), eq(CommKind.OUT_FOR_DELIVERY), eq(NOW), eq(PaymentSettings.DEFAULT), eq(NOW),
                refs.capture());
        assertThat(refs.getValue()).containsExactly(
                new PreorderEventPayload.OrderRef("o-1", "cust-1", "PICKUP", "482913"),
                new PreorderEventPayload.OrderRef("o-2", "cust-2", "DOOR", null));
    }

    @Test
    void aSecondDriverEventOrReplicaFindsTheStepAlreadyDoneAndSendsNothing() {
        PreorderWindow w = windowOfTheRun(EnumSet.allOf(CommKind.class), true);
        when(steps.done(w.id())).thenReturn(Set.of(STEP));

        assertThat(service.onRunOutForDelivery(run, onRun)).contains("PREORDER LUB-7");

        verify(steps, never()).markDone(any(), any(), any(), any());
        verify(events, never()).commsDueTo(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void aRacingReplicaThatLosesTheStepRowSendsNothing() {
        PreorderWindow w = windowOfTheRun(EnumSet.allOf(CommKind.class), true);
        when(steps.markDone(eq(w.id()), eq(STEP), any(), any())).thenReturn(false);

        assertThat(service.onRunOutForDelivery(run, onRun)).isPresent();

        verify(events, never()).commsDueTo(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void aPausedWindowOrOneWithTheMessageSwitchedOffSendsNothingButStillOwnsTheRun() {
        windowOfTheRun(EnumSet.allOf(CommKind.class), false);
        assertThat(service.onRunOutForDelivery(run, onRun)).contains("PREORDER LUB-7");

        windowOfTheRun(EnumSet.of(CommKind.ANNOUNCE, CommKind.CLOSED), true);
        assertThat(service.onRunOutForDelivery(run, onRun)).contains("PREORDER LUB-7");

        verify(steps, never()).markDone(any(), any(), any(), any());
        verify(events, never()).commsDueTo(any(), any(), any(), any(), any(), anyList());
    }
}
