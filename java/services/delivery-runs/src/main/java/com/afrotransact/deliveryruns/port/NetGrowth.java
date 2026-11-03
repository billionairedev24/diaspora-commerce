package com.afrotransact.deliveryruns.port;

import java.time.YearMonth;

/**
 * Subscriber growth in one calendar month (UTC): sign-ups by the month the subscription was created, unsubscribes
 * by the month it ended (the last consent withdrawn, from the consent log).
 *
 * @param month        calendar month
 * @param signUps      subscriptions created
 * @param unsubscribes subscriptions that ended (every channel withdrawn)
 */
public record NetGrowth(YearMonth month, long signUps, long unsubscribes) {

    /** Sign-ups minus unsubscribes; negative in a month more people left than joined. */
    public long net() {
        return signUps - unsubscribes;
    }
}
