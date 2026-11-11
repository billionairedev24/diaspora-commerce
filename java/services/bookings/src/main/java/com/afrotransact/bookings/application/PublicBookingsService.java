package com.afrotransact.bookings.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.ServiceMode;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.TeamMember;

/** Anonymous shopper reads: find services, see a provider, pick a day and a time, read the summary. */
public interface PublicBookingsService {

    /**
     * Active services matching a text (name, description, category, provider) with the next free time and their
     * rating.
     *
     * @param zip 5-digit US ZIP: only providers whose service area (geo zones) covers it, plus virtual services
     *            (null = anywhere)
     */
    SearchResult search(SearchQuery query);

    /** Search-box suggestions: service types and provider names containing the text. */
    List<String> suggestions(String text, int limit);

    ProviderPage provider(String orgId);

    ServiceDetail service(UUID serviceId);

    /** Free start times per day for the next {@code days} days (service → day). */
    List<DayCount> days(UUID serviceId, LocalDate from, int days, UUID memberId, List<String> addOnIds);

    /** Free start times on one date (day → time). Only slots that can actually be booked are offered. */
    List<OpenSlot> slots(UUID serviceId, LocalDate date, UUID memberId, List<String> addOnIds);

    /** Summary before paying: deposit now, balance later and the provider's policy. */
    Summary summary(UUID serviceId, List<String> addOnIds, ServiceMode mode);

    /** A search hit. */
    record Listing(ServiceOffering service, Provider provider, Instant nextAvailable, RatingSummary rating,
                   AreaView area) {
    }

    /**
     * Where the provider is relative to the customer.
     *
     * @param baseCity      provider base city
     * @param baseState     provider base state
     * @param distanceMiles base to the customer's ZIP centroid, one decimal
     * @param zone          the metro zone of the base city, or null
     * @param serves        cities served (the customer's first), for "Serves Georgetown, Hutto, Pflugerville"
     * @param nearest       true when outside the provider's usual coverage (a suggestion, not a match)
     */
    record AreaView(String baseCity, String baseState, double distanceMiles, String zone, List<String> serves,
                    boolean nearest) {
    }

    /** Sort orders of the result list. */
    enum Sort { NEAREST, SOONEST, PRICE, RATING }

    /** A search request. {@code zip} null = no location (every provider, no distance). */
    record SearchQuery(String text, String category, String zip, Sort sort, int size) {
    }

    /** The customer's place and its metro zone. */
    record Place(String zip, String city, String state, String zone) {
    }

    /** Services per category among the providers that serve the place. */
    record CategoryCount(String category, int count) {
    }

    /**
     * A location search. {@code coverage}: LOCAL (services serve the place), NEAREST (none do, {@code nearest} has
     * providers outside their usual coverage, within the staff-set maximum), NONE (nothing, not even nearby), ANY (no
     * location was given).
     */
    record SearchResult(Place place, String coverage, List<Listing> services, List<Listing> nearest,
                        List<CategoryCount> categories) {
    }

    /** A provider's public booking page. */
    record ProviderPage(Provider provider, List<ServiceOffering> services, List<TeamMember> team) {
    }

    /** A service page. */
    record ServiceDetail(ServiceOffering service, Provider provider, List<TeamMember> team) {
    }

    /** Number of free start times on a date. */
    record DayCount(LocalDate date, int count) {
    }

    /** A bookable start time; {@code memberIds} lists who is free then. */
    record OpenSlot(Instant start, Instant end, List<UUID> memberIds) {
    }

    /** Booking summary. */
    record Summary(UUID serviceId, String serviceName, String providerName, int durationMinutes, long priceMinor,
                   long depositNowMinor, long balanceLaterMinor, String currency, ServiceMode mode,
                   List<String> addOns, String policy, String policyText, boolean instantBooking,
                   long requestSlaMinutes) {
    }
}
