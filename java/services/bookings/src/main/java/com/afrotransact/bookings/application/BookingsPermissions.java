package com.afrotransact.bookings.application;

/**
 * Permission keys checked by bookings use cases (granted by identity-service roles). Customers need none: their
 * own bookings are checked by principal type and subject.
 */
public final class BookingsPermissions {

    /** Calendar, bookings and earnings of the organisation (Owner, Manager, Practitioner — own only —, Finance). */
    public static final String READ = "bookings:read";
    /** Act on bookings: confirm, decline, propose a time, complete, no-show, cancel (Owner, Manager, Practitioner). */
    public static final String MANAGE = "bookings:manage";
    /** Services, prices, deposits, add-ons and the cancellation policy (Owner, Manager). */
    public static final String SERVICES_WRITE = "bookings:services:write";
    /** Working hours, time off and booking rules (Owner, Manager; Practitioners for their own hours only). */
    public static final String AVAILABILITY_WRITE = "bookings:availability:write";
    /** Team members, roles and who performs which service (Owner, Manager). */
    public static final String TEAM_MANAGE = "bookings:team:manage";
    /**
     * Approve or reject customer reviews (S232): trust and safety staff and the trust-ops service client only
     * (principal type STAFF or SERVICE); provider members are refused even if granted it.
     */
    public static final String REVIEWS_MODERATE = "bookings:reviews:moderate";
    /**
     * Staff read the service-area policy and the metro zones: the Markets &amp; locations view permission
     * (identity V5__content_permissions), so no new grant is needed.
     */
    public static final String SERVICE_AREAS_VIEW = "content:markets.view";
    /**
     * Staff change the service-area policy (the default search radius per category and the minimum and maximum of a
     * provider's radius) and the metro zones, always with a reason: the Markets &amp; locations edit permission.
     */
    public static final String SERVICE_AREAS_MANAGE = "content:markets.edit";

    /** Identity role keys used to scope practitioners to their own calendar. */
    public static final String ROLE_OWNER = "provider.owner";
    public static final String ROLE_MANAGER = "provider.manager";
    public static final String ROLE_PRACTITIONER = "provider.practitioner";

    private BookingsPermissions() {
    }
}
