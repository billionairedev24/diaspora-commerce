package com.afrotransact.trustops.domain;

import java.util.Objects;

/**
 * One immutable, hash-chained audit record. {@code hash = SHA-256(prevHash ‖ seq ‖ content)}; the first entry's
 * {@code prevHash} is {@link AuditHashChain#GENESIS}.
 */
public record AuditEntry(long seq, AuditDraft draft, String prevHash, String hash) {

    public AuditEntry {
        if (seq <= 0) {
            throw new IllegalArgumentException("seq starts at 1");
        }
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(hash, "hash");
    }

    public AuditFacts facts() {
        return draft.facts();
    }
}
