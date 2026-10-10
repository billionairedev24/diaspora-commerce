package com.afrotransact.fulfilment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Sync ordering: per-device sequence, idempotent replays, gaps and reused numbers. */
class DefaultSequencePolicyTest {

    private final SequencePolicy policy = new DefaultSequencePolicy();

    private record Ev(String id, long seq) {
    }

    @Test
    void appliesOnlyTheNextSequence() {
        assertThat(policy.decide(0, 1, false)).isEqualTo(SyncDecision.APPLY);
        assertThat(policy.decide(7, 8, false)).isEqualTo(SyncDecision.APPLY);
        assertThat(policy.decide(7, 10, false)).isEqualTo(SyncDecision.GAP);
        assertThat(policy.decide(0, 2, false)).isEqualTo(SyncDecision.GAP);
    }

    @Test
    void knownEventsAreDuplicatesAndReusedNumbersConflict() {
        assertThat(policy.decide(7, 3, true)).isEqualTo(SyncDecision.DUPLICATE);
        assertThat(policy.decide(7, 8, true)).isEqualTo(SyncDecision.DUPLICATE);
        assertThat(policy.decide(7, 7, false)).isEqualTo(SyncDecision.SEQUENCE_CONFLICT);
        assertThat(policy.decide(7, 0, false)).isEqualTo(SyncDecision.SEQUENCE_CONFLICT);
    }

    @Test
    void batchesAreAppliedInSequenceOrderWhateverOrderTheyArrive() {
        List<Ev> batch = List.of(new Ev("c", 3), new Ev("a", 1), new Ev("d", 4), new Ev("b", 2));
        assertThat(policy.inOrder(batch, Ev::seq)).extracting(Ev::id).containsExactly("a", "b", "c", "d");
    }

    @Test
    void replayingABatchAfterPartialApplicationResumesWhereItStopped() {
        // Server consumed 1–2; the app resends 1–4 (it never saw the response).
        long last = 2;
        List<SyncDecision> decisions = policy.inOrder(List.of(new Ev("a", 1), new Ev("b", 2), new Ev("c", 3),
                new Ev("d", 4)), Ev::seq).stream().map(e -> policy.decide(last + (e.seq() > 3 ? 1 : 0), e.seq(),
                e.seq() <= 2)).toList();
        assertThat(decisions).containsExactly(SyncDecision.DUPLICATE, SyncDecision.DUPLICATE, SyncDecision.APPLY,
                SyncDecision.APPLY);
    }

    @Test
    void revokedDriversKeepEventsCapturedBeforeTheRevocation() {
        Instant revokedAt = Instant.parse("2026-10-10T18:00:00Z");
        DriverAccess revoked = new DriverAccess("d1", DriverAccessStatus.REVOKED, revokedAt, "role revoked",
                AccessSource.IDENTITY);
        assertThat(revoked.acceptsEventCapturedAt(revokedAt.minusSeconds(60))).isTrue();
        assertThat(revoked.acceptsEventCapturedAt(revokedAt)).isTrue();
        assertThat(revoked.acceptsEventCapturedAt(revokedAt.plusSeconds(1))).isFalse();
        assertThat(revoked.revokedFor(revokedAt.minusSeconds(300))).as("old token: sign out").isTrue();
        assertThat(revoked.revokedFor(revokedAt.plusSeconds(300))).as("token issued after a re-grant").isFalse();
        DriverAccess suspended = new DriverAccess("d1", DriverAccessStatus.REVOKED, revokedAt, "suspended",
                AccessSource.DISPATCH);
        assertThat(suspended.revokedFor(revokedAt.plusSeconds(300))).as("only dispatch lifts a suspension").isTrue();
        DriverAccess active = new DriverAccess("d1", DriverAccessStatus.ACTIVE, revokedAt, "granted",
                AccessSource.IDENTITY);
        assertThat(active.acceptsEventCapturedAt(revokedAt.plusSeconds(999))).isTrue();
        assertThat(active.revokedFor(null)).isFalse();
    }
}
