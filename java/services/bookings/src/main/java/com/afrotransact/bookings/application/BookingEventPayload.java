package com.afrotransact.bookings.application;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.Provider;

/**
 * Payload of {@code booking.*} events (contracts/asyncapi/fragments/bookings.yaml). It follows notifications'
 * convention: a {@code recipient} block plus template fields (firstName, serviceName, providerName, startsAt,
 * location, bookingReference, manageUrl).
 */
public record BookingEventPayload(Recipient recipient, String firstName, String serviceName, String providerName,
                                  String startsAt, String location, String bookingReference, String manageUrl,
                                  UUID bookingId, String orgId, UUID memberId, UUID serviceId, String status,
                                  String previousStatus, Instant startAt, Instant endAt, Instant proposedStartAt,
                                  long priceMinor, long depositMinor, String currency, String policy,
                                  long refundedMinor, long providerFeeMinor, String reason, String cancelledBy,
                                  long balanceMinor, String balanceState, String balanceFailure) {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.US);

    /** Who notifications should reach: the customer. */
    public record Recipient(String id, String email, String phone, String timeZone) {
    }

    static BookingEventPayload of(Booking b, Provider p, String previousStatus, String manageUrlBase) {
        String where = b.location() != null ? b.location()
                : switch (b.service().mode()) {
                    case VIRTUAL -> "Online / video";
                    case AT_HOME -> "Home visit";
                    case IN_PERSON -> p.location() == null ? p.displayName() : p.location();
                };
        return new BookingEventPayload(
                new Recipient(b.customerId(), b.customer().email(), b.customer().phone(), p.timeZone().getId()),
                b.customer().firstName(), b.service().name(), p.displayName(),
                WHEN.format(b.startAt().atZone(p.timeZone())), where, b.reference(), manageUrlBase + b.reference(),
                b.id(), b.orgId(), b.memberId(), b.service().serviceId(), b.status().name(), previousStatus,
                b.startAt(), b.endAt(), b.proposedStartAt(), b.service().priceMinor(), b.service().depositMinor(),
                b.service().currency(), b.policy().kind().name(), b.refundedMinor(), b.providerFeeMinor(), b.reason(),
                b.cancelledBy() == null ? null : b.cancelledBy().name(), b.service().balanceMinor(),
                b.balance().state().name(), b.balance().failure());
    }
}
