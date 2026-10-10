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

    /** Identity role keys used to scope practitioners to their own calendar. */
    public static final String ROLE_OWNER = "provider.owner";
    public static final String ROLE_MANAGER = "provider.manager";
    public static final String ROLE_PRACTITIONER = "provider.practitioner";

    private BookingsPermissions() {
    }
}
