package com.afrotransact.trustops.domain;

import java.util.List;

/** Links audit records into a tamper-evident chain and verifies it. */
public interface AuditHashChain {

    /** {@code prevHash} of the first entry. */
    String GENESIS = "0".repeat(64);

    /** The entry at {@code seq} following an entry whose hash is {@code prevHash}. */
    AuditEntry link(AuditDraft draft, long seq, String prevHash);

    /** Re-computes the hash of a stored entry from its content and its stored {@code prevHash}. */
    String hashOf(AuditEntry entry);

    /**
     * Verifies consecutive entries (ordered by seq): no gaps, each {@code prevHash} equals the previous hash and each
     * hash matches the content.
     *
     * @param expectedSeq      seq the first entry must have
     * @param expectedPrevHash hash the first entry must link to
     */
    ChainVerification verify(List<AuditEntry> entries, long expectedSeq, String expectedPrevHash);
}
