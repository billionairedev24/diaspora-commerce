package com.afrotransact.bookings.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.NamedArea;
import com.afrotransact.bookings.domain.RadiusRule;
import com.afrotransact.bookings.domain.ServiceArea;
import com.afrotransact.bookings.domain.ServiceZone;
import com.afrotransact.bookings.domain.ZoneCity;

/** A provider's service area (Provider Hub "Service area"): base location, radius, extra areas. */
public interface ServiceAreaService {

    /** The saved area (if any), the radius bounds for this provider's services and the zones to pick from. */
    AreaView get(String orgId);

    /** Saves the area: the base ZIP is placed through geo, the radius must fit the staff-set bounds. */
    AreaView save(String orgId, AreaCommand command);

    /** What a base ZIP and radius would cover, without saving (the live preview of the form). */
    Preview preview(String orgId, String zip, int radiusMiles);

    /**
     * Provider-entered area.
     *
     * @param baseZip      ZIP of the base address
     * @param radiusMiles  null = the default for the provider's categories
     * @param extraAreas   named cities, ZIPs or zones
     */
    record AreaCommand(String baseZip, Integer radiusMiles, List<NamedArea> extraAreas) {
    }

    /** The area with the rules around it; {@code area} is null before the first save. */
    record AreaView(ServiceArea area, RadiusRule bounds, AreaPolicy policy, List<ServiceZone> zones) {
    }

    /** Cities a radius reaches around a base and the base's metro zone (null if none). */
    record Preview(String baseCity, String baseState, Optional<ServiceZone> zone, List<ZoneCity> cities) {
    }
}
