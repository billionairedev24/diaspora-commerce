package com.afrotransact.mcp.adapter.out.valkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.mcp.port.AuditSpool;
import com.afrotransact.mcp.port.AuditTrail;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ValkeyAuditSpoolIT {

    private final AuditSpool spool = new ValkeyAuditSpool(ValkeyTestSupport.template(), JsonMapper.builder().build(),
            "test:audit:" + UUID.randomUUID(), 10_000);

    @Test
    void keepsArrivalOrderAndRoundTripsEvents() {
        AuditTrail.ToolInvoked first = event("first");
        spool.append(first);
        spool.append(event("second"));
        spool.append(event("third"));

        assertThat(spool.size()).isEqualTo(3);
        assertThat(spool.oldest(2)).extracting(s -> s.event().tool()).containsExactly("first", "second");
        AuditSpool.Spooled head = spool.oldest(1).getFirst();
        assertThat(head.event()).isEqualTo(first);

        spool.remove(head.spoolId());
        assertThat(spool.oldest(10)).extracting(s -> s.event().tool()).containsExactly("second", "third");
    }

    @Test
    void oneReplicaHoldsTheReplayLease() {
        assertThat(spool.tryLease("pod-a", Duration.ofSeconds(30))).isTrue();
        assertThat(spool.tryLease("pod-b", Duration.ofSeconds(30))).isFalse();
        spool.releaseLease("pod-b");
        assertThat(spool.tryLease("pod-b", Duration.ofSeconds(30))).as("not released by a non-holder").isFalse();
        spool.releaseLease("pod-a");
        assertThat(spool.tryLease("pod-b", Duration.ofSeconds(30))).isTrue();
        spool.releaseLease("pod-b");
    }

    private static AuditTrail.ToolInvoked event(String tool) {
        return new AuditTrail.ToolInvoked(UUID.randomUUID().toString(), tool, "SHOPPER", "user-1", "agent", null,
                "sha256:00", "ok", 12, Instant.parse("2026-10-10T12:00:00Z"));
    }
}
