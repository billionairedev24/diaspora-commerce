package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.CommKind;
import com.afrotransact.deliveryruns.domain.PreorderSettings;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.port.PreorderDecisionLog;
import com.afrotransact.deliveryruns.port.PreorderOrderRepository;
import com.afrotransact.deliveryruns.port.PreorderSettingsRepository;
import com.afrotransact.deliveryruns.port.PreorderStepLog;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S262: the time-driven announcements of a pre-order window. ANNOUNCE at opening, REMIND_48H at close - 48 h and
 * LAST_CALL_6H at close - 6 h each go out once; a reminder whose moment has passed is skipped for good; a paused
 * window or one with the message switched off sends nothing.
 */
class PreorderSchedulerServiceTest {

    private static final Instant OPENS = PreorderFixtures.OPENS;
    private static final Instant CLOSES = PreorderFixtures.CLOSES;
    private static final Instant REMIND_AT = CLOSES.minus(Duration.ofHours(48));
    private static final Instant LAST_CALL_AT = CLOSES.minus(Duration.ofHours(6));

    private final PreorderWindowRepository windows = mock(PreorderWindowRepository.class);
    private final PreorderOrderRepository orders = mock(PreorderOrderRepository.class);
    private final PreorderSettingsRepository settings = mock(PreorderSettingsRepository.class);
    private final PreorderDecisionLog decisions = mock(PreorderDecisionLog.class);
    private final PreorderEventPublisher events = mock(PreorderEventPublisher.class);
    private final RunService runs = mock(RunService.class);
    private final PreorderOptions options = mock(PreorderOptions.class);
    private final MemoryStepLog steps = new MemoryStepLog();

    @BeforeEach
    void setUp() {
        when(settings.settings()).thenReturn(PreorderSettings.DEFAULT);
    }

