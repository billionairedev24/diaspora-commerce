package com.afrotransact.identity.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.PushDevice;

/** The signed-in shopper's or driver's push device registrations. */
public interface DeviceService {

    List<PushDevice> list();

    /** Registers (or refreshes) a push token; driver-app tokens need {@code delivery:operate}. */
    PushDevice register(RegisterDeviceCommand command);

    /** Removes the caller's registration of this token (idempotent; used on sign-out). */
    void unregister(String token);

    /** Removes one of the caller's registrations by id (404 when it is not theirs). */
    void remove(UUID deviceId);
}
