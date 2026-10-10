package com.afrotransact.bookings.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.ServiceOffering;

/** Persistence port for providers' services and prices. */
public interface OfferingRepository {

    ServiceOffering insert(ServiceOffering offering);

    ServiceOffering update(ServiceOffering offering);

    Optional<ServiceOffering> find(UUID id);

    List<ServiceOffering> byOrg(String orgId, boolean activeOnly);

    /** Active services whose name, description, category or provider name match {@code text} (null = any). */
    List<ServiceOffering> search(String text, String category, int limit);
}
