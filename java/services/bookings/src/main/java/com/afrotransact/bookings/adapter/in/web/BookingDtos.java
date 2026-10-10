package com.afrotransact.bookings.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.application.BookingService.CreateBooking;
import com.afrotransact.bookings.application.EarningsView;
import com.afrotransact.bookings.domain.Activity;
import com.afrotransact.bookings.domain.BookedService;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingEarnings;
import com.afrotransact.bookings.domain.CancellationQuote;
import com.afrotransact.bookings.domain.CustomerContact;
import com.afrotransact.bookings.domain.ServiceMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of booking endpoints. */
final class BookingDtos {

    private BookingDtos() {
    }

    @Schema(name = "CustomerContact")
    record CustomerDto(@NotBlank @Size(max = 120) String name, @Email @Size(max = 200) String email,
                       @Size(max = 40) @Schema(description = "E.164 preferred") String phone) {

        CustomerContact toDomain() {
            return new CustomerContact(name, email, phone);
        }

        static CustomerDto of(CustomerContact c) {
            return new CustomerDto(c.name(), c.email(), c.phone());
        }
    }

    @Schema(name = "CreateBookingRequest", description = "Service → day → time → summary, then book")
    record CreateBookingRequest(@NotNull UUID serviceId,
                                @Schema(description = "Preferred team member; empty = anyone free") UUID memberId,
                                @NotNull @Schema(description = "A start time from the slots endpoint") Instant startAt,
                                @NotNull ServiceMode mode,
                                @Size(max = 20) List<@NotBlank String> addOnIds,
                                @NotNull @Valid CustomerDto customer,
                                @Size(max = 300) @Schema(description = "Required for home visits") String location,
                                @Size(max = 1000) String notes,
                                @Pattern(regexp = "^(web|app)$") String channel) {

        CreateBooking toCommand() {
            return new CreateBooking(serviceId, memberId, startAt, mode, addOnIds, customer.toDomain(), location,
                    notes, channel == null ? "web" : channel);
        }
    }

    @Schema(name = "BookedService", description = "The service as booked (later price changes don't apply)")
    record BookedServiceDto(UUID serviceId, String name, int durationMinutes, long priceMinor, long depositMinor,
                            long balanceMinor, String currency, List<String> addOns, String mode,
                            String providerName, String memberName) {

        static BookedServiceDto of(BookedService s) {
            return new BookedServiceDto(s.serviceId(), s.name(), s.durationMinutes(), s.priceMinor(),
                    s.depositMinor(), s.balanceMinor(), s.currency(), s.addOns().stream().map(a -> a.name()).toList(),
                    s.mode().name(), s.providerName(), s.memberName());
        }
    }

    @Schema(name = "BookingPayment")
    record PaymentDto(UUID paymentId, String state, @Schema(description = "Only in the create response, for "
            + "Stripe.js / PaymentSheet confirmation") String clientSecret, String failureMessage,
                      long authorizedMinor, long depositCapturedMinor, long balanceCapturedMinor, long refundedMinor,
                      long releasedMinor, long providerFeeMinor) {
    }

    @Schema(name = "BookingPolicy", description = "The cancellation policy this booking was made under")
    record PolicyDto(String kind, String label, String text) {
    }

