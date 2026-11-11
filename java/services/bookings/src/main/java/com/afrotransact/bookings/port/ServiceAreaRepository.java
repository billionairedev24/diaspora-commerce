package com.afrotransact.bookings.port;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.bookings.domain.ServiceArea;

/** Persistence port for providers' service areas. */
public interface ServiceAreaRepository {

    Optional<ServiceArea> find(String orgId);

    /** Inserts or replaces the provider's area. */
    ServiceArea save(ServiceArea area);

    /** The areas of these organisations, by org id (organisations without one are absent). */
    Map<String, ServiceArea> byOrgs(Collection<String> orgIds);
}
