package com.afrotransact.trustops.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * SHA-256 chain. Fields are length-prefixed ({@code <len>:<value>;}, null as {@code -1:;}) so no two different
 * records serialise to the same bytes.
 */
public final class DefaultAuditHashChain implements AuditHashChain {

    @Override
    public AuditEntry link(AuditDraft draft, long seq, String prevHash) {
        return new AuditEntry(seq, draft, prevHash, hash(prevHash, seq, draft));
    }

    @Override
    public String hashOf(AuditEntry entry) {
        return hash(entry.prevHash(), entry.seq(), entry.draft());
    }

    @Override
    public ChainVerification verify(List<AuditEntry> entries, long expectedSeq, String expectedPrevHash) {
        long seq = expectedSeq;
        String prev = expectedPrevHash;
        long checked = 0;
        long lastSeq = expectedSeq - 1;
        for (AuditEntry e : entries) {
            if (e.seq() != seq) {
                return ChainVerification.broken(checked, seq, "Entry " + seq + " is missing (found " + e.seq() + ")",
                        lastSeq, prev);
            }
            if (!e.prevHash().equals(prev)) {
                return ChainVerification.broken(checked, e.seq(), "prevHash does not match the previous entry's hash",
                        lastSeq, prev);
            }
            if (!hashOf(e).equals(e.hash())) {
                return ChainVerification.broken(checked, e.seq(), "Content does not match the stored hash",
                        lastSeq, prev);
            }
            checked++;
            lastSeq = e.seq();
            prev = e.hash();
            seq++;
        }
        return ChainVerification.ok(checked, lastSeq, prev);
    }

    private static String hash(String prevHash, long seq, AuditDraft d) {
        AuditFacts f = d.facts();
        StringBuilder b = new StringBuilder(256 + d.payload().length());
        field(b, prevHash);
        field(b, Long.toString(seq));
        field(b, d.id().toString());
        field(b, d.sourceEventId() == null ? null : d.sourceEventId().toString());
        field(b, d.sourceTopic());
        field(b, d.eventType());
        field(b, f.actorId());
        field(b, f.actorType());
        field(b, f.action());
        field(b, f.entityType());
        field(b, f.entityId());
        field(b, f.orgId());
        field(b, f.summary());
        field(b, d.payload());
        field(b, d.occurredAt().toString());
        field(b, d.recordedAt().toString());
        return HexFormat.of().formatHex(sha256().digest(b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void field(StringBuilder b, String value) {
        if (value == null) {
            b.append("-1:;");
        } else {
            b.append(value.length()).append(':').append(value).append(';');
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
