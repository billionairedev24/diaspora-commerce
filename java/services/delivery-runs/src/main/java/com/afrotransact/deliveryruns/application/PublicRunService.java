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

    /** Active locations, by name. */
    List<Location> locations();

    LocationPage locationPage(String slug);

    /** 404 for draft runs and inactive locations. */
    Advertorial advertorial(UUID runId);
}
