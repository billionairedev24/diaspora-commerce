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
    List<Listing> search(String text, String category, String zip, int size);

    ProviderPage provider(String orgId);

    ServiceDetail service(UUID serviceId);

    /** Free start times per day for the next {@code days} days (service → day). */
    List<DayCount> days(UUID serviceId, LocalDate from, int days, UUID memberId, List<String> addOnIds);

    /** Free start times on one date (day → time). Only slots that can actually be booked are offered. */
    List<OpenSlot> slots(UUID serviceId, LocalDate date, UUID memberId, List<String> addOnIds);

    /** Summary before paying: deposit now, balance later and the provider's policy. */
    Summary summary(UUID serviceId, List<String> addOnIds, ServiceMode mode);

    /** A search hit. */
    record Listing(ServiceOffering service, Provider provider, Instant nextAvailable, RatingSummary rating) {
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
