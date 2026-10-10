package com.afrotransact.bookings.adapter.in.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.adapter.in.web.BookingDtos.ActivityResponse;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.BalancePaymentResponse;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.BookingResponse;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.CancelResponse;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.CreateBookingRequest;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.CreateBookingResponse;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.QuoteResponse;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.ReasonRequest;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.ReviewRequest;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.ReviewResponse;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.TimeRequest;
import com.afrotransact.bookings.application.BookingService;
import com.afrotransact.bookings.application.BookingService.BookingResult;
import com.afrotransact.bookings.application.BookingService.Cancelled;
import com.afrotransact.bookings.application.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Bookings", description = "Booking flow for customers (CUSTOMER token) and providers (bookings:manage). "
        + "Every write takes an Idempotency-Key and is safe to retry.")
@RestController
@RequestMapping("/v1/bookings")
class BookingController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final BookingService bookings;
    private final ReviewService reviews;

    BookingController(BookingService bookings, ReviewService reviews) {
        this.bookings = bookings;
        this.reviews = reviews;
    }

    @Operation(summary = "Book a slot (customer)", description = "Holds the slot (a database exclusion constraint "
            + "makes double booking impossible) and creates the payments-service PaymentIntent for the deposit "
            + "(manual capture: deposit held now, charged on confirmation, balance on completion). Returns the "
            + "client secret for Stripe.js / PaymentSheet. 409 bookings.slot-taken when the time was just taken.")
    @PostMapping
    ResponseEntity<CreateBookingResponse> create(
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest body) {
        BookingResult r = bookings.create(body.toCommand(), idempotencyKey);
        CreateBookingResponse response = new CreateBookingResponse(BookingResponse.of(r.booking(), true),
                r.replayed());
        return r.replayed() ? ResponseEntity.ok(response)
                : ResponseEntity.created(URI.create("/v1/bookings/" + r.booking().id())).body(response);
    }

    @Operation(summary = "Your bookings (customer), active first")
    @GetMapping("/mine")
    List<BookingResponse> mine() {
        return bookings.mine().stream().map(BookingResponse::of).toList();
    }

    @Operation(summary = "A booking (its customer, or the provider with bookings:read)")
    @GetMapping("/{id}")
    BookingResponse get(@PathVariable UUID id) {
        return BookingResponse.of(bookings.get(id));
    }

    @Operation(summary = "History of a booking (who did what, and the money it moved)")
    @GetMapping("/{id}/history")
    List<ActivityResponse> history(@PathVariable UUID id) {
        return bookings.history(id).stream().map(ActivityResponse::of).toList();
    }

    @Operation(summary = "Refresh the deposit authorisation (and an off-session balance charge) from payments",
            description = "Call after Stripe.js confirmed the card, or after the customer authenticated the "
                    + "off-session balance charge; payments' intent events do the same asynchronously.")
    @PostMapping("/{id}/payment/sync")
    BookingResponse syncPayment(@PathVariable UUID id) {
        return BookingResponse.of(bookings.syncPayment(id));
    }

    @Operation(summary = "The balance and its off-session charge (long-dated bookings)",
            description = "When the customer's bank asks for authentication (state ACTION_REQUIRED) the customer "
                    + "gets the charge's clientSecret to confirm with Stripe.js / PaymentSheet, then calls "
                    + "POST /payment/sync. Providers see the state without the secret.")
    @GetMapping("/{id}/balance-payment")
    BalancePaymentResponse balancePayment(@PathVariable UUID id) {
        return BalancePaymentResponse.of(bookings.balancePayment(id));
    }

    @Operation(summary = "Rate a completed booking (customer, once): 1–5 stars and an optional text",
            description = "Ratings are averaged per service in search. 409 bookings.not-reviewable before "
                    + "completion, 409 bookings.already-reviewed the second time.")
    @PostMapping("/{id}/review")
    ResponseEntity<ReviewResponse> review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReviewResponse.of(reviews.rate(id, body.rating(), body.text())));
    }

    @Operation(summary = "What cancelling now refunds, keeps or releases",
            description = "Customers see the exact refund under the policy the booking was made with before they "
                    + "cancel; providers see the late-cancellation fee.")
    @GetMapping("/{id}/cancellation-quote")
    QuoteResponse quote(@PathVariable UUID id) {
        return QuoteResponse.of(bookings.cancellationQuote(id));
    }

    @Operation(summary = "Cancel (customer: policy refund; provider: full refund, fee + strike under 24 h)",
            description = "After the deposit capture the uncaptured balance hold is released (or the open "
                    + "off-session balance charge cancelled).")
    @PostMapping("/{id}/cancel")
    CancelResponse cancel(@PathVariable UUID id,
                          @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                          @Valid @RequestBody(required = false) ReasonRequest body) {
        Cancelled c = bookings.cancel(id, body == null ? null : body.reason(), idempotencyKey);
        return new CancelResponse(BookingResponse.of(c.booking()), QuoteResponse.of(c.quote()));
    }

    @Operation(summary = "Customer moves the appointment",
            description = "Allowed inside the policy's free-change window and reschedule limit; moves straight away "
                    + "when the provider auto-accepts, otherwise waits for the provider (RESCHEDULE_REQUESTED).")
    @PostMapping("/{id}/reschedule")
    BookingResponse reschedule(@PathVariable UUID id,
                               @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                               @Valid @RequestBody TimeRequest body) {
        return BookingResponse.of(bookings.reschedule(id, body.startAt(), idempotencyKey));
    }

    @Operation(summary = "Accept the pending new time",
            description = "Customer accepts the provider's proposal, or the provider accepts the customer's request.")
    @PostMapping("/{id}/new-time/accept")
    BookingResponse acceptNewTime(@PathVariable UUID id,
                                  @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey) {
        return BookingResponse.of(bookings.acceptNewTime(id, idempotencyKey));
    }

    @Operation(summary = "Decline the pending new time",
            description = "Provider keeps the original time; a customer declining the provider's proposal cancels "
                    + "with a full refund.")
    @PostMapping("/{id}/new-time/decline")
    BookingResponse declineNewTime(@PathVariable UUID id,
                                   @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey) {
        return BookingResponse.of(bookings.declineNewTime(id, idempotencyKey));
    }

    @Operation(summary = "Confirm a request (provider): the deposit is captured")
    @PostMapping("/{id}/confirm")
    BookingResponse confirm(@PathVariable UUID id,
                            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey) {
        return BookingResponse.of(bookings.confirm(id, idempotencyKey));
    }

    @Operation(summary = "Decline a request with a reason (provider): the deposit hold is released")
    @PostMapping("/{id}/decline")
    BookingResponse decline(@PathVariable UUID id,
                            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                            @Valid @RequestBody(required = false) ReasonRequest body) {
        return BookingResponse.of(bookings.decline(id, body == null ? null : body.reason(), idempotencyKey));
    }

    @Operation(summary = "Propose a new time (provider)", description = "Must be inside the provider's "
            + "availability; the customer accepts or declines.")
    @PostMapping("/{id}/propose-time")
    BookingResponse proposeTime(@PathVariable UUID id,
                                @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
                                @Valid @RequestBody TimeRequest body) {
        return BookingResponse.of(bookings.proposeTime(id, body.startAt(), idempotencyKey));
    }

    @Operation(summary = "Mark complete (provider): the balance is charged and the settlement hold starts",
            description = "The balance is captured on the original authorisation, or on the off-session charge "
                    + "of a long-dated booking; a balance that could not be collected flags the booking "
                    + "(balance.state NEEDS_ATTENTION, event booking.balance_unpaid).")
    @PostMapping("/{id}/complete")
    BookingResponse complete(@PathVariable UUID id,
                             @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey) {
        return BookingResponse.of(bookings.complete(id, idempotencyKey));
    }

    @Operation(summary = "Mark no-show (provider, after the start time): deposit kept, or full price per policy",
            description = "Without full price the uncaptured balance hold is released.")
    @PostMapping("/{id}/no-show")
    ResponseEntity<BookingResponse> noShow(@PathVariable UUID id,
                                           @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 160)
                                           String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.OK).body(BookingResponse.of(bookings.noShow(id, idempotencyKey)));
    }
}
