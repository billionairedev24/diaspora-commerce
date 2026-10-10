package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The balance of a booking and its off-session charge, if any.
 *
 * @param state         where the balance stands
 * @param paymentId     payments' id of the latest off-session charge (a child payment of the booking's payment)
 * @param attempts      off-session attempts made so far ({@code n} in the {@code BK-…:offsession:n} key)
 * @param clientSecret  for the customer to authenticate the charge (only while {@link BalanceState#ACTION_REQUIRED})
 * @param failure       why the last attempt failed or needs action
 * @param nextAttemptAt earliest retry after a decline
 */
public record BalanceCharge(BalanceState state, UUID paymentId, int attempts, String clientSecret, String failure,
                            Instant nextAttemptAt) {

    public BalanceCharge {
        Objects.requireNonNull(state, "state");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    public static BalanceCharge onAuthorisation() {
        return new BalanceCharge(BalanceState.ON_AUTHORISATION, null, 0, null, null, null);
    }

    BalanceCharge withState(BalanceState newState) {
        return new BalanceCharge(newState, paymentId, attempts, newState == BalanceState.ACTION_REQUIRED
                ? clientSecret : null, failure, newState == BalanceState.FAILED ? nextAttemptAt : null);
    }
}
