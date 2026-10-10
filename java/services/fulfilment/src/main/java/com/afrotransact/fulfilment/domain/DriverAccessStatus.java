package com.afrotransact.fulfilment.domain;

/** Whether a driver may operate (mirrors delivery:operate grants and revocations from identity-service). */
public enum DriverAccessStatus {
    ACTIVE,
    REVOKED
}
