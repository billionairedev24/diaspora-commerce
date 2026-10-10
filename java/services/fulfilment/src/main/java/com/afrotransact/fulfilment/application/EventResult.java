package com.afrotransact.fulfilment.application;

/**
 * What happened to one synced event.
 *
 * @param eventId        client event id
 * @param sequence       its sequence number
 * @param outcome        APPLIED · REJECTED (a rule failed; fix and send a new event) · CONFLICT (the route/stop
 *                       moved on, or the sequence number was reused) · DEFERRED (an earlier sequence number is
 *                       missing; resend from {@code expectedSequence})
 * @param code           problem code for REJECTED / CONFLICT / DEFERRED
 * @param message        human-readable reason
 * @param replayed       the event id was already received; this is the stored outcome
 * @param forwardPending applied here, but orders-service was unreachable; forwarded by the retry queue
 */
public record EventResult(String eventId, long sequence, String outcome, String code, String message,
                          boolean replayed, boolean forwardPending) {

    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";
    public static final String CONFLICT = "CONFLICT";
    public static final String DEFERRED = "DEFERRED";
}
