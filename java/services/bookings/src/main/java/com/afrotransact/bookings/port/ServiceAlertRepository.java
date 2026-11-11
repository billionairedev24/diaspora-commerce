package com.afrotransact.bookings.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence port for "tell me when a provider joins" alerts. */
public interface ServiceAlertRepository {

    /** Opens an alert unless the same email already waits for the same ZIP and category. */
    void open(Alert alert);

    /** Alerts still waiting whose place could be served by a provider based within {@code miles} of the point. */
    List<Alert> openNear(double lat, double lon, int miles);

    void markNotified(UUID id, Instant at);

    /** One alert. {@code category} null = any service. */
    record Alert(UUID id, String email, String zip, String city, String state, double lat, double lon,
                 String category, String locale, Instant createdAt) {
    }
}
