package com.afrotransact.bookings.adapter.in.module;

import java.util.List;

import com.afrotransact.bookings.api.ServiceSearchApi;
import com.afrotransact.bookings.application.PublicBookingsService;
import com.afrotransact.bookings.application.PublicBookingsService.Listing;
import com.afrotransact.bookings.application.PublicBookingsService.SearchQuery;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ServiceSearchApi}: the anonymous search behind {@code GET /v1/services/search} (alias of
 * {@code /v1/public/services/search}) through {@link PublicBookingsService}, with the same defaults the alias applies (no category,
 * default sort and radius). No scope over HTTP, none here; a caller principal is still required so that no code path reaches it
 * unnamed. An invalid ZIP is 422 and an unreachable geo service is 502, as over HTTP.
 */
@Component
class ServiceSearchApiAdapter implements ServiceSearchApi {

    private final PublicBookingsService catalog;
    private final ModuleEntry entry;

    ServiceSearchApiAdapter(PublicBookingsService catalog, ActorResolver actors) {
        this.catalog = catalog;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<ServiceListing> search(String query, String zip, int size) {
        return entry.run(() -> catalog.search(new SearchQuery(query, null, zip, null, size, null)).services().stream()
                .map(ServiceSearchApiAdapter::listing).toList(), BookingsFailures::map);
    }

    private static ServiceListing listing(Listing l) {
        return new ServiceListing(l.service().id().toString(), l.service().name(), l.provider().displayName(),
                l.service().description(), l.service().priceMinor(), l.service().currency(), l.service().depositMinor(),
                l.rating().average(), l.nextAvailable() == null ? null : l.nextAvailable().toString(), l.provider().location());
    }
}
