package com.afrotransact.fulfilment.domain;

/**
 * A fulfilment centre / node (same id as inventory-service's node, e.g. {@code HOU-FC1}): depot for driver routes and
 * site of pick and pack.
 *
 * @param id          node id
 * @param name        display name
 * @param kind        FULFILMENT_CENTRE, ORIGIN_HUB or WAREHOUSE
 * @param timezone    IANA zone of the site
 * @param addressLine street address
 * @param city        city
 * @param state       state / region
 * @param postalCode  ZIP
 * @param country     ISO 3166-1 alpha-2
 * @param location    geocoded position, null when unknown
 * @param coldChain   has cold storage for perishables
 * @param active      accepts work
 */
public record FulfilmentCentre(String id, String name, String kind, String timezone, String addressLine, String city,
                               String state, String postalCode, String country, GeoPoint location, boolean coldChain,
                               boolean active) {

    public FulfilmentCentre {
        if (id == null || !id.matches("[A-Z0-9][A-Z0-9-]{1,19}")) {
            throw new FulfilmentRuleViolation("fulfilment.centre-id-invalid",
                    "Centre id must be 2–20 upper-case letters, digits or dashes (e.g. HOU-FC1)");
        }
        if (name == null || name.isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.centre-name-required", "Name the centre");
        }
    }
}