    @Schema(name = "Booking")
    record BookingResponse(UUID id, String reference, String orgId, UUID memberId, String customerId,
                           CustomerDto customer, BookedServiceDto service, String status, String statusLabel,
                           Instant startAt, Instant endAt, Instant proposedStartAt, String location, String notes,
                           String channel, PolicyDto policy, int reschedules, PaymentDto payment,
                           Instant holdExpiresAt, Instant requestExpiresAt, String reason, String cancelledBy,
                           Instant createdAt, Instant updatedAt, long version) {

        static BookingResponse of(Booking b) {
            return of(b, false);
        }

        static BookingResponse of(Booking b, boolean withSecret) {
            return new BookingResponse(b.id(), b.reference(), b.orgId(), b.memberId(), b.customerId(),
                    CustomerDto.of(b.customer()), BookedServiceDto.of(b.service()), b.status().name(),
                    b.status().label(), b.startAt(), b.endAt(), b.proposedStartAt(), b.location(), b.notes(),
                    b.channel(), new PolicyDto(b.policy().kind().name(), b.policy().label(), b.policy().text()),
                    b.reschedules(), new PaymentDto(b.paymentId(), b.paymentState().name(),
                    withSecret ? b.clientSecret() : null, b.paymentFailure(), b.authorizedMinor(),
                    b.depositCapturedMinor(), b.balanceCapturedMinor(), b.refundedMinor(), b.releasedMinor(),
                    b.providerFeeMinor()), b.holdExpiresAt(), b.requestExpiresAt(), b.reason(),
                    b.cancelledBy() == null ? null : b.cancelledBy().name(), b.createdAt(), b.updatedAt(),
                    b.version());
        }
    }

    @Schema(name = "CreateBookingResponse")
    record CreateBookingResponse(BookingResponse booking, boolean replayed) {
    }

    @Schema(name = "CancellationQuote", description = "Exactly what cancelling now refunds, keeps or releases")
    record QuoteResponse(String by, int refundSharePercent, long depositMinor, long refundMinor,
                         boolean releaseAuthorisation, long releasedMinor, long keptMinor, long providerFeeMinor,
                         String policy, String explanation, String currency) {

        static QuoteResponse of(CancellationQuote q) {
            return q == null ? null : new QuoteResponse(q.by().name(), q.refundShareBps() / 100, q.depositMinor(),
                    q.refundMinor(), q.releaseAuthorisation(), q.releasedMinor(), q.keptMinor(), q.providerFeeMinor(),
                    q.policyLabel(), q.explanation(), q.currency());
        }
    }

    @Schema(name = "ReasonRequest", description = "Optional reason (cancel, decline)")
    record ReasonRequest(@Size(max = 500) String reason) {
    }

    @Schema(name = "CancelResponse")
    record CancelResponse(BookingResponse booking, QuoteResponse applied) {
    }

    @Schema(name = "NewTimeRequest")
    record TimeRequest(@NotNull Instant startAt) {
    }

    @Schema(name = "BookingActivity")
    record ActivityResponse(Instant at, String by, String action, String moneyKind, long amountMinor) {

        static ActivityResponse of(Activity a) {
            return new ActivityResponse(a.at(), a.by().name(), a.action(),
                    a.moneyKind() == null ? null : a.moneyKind().name(), a.amountMinor());
        }
    }

    @Schema(name = "BookingEarnings")
    record EarningsRowDto(UUID bookingId, String reference, String customer, Instant startAt, String serviceName,
                          String status, long heldMinor, long collectedMinor, int commissionBps, long commissionMinor,
                          long cardFeesMinor, long providerFeeMinor, long netMinor, long balanceDueMinor) {

        static EarningsRowDto of(EarningsView.Row r) {
            Booking b = r.booking();
            BookingEarnings e = r.earnings();
            return new EarningsRowDto(b.id(), b.reference(), b.customer().name(), b.startAt(), b.service().name(),
                    b.status().name(), e.heldMinor(), e.collectedMinor(), e.commissionBps(), e.commissionMinor(),
                    e.cardFeesMinor(), e.providerFeeMinor(), e.netMinor(), e.balanceDueMinor());
        }
    }

    @Schema(name = "Earnings")
    record EarningsResponse(String orgId, String currency, List<EarningsRowDto> rows, long collectedMinor,
                            long commissionMinor, long cardFeesMinor, long providerFeesMinor, long netMinor,
                            long depositsOnHoldMinor) {

        static EarningsResponse of(EarningsView v) {
            EarningsView.Totals t = v.totals();
            return new EarningsResponse(v.orgId(), v.currency(), v.rows().stream().map(EarningsRowDto::of).toList(),
                    t.collectedMinor(), t.commissionMinor(), t.cardFeesMinor(), t.providerFeesMinor(), t.netMinor(),
                    t.heldMinor());
        }
    }
}
