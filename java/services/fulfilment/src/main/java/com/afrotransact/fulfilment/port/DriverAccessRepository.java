package com.afrotransact.fulfilment.port;

import java.util.Optional;

import com.afrotransact.fulfilment.domain.DriverAccess;

/** Last known driver access state (grants and revocations). */
public interface DriverAccessRepository {

    Optional<DriverAccess> find(String driverId);

    /** Stores the state unless a newer change is already recorded. @return true when stored */
    boolean apply(DriverAccess access);
}
