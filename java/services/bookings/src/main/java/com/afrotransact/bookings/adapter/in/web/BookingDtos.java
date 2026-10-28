package com.afrotransact.bookings.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.application.BookingService.BalancePayment;
import com.afrotransact.bookings.application.BookingService.CreateBooking;
import com.afrotransact.bookings.application.EarningsView;
import com.afrotransact.bookings.domain.Activity;
import com.afrotransact.bookings.domain.BalanceCharge;
import com.afrotransact.bookings.domain.BalanceState;
import com.afrotransact.bookings.domain.BookedService;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingEarnings;
import com.afrotransact.bookings.domain.CancellationQuote;
import com.afrotransact.bookings.domain.CustomerContact;
import com.afrotransact.bookings.domain.Review;
import com.afrotransact.bookings.domain.ServiceMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
                            String providerName, String memberName,
                            @Schema(description = "The service's category (selects payments' connection fee); "
                                    + "'services' for bookings made before it was recorded") String category) {

        static BookedServiceDto of(BookedService s) {
            return new BookedServiceDto(s.serviceId(), s.name(), s.durationMinutes(), s.priceMinor(),
                    s.depositMinor(), s.balanceMinor(), s.currency(), s.addOns().stream().map(a -> a.name()).toList(),
                    s.mode().name(), s.providerName(), s.memberName(), s.category());
        }
    }

    @Schema(name = "BookingPayment")
    record PaymentDto(UUID paymentId, String state, @Schema(description = "Only in the create response, for "
            + "Stripe.js / PaymentSheet confirmation") String clientSecret, String failureMessage,
                      long authorizedMinor, long depositCapturedMinor, long balanceCapturedMinor, long refundedMinor,
                      long releasedMinor, long providerFeeMinor, BalanceDto balance) {
    }

    @Schema(name = "BookingBalance", description = "The balance (price − deposit). Long-dated bookings: the balance "
            + "hold is released after the deposit capture and the balance is charged off-session on the saved card "
            + "before the appointment. The client secret for authentication is served by GET "
            + "/v1/bookings/{id}/balance-payment to the customer only.")
    record BalanceDto(
            @Schema(description = "ON_AUTHORISATION, DUE, PROCESSING, ACTION_REQUIRED (the customer must "
                    + "authenticate), FAILED (retried), AUTHORIZED, CAPTURED, NEEDS_ATTENTION (not collected "
                    + "automatically), RELEASED") String state,
            @Schema(description = "payments' id of the off-session charge") UUID paymentId, int attempts,
            String failureMessage, Instant nextAttemptAt, boolean needsCustomerAction, boolean needsAttention) {

        static BalanceDto of(BalanceCharge b) {
            return new BalanceDto(b.state().name(), b.paymentId(), b.attempts(), b.failure(), b.nextAttemptAt(),
                    b.state() == BalanceState.ACTION_REQUIRED, b.state() == BalanceState.NEEDS_ATTENTION);
        }
    }

    @Schema(name = "BookingBalancePayment", description = "The off-session balance charge; clientSecret only for "
            + "the customer while their bank asks them to authenticate (confirm with Stripe.js / PaymentSheet, then "
            + "POST /v1/bookings/{id}/payment/sync)")
    record BalancePaymentResponse(UUID bookingId, String reference, String state, UUID paymentId, long amountMinor,
                                  String currency, String clientSecret, String failureMessage, int attempts,
                                  Instant nextAttemptAt) {

        static BalancePaymentResponse of(BalancePayment p) {
            return new BalancePaymentResponse(p.bookingId(), p.reference(), p.state().name(), p.paymentId(),
                    p.amountMinor(), p.currency(), p.clientSecret(), p.failureMessage(), p.attempts(),
                    p.nextAttemptAt());
        }
    }

    @Schema(name = "ReviewRequest", description = "Rate a completed booking once")
    record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = Review.MAX_TEXT) String text) {
    }

    @Schema(name = "Review", description = "A review as its customer, the provider and moderators see it")
    record ReviewResponse(UUID bookingId, UUID serviceId, String serviceName, String orgId, String customerId,
                          String reviewerName, int rating, String text, Instant createdAt,
                          @Schema(description = "PENDING (waiting for moderation), APPROVED (public) or REJECTED")
                          String status,
                          @Schema(description = "Last moderation decision; null while pending")
                          ModerationDto moderation,
                          @Schema(description = "The provider's reply; null until they reply") ReplyDto reply) {

        static ReviewResponse of(Review r) {
            Review.Moderation m = r.moderation();
            return new ReviewResponse(r.bookingId(), r.serviceId(), r.serviceName(), r.orgId(), r.customerId(),
                    r.reviewerName(), r.rating(), r.text(), r.createdAt(), r.status().name(),
                    m == null ? null : new ModerationDto(m.by(), m.at(),
                            m.reason() == null ? null : m.reason().name(), m.note()),
                    ReplyDto.of(r.reply()));
        }
    }

    @Schema(name = "ReviewModeration")
    record ModerationDto(String moderatedBy, Instant moderatedAt, String reasonCode, String note) {
    }

    @Schema(name = "ReviewReply", description = "The provider's single public reply")
    record ReplyDto(String text, Instant repliedAt) {

        static ReplyDto of(Review.ProviderReply reply) {
            return reply == null ? null : new ReplyDto(reply.text(), reply.at());
        }
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
                    b.providerFeeMinor(), BalanceDto.of(b.balance())), b.holdExpiresAt(), b.requestExpiresAt(), b.reason(),
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
                          String status, long heldMinor, long collectedMinor,
                          @Schema(description = "AfroTransact's connection fee rate (payments, kind SERVICE)")
                          int commissionBps,
                          @Schema(description = "Connection fee on what was collected") long commissionMinor,
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
