package com.afrotransact.fulfilment.port;

import java.time.Instant;
import java.util.List;

import com.afrotransact.fulfilment.domain.SosAlert;

/** SOS alerts. */
public interface SosRepository {

    /** @return true when the alert is new (first delivery of this event id) */
    boolean insert(SosAlert alert);

    List<SosAlert> open();

    boolean acknowledge(String eventId, String by, Instant at);
}