    private PreorderSchedulerService at(Instant now, PreorderWindow w) {
        when(windows.tryLock(w.id())).thenReturn(Optional.of(w));
        return new PreorderSchedulerServiceImpl(windows, orders, settings, steps, decisions, events, runs, options,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void commsSent(PreorderWindow w, int times, CommKind kind) {
        verify(events, times(times)).commsDue(eq(w), eq(kind), any(), any(), any(), any());
    }

    @Test
    void announcesWhenTheWindowOpensAndOnlyOnce() {
        PreorderWindow w = PreorderFixtures.window(EnumSet.allOf(CommKind.class));
        PreorderSchedulerService scheduler = at(OPENS.plusSeconds(30), w);

        assertThat(scheduler.process(w.id())).as("opened + announce").isEqualTo(2);
        assertThat(scheduler.process(w.id())).as("each step once").isZero();

        commsSent(w, 1, CommKind.ANNOUNCE);
        commsSent(w, 0, CommKind.REMIND_48H);
        commsSent(w, 0, CommKind.LAST_CALL_6H);
        assertThat(steps.done(w.id())).contains("OPENED", "COMMS_ANNOUNCE");
    }

    @Test
    void remindsAt48HoursBeforeCloseOnceAndNotBefore() {
        PreorderWindow w = PreorderFixtures.window(EnumSet.allOf(CommKind.class));
        steps.markDone(w.id(), "OPENED", OPENS, OPENS);
        steps.markDone(w.id(), "COMMS_ANNOUNCE", OPENS, OPENS);

        assertThat(at(REMIND_AT.minusSeconds(1), w).process(w.id())).as("a second early").isZero();
        commsSent(w, 0, CommKind.REMIND_48H);

        PreorderSchedulerService scheduler = at(REMIND_AT.plusSeconds(1), w);
        assertThat(scheduler.process(w.id())).isEqualTo(1);
        assertThat(scheduler.process(w.id())).as("not again").isZero();
        assertThat(at(REMIND_AT.plus(Duration.ofHours(10)), w).process(w.id())).as("not on a later tick").isZero();

        commsSent(w, 1, CommKind.REMIND_48H);
        commsSent(w, 0, CommKind.LAST_CALL_6H);
    }

    @Test
    void lastCallGoesOutOnceInTheSixHoursBeforeClose() {
        PreorderWindow w = PreorderFixtures.window(EnumSet.allOf(CommKind.class));
        steps.markDone(w.id(), "OPENED", OPENS, OPENS);
        steps.markDone(w.id(), "COMMS_ANNOUNCE", OPENS, OPENS);
        steps.markDone(w.id(), "COMMS_REMIND_48H", REMIND_AT, REMIND_AT);

        assertThat(at(LAST_CALL_AT.minusSeconds(1), w).process(w.id())).isZero();
        commsSent(w, 0, CommKind.LAST_CALL_6H);

        PreorderSchedulerService scheduler = at(LAST_CALL_AT.plusSeconds(1), w);
        assertThat(scheduler.process(w.id())).isEqualTo(1);
        assertThat(scheduler.process(w.id())).as("once only").isZero();
        assertThat(at(CLOSES.minusSeconds(60), w).process(w.id())).as("not again a minute before close").isZero();

        commsSent(w, 1, CommKind.LAST_CALL_6H);
    }

    @Test
    void aReminderWhoseMomentHasPassedIsSkippedForGood() {
        PreorderWindow w = PreorderFixtures.window(EnumSet.allOf(CommKind.class));
        // The job was down from the day the window opened until 5 hours before it closes: the 48 h reminder would
        // now say "48 hours left" with 5 left, so it is dropped; the announcement and the last call still apply.
        PreorderSchedulerService scheduler = at(CLOSES.minus(Duration.ofHours(5)), w);

        assertThat(scheduler.process(w.id())).as("opened, announce, last call").isEqualTo(3);

        commsSent(w, 1, CommKind.ANNOUNCE);
        commsSent(w, 0, CommKind.REMIND_48H);
        commsSent(w, 1, CommKind.LAST_CALL_6H);
        assertThat(steps.done(w.id())).as("the stale reminder is recorded so it never fires later")
                .contains("COMMS_REMIND_48H");
        assertThat(scheduler.process(w.id())).isZero();
        commsSent(w, 0, CommKind.REMIND_48H);
    }

    @Test
    void afterTheCloseNoPreCloseMessageIsSentAtAll() {
        PreorderWindow w = PreorderFixtures.window(EnumSet.allOf(CommKind.class));

        at(CLOSES.plusSeconds(60), w).process(w.id());

        commsSent(w, 0, CommKind.ANNOUNCE);
        commsSent(w, 0, CommKind.REMIND_48H);
        commsSent(w, 0, CommKind.LAST_CALL_6H);
        assertThat(steps.done(w.id())).contains("COMMS_ANNOUNCE", "COMMS_REMIND_48H", "COMMS_LAST_CALL_6H");
    }

    @Test
    void aPausedWindowSendsNothingAndKeepsItsMessagesForTheResumeWhileTheyAreNotStale() {
        PreorderWindow paused = PreorderFixtures.window(EnumSet.allOf(CommKind.class), false, null);
        Instant now = REMIND_AT.plus(Duration.ofHours(2));

        at(now, paused).process(paused.id());

        verify(events, never()).commsDue(any(), any(), any(), any(), any(), any());
        assertThat(steps.done(paused.id())).doesNotContain("COMMS_ANNOUNCE", "COMMS_REMIND_48H");

        PreorderWindow resumed = new PreorderWindow(paused.id(), paused.code(), paused.details(), paused.creatives(),
                false, true, null, paused.createdAt(), paused.updatedAt(), paused.version());
        at(now, resumed).process(resumed.id());
        commsSent(resumed, 1, CommKind.ANNOUNCE);
        commsSent(resumed, 1, CommKind.REMIND_48H);
    }

    @Test
    void aMessageSwitchedOffInTheWizardIsNeverSent() {
        PreorderWindow w = PreorderFixtures.window(EnumSet.of(CommKind.ANNOUNCE));

        at(LAST_CALL_AT.plusSeconds(1), w).process(w.id());

        commsSent(w, 1, CommKind.ANNOUNCE);
        commsSent(w, 0, CommKind.REMIND_48H);
        commsSent(w, 0, CommKind.LAST_CALL_6H);
    }

    /** The step log in memory: a row per (window, step), written once. */
    private static final class MemoryStepLog implements PreorderStepLog {

        private final Map<UUID, Set<String>> rows = new HashMap<>();

        @Override
        public Set<String> done(UUID windowId) {
            return Set.copyOf(rows.getOrDefault(windowId, Set.of()));
        }

        @Override
        public boolean markDone(UUID windowId, String step, Instant dueAt, Instant now) {
            return rows.computeIfAbsent(windowId, k -> new HashSet<>()).add(step);
        }
    }
}
