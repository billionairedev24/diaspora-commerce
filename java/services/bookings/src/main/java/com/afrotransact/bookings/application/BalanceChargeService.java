package com.afrotransact.bookings.application;

/**
 * Long-dated bookings: charges the balance off-session on the customer's saved card shortly before the appointment
 * ({@code at.bookings.balance.charge-before}) for bookings whose balance hold was released. Safe on every replica:
 * each booking is claimed with {@code SELECT … FOR UPDATE SKIP LOCKED} and re-checked under the lock, and every
 * attempt {@code n} uses the idempotency key {@code BK-…:offsession:n}.
 */
public interface BalanceChargeService {

    /**
     * One round of the job.
     *
     * @return bookings whose balance charge changed (submitted, flagged)
     */
    int chargeDueBalances();
}
