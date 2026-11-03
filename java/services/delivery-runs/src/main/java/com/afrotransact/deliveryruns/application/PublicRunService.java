package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Location;

/** Public (no sign-in) reads for the storefront, checkout and content. */
public interface PublicRunService {

    /**
     * Open runs whose location covers the address: a 5-digit ZIP (its centre) or coordinates. Polygon areas are
     * matched with geo's zone coverage, radius areas with geo's distances.
     */
    List<AvailableRun> available(String zip, Double lat, Double lng);

    /**
     * The same for a member with early access (S323): announced runs opening within {@code earlyAccessHours} are listed
     * too. The run itself only takes the member's order when the reservation names the same hours.
     */
    List<AvailableRun> available(String zip, Double lat, Double lng, int earlyAccessHours);

    /**
     * The active locations whose area covers the address (a 5-digit ZIP's centre or coordinates), by name; empty when
     * geo does not know the ZIP. The same coverage {@link #available} uses.
     */
    List<Location> coveringLocations(String zip, Double lat, Double lng);

    /** Open runs (and, with early access, announced runs opening soon) of these locations, soonest cutoff first. */
    List<AvailableRun> available(List<Location> covering, int earlyAccessHours);

    /** Active locations, by name. */
    List<Location> locations();

    LocationPage locationPage(String slug);

    /** 404 for draft runs and inactive locations. */
    Advertorial advertorial(UUID runId);
}
