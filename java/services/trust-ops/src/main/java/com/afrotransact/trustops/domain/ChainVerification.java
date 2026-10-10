package com.afrotransact.trustops.domain;

/**
 * Result of re-computing the audit chain.
 *
 * @param valid       every checked entry links to its predecessor and its hash matches its content
 * @param checked     entries checked
 * @param brokenAtSeq first entry that fails (null when valid)
 * @param problem     what is wrong with it (null when valid)
 * @param lastSeq     last entry checked (0 when none)
 * @param lastHash    hash of the last entry checked (genesis when none)
 */
public record ChainVerification(boolean valid, long checked, Long brokenAtSeq, String problem, long lastSeq,
                                String lastHash) {

    public static ChainVerification ok(long checked, long lastSeq, String lastHash) {
        return new ChainVerification(true, checked, null, null, lastSeq, lastHash);
    }

    public static ChainVerification broken(long checked, long seq, String problem, long lastSeq, String lastHash) {
        return new ChainVerification(false, checked, seq, problem, lastSeq, lastHash);
    }
}
