package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.afrotransact.bookings.application.PaymentEventIngest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S232 reviews: moderation before public display (event for trust-ops' queue, staff / service approve or reject),
 * a single provider reply, and public lists per provider and per service.
 */
class ReviewModerationIT extends BookingsIntegrationTest {

    private final PaymentEventIngest paymentEvents;

    @Autowired
    ReviewModerationIT(PaymentEventIngest paymentEvents) {
        this.paymentEvents = paymentEvents;
    }

    @Test
    void reviewsAreModeratedThenListedPubliclyWithTheProvidersSingleReply() {
        Setup s = provider(false);
        String good = completed(s, "cust-rev-1").path("id").asString();
        String bad = completed(s, "cust-rev-2").path("id").asString();

        TestSupport.as(TestSupport.customer("cust-rev-1"));
        JsonNode submitted = ok(send("POST", "/v1/bookings/" + good + "/review",
                Map.of("rating", 5, "text", "Neat rows and on time"), null), 201);
        assertThat(submitted.path("status").asString()).isEqualTo("PENDING");
        assertThat(submitted.path("reviewerName").asString()).isEqualTo("Adaeze N.");
        TestSupport.as(TestSupport.customer("cust-rev-2"));
        ok(send("POST", "/v1/bookings/" + bad + "/review", Map.of("rating", 1, "text", "Call me on 713 555 0100"),
                null), 201);

        // Queued for trust-ops: the event carries what the moderator needs.
        assertThat(outboxTypes(good)).contains("booking.review.submitted");
        JsonNode event = JSON.readTree(jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id "
                + "and type = 'booking.review.submitted'").param("id", good).query(String.class).single());
        assertThat(event.path("contentType").asString()).isEqualTo("BOOKING_REVIEW");
        assertThat(event.path("text").asString()).isEqualTo("Neat rows and on time");
        assertThat(event.path("orgId").asString()).isEqualTo(s.org());
        assertThat(event.path("approvePath").asString()).isEqualTo("/v1/reviews/" + good + "/approve");

        // Nothing is public yet.
        TestSupport.reset();
        JsonNode none = ok(send("GET", "/v1/public/providers/" + s.org() + "/reviews", null, null));
        assertThat(none.path("total").asLong()).isZero();
        assertThat(none.path("ratingCount").asInt()).isZero();

        // Providers can't moderate, nor reply before publication.
        TestSupport.as(TestSupport.owner(s.org()));
        problem(send("POST", "/v1/reviews/" + good + "/approve", Map.of(), null), 403, "auth.missing-permission");
        problem(send("POST", "/v1/providers/" + s.org() + "/reviews/" + good + "/reply", Map.of("text", "Thanks!"),
                null), 409, "bookings.review-not-published");

        // The moderation queue (staff) lists both, oldest first.
        TestSupport.as(TestSupport.moderator());
        JsonNode queue = ok(send("GET", "/v1/reviews?status=PENDING&size=50", null, null));
        assertThat(queue.path("items").valueStream().map(r -> r.path("bookingId").asString()))
                .contains(good, bad);

        // trust-ops approves one (twice: idempotent) and rejects the other.
        TestSupport.as(TestSupport.trustOps());
        JsonNode approved = ok(send("POST", "/v1/reviews/" + good + "/approve", Map.of(), null));
        assertThat(approved.path("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.path("moderation").path("moderatedBy").asString()).isEqualTo("trust-ops-service");
        ok(send("POST", "/v1/reviews/" + good + "/approve", Map.of(), null));
        problem(send("POST", "/v1/reviews/" + bad + "/reject", Map.of("reasonCode", "OTHER"), null), 422,
                "bookings.invalid-moderation");
        JsonNode rejected = ok(send("POST", "/v1/reviews/" + bad + "/reject",
                Map.of("reasonCode", "PERSONAL_INFORMATION", "note", "Phone number"), null));
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(outboxTypes(good).stream().filter("booking.review.approved"::equals).count()).isEqualTo(1);
        assertThat(outboxTypes(bad)).contains("booking.review.rejected");

        // The provider replies once.
        TestSupport.as(TestSupport.owner(s.org()));
        JsonNode replied = ok(send("POST", "/v1/providers/" + s.org() + "/reviews/" + good + "/reply",
                Map.of("text", "Thank you, see you next time!"), null));
        assertThat(replied.path("reply").path("text").asString()).isEqualTo("Thank you, see you next time!");
        problem(send("POST", "/v1/providers/" + s.org() + "/reviews/" + good + "/reply", Map.of("text", "Again"),
                null), 409, "bookings.already-replied");
        assertThat(outboxTypes(good)).contains("booking.review.replied");
        JsonNode own = ok(send("GET", "/v1/providers/" + s.org() + "/reviews", null, null));
        assertThat(own.path("total").asLong()).as("the provider sees rejected reviews too").isEqualTo(2);

        // Public lists: approved only, with the reply and without the customer id.
        TestSupport.reset();
        JsonNode byProvider = ok(send("GET", "/v1/public/providers/" + s.org() + "/reviews", null, null));
        assertThat(byProvider.path("total").asLong()).isEqualTo(1);
        assertThat(byProvider.path("rating").asDouble()).isEqualTo(5.0);
        assertThat(byProvider.path("ratingCount").asInt()).isEqualTo(1);
        JsonNode item = byProvider.path("items").get(0);
        assertThat(item.path("reviewId").asString()).isEqualTo(good);
        assertThat(item.path("reviewerName").asString()).isEqualTo("Adaeze N.");
        assertThat(item.path("reply").path("text").asString()).isEqualTo("Thank you, see you next time!");
        assertThat(item.has("customerId")).isFalse();
        JsonNode byService = ok(send("GET", "/v1/public/services/" + s.serviceId() + "/reviews", null, null));
        assertThat(byService.path("total").asLong()).isEqualTo(1);
        assertThat(byService.path("items").get(0).path("serviceId").asString()).isEqualTo(s.serviceId().toString());

        // Taken down after publication: gone from the public list.
        TestSupport.as(TestSupport.moderator());
        ok(send("POST", "/v1/reviews/" + good + "/reject", Map.of("reasonCode", "CONFLICT_OF_INTEREST"), null));
        TestSupport.reset();
        assertThat(ok(send("GET", "/v1/public/services/" + s.serviceId() + "/reviews", null, null)).path("total")
                .asLong()).isZero();
        problem(send("GET", "/v1/public/services/" + UUID.randomUUID() + "/reviews", null, null), 404,
                "bookings.service-not-found");
    }

    /** A booking of the provider's service, confirmed and completed by the provider. */
    private JsonNode completed(Setup s, String customer) {
        JsonNode b = book(customer, s.serviceId(), firstSlot(s.serviceId()));
        String id = b.path("id").asString();
        paymentEvents.handle(UUID.randomUUID(), "payments.intent.succeeded", b.path("reference").asString(),
                "AUTHORIZED", null);
        TestSupport.as(TestSupport.owner(s.org()));
        ok(send("POST", "/v1/bookings/" + id + "/confirm", null, "confirm-" + id));
        JsonNode done = ok(send("POST", "/v1/bookings/" + id + "/complete", null, "complete-" + id));
        assertThat(done.path("status").asString()).isEqualTo("COMPLETED");
        TestSupport.reset();
        return done;
    }
}
