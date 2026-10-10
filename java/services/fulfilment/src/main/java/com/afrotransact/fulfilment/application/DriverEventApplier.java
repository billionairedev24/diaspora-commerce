package com.afrotransact.fulfilment.application;

/** Applies one driver event in its own transaction (sequence check, idempotency, rules, side effects). */
public interface DriverEventApplier {

    /** @return the outcome; {@code lastSequence} of the device is read with {@link #lastSequence(String)} */
    EventResult apply(DriverContext driver, String deviceId, DriverEvent event, String driverBearer);

    /** Raises an SOS once per event id (outside the sequence order). @return true when newly raised */
    boolean raiseSos(DriverContext driver, DriverEvent event);

    long lastSequence(String deviceId);
}
