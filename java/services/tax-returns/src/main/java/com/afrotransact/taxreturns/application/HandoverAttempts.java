package com.afrotransact.taxreturns.application;

/**
 * Limits guesses of a return's 6-digit handover code. Failures are counted per RMA (not per driver), so rotating
 * driver accounts does not widen the guess budget; the count is kept in memory and is not rolled back with the
 * request transaction.
 */
interface HandoverAttempts {

    /** Throws when the RMA is locked after too many wrong codes. */
    void requireOpen(String rmaKey);

    /** Records a wrong code. */
    void failed(String rmaKey);

    /** Forgets the failures after a correct code. */
    void succeeded(String rmaKey);
}
