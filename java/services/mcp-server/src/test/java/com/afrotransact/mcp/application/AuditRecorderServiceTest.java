package com.afrotransact.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.mcp.port.AuditSpool;
import com.afrotransact.mcp.port.AuditTrail;
import org.junit.jupiter.api.Test;

class AuditRecorderServiceTest {

    private final FakeBroker broker = new FakeBroker();
    private final FakeSpool spool = new FakeSpool();
    private final AuditRecorderService recorder = new AuditRecorderServiceImpl(broker, spool,
            new AuditSettings(2, Duration.ofSeconds(1), Duration.ofSeconds(30)));

    @Test
    void publishesDirectlyWhileTheBrokerIsUp() {
        recorder.record(event("a"));
        assertThat(broker.published).extracting(AuditTrail.ToolInvoked::tool).containsExactly("a");
        assertThat(spool.entries).isEmpty();
    }

    @Test
    void spoolsDuringAnOutageAndReplaysInOrder() {
        broker.mode = Mode.THROW;
        recorder.record(event("a"));
        broker.mode = Mode.FAIL_ASYNC;
        recorder.record(event("b"));
        broker.mode = Mode.OK;
        recorder.record(event("c")); // broker back, but a backlog exists: c must queue behind a and b
        assertThat(broker.published).isEmpty();
        assertThat(spool.entries).extracting(s -> s.event().tool()).containsExactly("a", "b", "c");

        assertThat(recorder.replaySpool()).isEqualTo(3);
        assertThat(broker.published).extracting(AuditTrail.ToolInvoked::tool).containsExactly("a", "b", "c");
        assertThat(spool.entries).isEmpty();

        recorder.record(event("d"));
        assertThat(broker.published).extracting(AuditTrail.ToolInvoked::tool).endsWith("d");
        assertThat(spool.entries).isEmpty();
    }

    @Test
    void replayStopsAtTheFirstRefusalToKeepOrder() {
        broker.mode = Mode.THROW;
        recorder.record(event("a"));
        recorder.record(event("b"));
        broker.mode = Mode.OK;
        broker.failNext = 1;

        assertThat(recorder.replaySpool()).isZero();
        assertThat(spool.entries).hasSize(2);
        assertThat(recorder.replaySpool()).isEqualTo(2);
        assertThat(broker.published).extracting(AuditTrail.ToolInvoked::tool).containsExactly("a", "b");
    }

    @Test
    void replayIsSkippedWhileAnotherReplicaHoldsTheLease() {
        broker.mode = Mode.THROW;
        recorder.record(event("a"));
        broker.mode = Mode.OK;
        spool.leaseHolder = "other-pod";

        assertThat(recorder.replaySpool()).isZero();
        assertThat(spool.entries).hasSize(1);
    }

    @Test
    void neverFailsTheCallWhenBrokerAndSpoolAreBothDown() {
        broker.mode = Mode.THROW;
        spool.down = true;
        recorder.record(event("a"));
        assertThat(recorder.replaySpool()).isZero();
    }

    private static AuditTrail.ToolInvoked event(String tool) {
        return new AuditTrail.ToolInvoked(UUID.randomUUID().toString(), tool, "SHOPPER", "u", "agent", null,
                "sha256:00", "ok", 1, Instant.parse("2026-10-10T12:00:00Z"));
    }

    private enum Mode { OK, THROW, FAIL_ASYNC }

    private static final class FakeBroker implements AuditTrail {

        private Mode mode = Mode.OK;
        private int failNext;
        private final List<ToolInvoked> published = new ArrayList<>();

        @Override
        public CompletionStage<Void> publish(ToolInvoked event) {
            if (mode == Mode.THROW) {
                throw new IllegalStateException("broker down");
            }
            if (mode == Mode.FAIL_ASYNC || failNext > 0) {
                failNext = Math.max(0, failNext - 1);
                return CompletableFuture.failedFuture(new IllegalStateException("not acknowledged"));
            }
            published.add(event);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FakeSpool implements AuditSpool {

        private final List<Spooled> entries = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong();
        private String leaseHolder;
        private boolean down;

        @Override
        public void append(AuditTrail.ToolInvoked event) {
            check();
            entries.add(new Spooled(Long.toString(seq.incrementAndGet()), event));
        }

        @Override
        public List<Spooled> oldest(int limit) {
            check();
            return List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
        }

        @Override
        public void remove(String spoolId) {
            entries.removeIf(s -> s.spoolId().equals(spoolId));
        }

        @Override
        public long size() {
            return entries.size();
        }

        @Override
        public boolean tryLease(String owner, Duration ttl) {
            check();
            if (leaseHolder != null && !leaseHolder.equals(owner)) {
                return false;
            }
            leaseHolder = owner;
            return true;
        }

        @Override
        public void releaseLease(String owner) {
            if (owner.equals(leaseHolder)) {
                leaseHolder = null;
            }
        }

        private void check() {
            if (down) {
                throw new IllegalStateException("valkey down");
            }
        }
    }
}
