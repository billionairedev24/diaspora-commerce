package com.afrotransact.trustops.adapter.in.web;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.trustops.domain.AuditDraft;
import com.afrotransact.trustops.domain.AuditEntry;
import com.afrotransact.trustops.domain.AuditFacts;
import com.afrotransact.trustops.domain.ChainVerification;
import io.swagger.v3.oas.annotations.media.Schema;

/** Audit log DTOs. */
final class AuditDtos {

    private AuditDtos() {
    }

    @Schema(description = "An immutable audit record; hash = SHA-256(prevHash, seq, content)")
    record AuditEntryResponse(long seq, UUID id, String eventType, String actorId, String actorType, String action,
                              String entityType, String entityId, String orgId, String summary, Instant occurredAt,
                              Instant recordedAt, String sourceTopic, UUID sourceEventId, String payload,
                              String prevHash, String hash) {

        static AuditEntryResponse of(AuditEntry e) {
            AuditDraft d = e.draft();
            AuditFacts f = d.facts();
            return new AuditEntryResponse(e.seq(), d.id(), d.eventType(), f.actorId(), f.actorType(), f.action(),
                    f.entityType(), f.entityId(), f.orgId(), f.summary(), d.occurredAt(), d.recordedAt(),
                    d.sourceTopic(), d.sourceEventId(), d.payload(), e.prevHash(), e.hash());
        }
    }

    @Schema(description = "Chain verification: valid, entries checked, first broken entry and the head checked")
    record VerificationResponse(boolean valid, long checked, Long brokenAtSeq, String problem, long lastSeq,
                                String lastHash) {

        static VerificationResponse of(ChainVerification v) {
            return new VerificationResponse(v.valid(), v.checked(), v.brokenAtSeq(), v.problem(), v.lastSeq(),
                    v.lastHash());
        }
    }
}
