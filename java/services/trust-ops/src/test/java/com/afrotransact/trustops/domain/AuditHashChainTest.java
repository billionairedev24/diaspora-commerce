package com.afrotransact.trustops.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AuditHashChainTest {

    private static final Instant T0 = Instant.parse("2026-10-10T08:00:00.123456789Z");
    private final AuditHashChain chain = new DefaultAuditHashChain();

    private static AuditDraft draft(String actor, String entity) {
        return new AuditDraft(UUID.randomUUID(), UUID.randomUUID(), "at.identity.events", "identity.role.granted",
                new AuditFacts(actor, "STAFF", "identity.role.granted", "USER", entity, null, "Granted role x"),
                "{\"userId\":\"" + entity + "\"}", T0, T0);
    }

    private List<AuditEntry> build(int n) {
        List<AuditEntry> out = new ArrayList<>();
        String prev = AuditHashChain.GENESIS;
        for (int i = 1; i <= n; i++) {
            AuditEntry e = chain.link(draft("admin-" + i, "user-" + i), i, prev);
            out.add(e);
            prev = e.hash();
        }
        return out;
    }

    @Test
    void entriesLinkToTheirPredecessor() {
        List<AuditEntry> log = build(3);

        assertThat(log.getFirst().prevHash()).isEqualTo(AuditHashChain.GENESIS);
        assertThat(log.get(1).prevHash()).isEqualTo(log.get(0).hash());
        assertThat(log.get(2).prevHash()).isEqualTo(log.get(1).hash());
        assertThat(log).extracting(AuditEntry::hash).doesNotHaveDuplicates().allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(chain.verify(log, 1, AuditHashChain.GENESIS)).satisfies(v -> {
            assertThat(v.valid()).isTrue();
            assertThat(v.checked()).isEqualTo(3);
            assertThat(v.lastSeq()).isEqualTo(3);
            assertThat(v.lastHash()).isEqualTo(log.get(2).hash());
        });
    }

    @Test
    void hashIsDeterministicAndInstantsAreTruncatedToDatabasePrecision() {
        AuditDraft d = draft("a", "u");
        assertThat(d.occurredAt()).isEqualTo(Instant.parse("2026-10-10T08:00:00.123456Z"));
        assertThat(chain.link(d, 1, AuditHashChain.GENESIS).hash()).isEqualTo(chain.link(d, 1,
                AuditHashChain.GENESIS).hash());
        assertThat(chain.link(d, 2, AuditHashChain.GENESIS).hash()).isNotEqualTo(chain.link(d, 1,
                AuditHashChain.GENESIS).hash());
    }

    @Test
    void editedContentIsDetected() {
        List<AuditEntry> log = build(3);
        AuditEntry original = log.get(1);
        AuditDraft d = original.draft();
        AuditDraft edited = new AuditDraft(d.id(), d.sourceEventId(), d.sourceTopic(), d.eventType(),
                new AuditFacts("someone-else", "STAFF", d.facts().action(), "USER", d.facts().entityId(), null,
                        d.facts().summary()), d.payload(), d.occurredAt(), d.recordedAt());
        log.set(1, new AuditEntry(2, edited, original.prevHash(), original.hash()));

        ChainVerification v = chain.verify(log, 1, AuditHashChain.GENESIS);
        assertThat(v.valid()).isFalse();
        assertThat(v.brokenAtSeq()).isEqualTo(2L);
        assertThat(v.problem()).contains("Content");
        assertThat(v.checked()).isEqualTo(1);
    }

    @Test
    void reHashedEntryBreaksTheNextLink() {
        List<AuditEntry> log = build(3);
        AuditEntry original = log.get(1);
        AuditDraft tampered = new AuditDraft(original.draft().id(), original.draft().sourceEventId(),
                original.draft().sourceTopic(), original.draft().eventType(), original.facts(), "{\"userId\":\"x\"}",
                original.draft().occurredAt(), original.draft().recordedAt());
        log.set(1, chain.link(tampered, 2, original.prevHash()));

        ChainVerification v = chain.verify(log, 1, AuditHashChain.GENESIS);
        assertThat(v.valid()).isFalse();
        assertThat(v.brokenAtSeq()).isEqualTo(3L);
        assertThat(v.problem()).contains("prevHash");
    }

    @Test
    void missingEntryIsDetected() {
        List<AuditEntry> log = build(4);
        log.remove(2);

        ChainVerification v = chain.verify(log, 1, AuditHashChain.GENESIS);
        assertThat(v.valid()).isFalse();
        assertThat(v.brokenAtSeq()).isEqualTo(3L);
        assertThat(v.problem()).contains("missing");
    }

    @Test
    void verificationCanStartMidChainFromAnAnchor() {
        List<AuditEntry> log = build(5);
        ChainVerification v = chain.verify(log.subList(2, 5), 3, log.get(1).hash());
        assertThat(v.valid()).isTrue();
        assertThat(v.checked()).isEqualTo(3);
        assertThat(chain.verify(log.subList(2, 5), 3, AuditHashChain.GENESIS).valid()).isFalse();
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        AuditDraft a = new AuditDraft(UUID.fromString("00000000-0000-0000-0000-000000000001"), null, "t", "e",
                new AuditFacts("ab", null, "act", "X", "c", null, null), "{}", T0, T0);
        AuditDraft b = new AuditDraft(UUID.fromString("00000000-0000-0000-0000-000000000001"), null, "t", "e",
                new AuditFacts("a", null, "act", "X", "bc", null, null), "{}", T0, T0);
        assertThat(chain.link(a, 1, AuditHashChain.GENESIS).hash())
                .isNotEqualTo(chain.link(b, 1, AuditHashChain.GENESIS).hash());
    }
}
