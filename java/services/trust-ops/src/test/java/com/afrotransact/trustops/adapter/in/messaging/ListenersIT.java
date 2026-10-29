package com.afrotransact.trustops.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.afrotransact.trustops.it.TrustOpsIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Kafka listeners, called directly with records shaped like the producers' outbox output. */
class ListenersIT extends TrustOpsIntegrationTest {

    private final CatalogProductEventListener products;
    private final AuditEventListener audit;
    private final OrderEventListener orders;
    private final AiHandoffEventListener handoffs;
    private final FulfilmentEventListener fulfilment;
    private final BookingReviewEventListener reviews;

    @Autowired
    ListenersIT(CatalogProductEventListener products, AuditEventListener audit, OrderEventListener orders,
                AiHandoffEventListener handoffs, FulfilmentEventListener fulfilment,
                BookingReviewEventListener reviews) {
        this.products = products;
        this.audit = audit;
        this.orders = orders;
        this.handoffs = handoffs;
        this.fulfilment = fulfilment;
        this.reviews = reviews;
    }

    /** A BookingsReviewEvent as bookings-service's outbox writes it. */
    private static String reviewEvent(UUID bookingId, String org, String status, int rating, String text) {
        return """
                {"contentType":"BOOKING_REVIEW","reviewId":"%s","bookingId":"%s","orgId":"%s",
                 "serviceId":"7d1f0b8e-0c55-4c8b-9a51-3f7f2b0c1a10","serviceName":"Box braids (medium)",
                 "customerId":"cust-77","reviewerName":"Kemi A.","rating":%d,"text":%s,"status":"%s",
                 "createdAt":"2026-10-09T12:00:00Z","moderatedBy":null,"moderatedAt":null,"reasonCode":null,
                 "moderationNote":null,"replyText":null,"repliedAt":null,
                 "approvePath":"/v1/reviews/%s/approve","rejectPath":"/v1/reviews/%s/reject"}
                """.formatted(bookingId, bookingId, org, rating, text == null ? "null" : "\"" + text + "\"",
                status, bookingId, bookingId);
    }

    private String reviewItem(UUID bookingId, String column) {
        return jdbc.sql("select " + column + "::text from moderation_item where booking_id = :b")
                .param("b", bookingId).query(String.class).single();
    }

    @Test
    void submittedReviewIsQueuedOnceAndBookingsDecisionClosesIt() {
        UUID bookingId = UUID.randomUUID();
        String org = orgId();
        UUID eventId = UUID.randomUUID();
        String submitted = reviewEvent(bookingId, org, "PENDING", 2, "Arrived 40 minutes late");

        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.submitted", eventId,
                submitted));
        assertThat(reviewItem(bookingId, "item_type")).isEqualTo("SERVICE_REVIEW");
        assertThat(reviewItem(bookingId, "status")).isEqualTo("PENDING");
        assertThat(reviewItem(bookingId, "seller_org_id")).isEqualTo(org);
        assertThat(reviewItem(bookingId, "rating")).isEqualTo("2");
        assertThat(reviewItem(bookingId, "review_text")).isEqualTo("Arrived 40 minutes late");
        assertThat(reviewItem(bookingId, "customer_id")).isEqualTo("cust-77");

        // Redelivery (same event-id) and a second submitted event for the same review add nothing.
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.submitted", eventId,
                submitted));
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.submitted",
                UUID.randomUUID(), submitted));
        assertThat(jdbc.sql("select count(*) from moderation_item where booking_id = :b").param("b", bookingId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from platform_inbox where event_id = :e").param("e", eventId)
                .query(Long.class).single()).isEqualTo(1);

        // A staff member decided in bookings directly: booking.review.rejected closes the item.
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.rejected",
                UUID.randomUUID(), reviewEvent(bookingId, org, "REJECTED", 2, "Arrived 40 minutes late")));
        assertThat(reviewItem(bookingId, "status")).isEqualTo("CLOSED");
        assertThat(reviewItem(bookingId, "decided_by")).isEqualTo("bookings");
        assertThat(outbox(bookingId.toString())).containsExactly("trustops.moderation.queued",
                "trustops.moderation.decided");
    }

    @Test
    void otherBookingEventsAndUnusableReviewsAreSkipped() {
        UUID bookingId = UUID.randomUUID();
        String org = orgId();
        // Not review events, or reviews already decided, are not queued.
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.confirmed", UUID.randomUUID(),
                "{\"bookingId\":\"" + bookingId + "\",\"orgId\":\"" + org + "\",\"status\":\"CONFIRMED\"}"));
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.replied",
                UUID.randomUUID(), reviewEvent(bookingId, org, "APPROVED", 5, "Great")));
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.submitted",
                UUID.randomUUID(), reviewEvent(bookingId, org, "APPROVED", 5, "Great")));
        // Malformed or invalid payloads are logged and skipped.
        reviews.onMessage(rec("at.bookings.events", "x", "booking.review.submitted", UUID.randomUUID(), "not json"));
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.submitted",
                UUID.randomUUID(), reviewEvent(bookingId, org, "PENDING", 9, null)));
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.submitted",
                UUID.randomUUID(), "{\"bookingId\":\"" + bookingId + "\",\"rating\":4,\"status\":\"PENDING\"}"));
        // Missing event-id.
        ConsumerRecord<String, String> noId = new ConsumerRecord<>("at.bookings.events", 0, 0L, bookingId.toString(),
                reviewEvent(bookingId, org, "PENDING", 4, null));
        noId.headers().add(EventHeaders.EVENT_TYPE, "booking.review.submitted".getBytes(StandardCharsets.UTF_8));
        reviews.onMessage(noId);

        assertThat(jdbc.sql("select count(*) from moderation_item where booking_id = :b").param("b", bookingId)
                .query(Long.class).single()).isZero();

        // A review without text is fine.
        reviews.onMessage(rec("at.bookings.events", bookingId.toString(), "booking.review.submitted",
                UUID.randomUUID(), reviewEvent(bookingId, org, "PENDING", 4, null)));
        assertThat(reviewItem(bookingId, "status")).isEqualTo("PENDING");
        assertThat(jdbc.sql("select review_text is null from moderation_item where booking_id = :b")
                .param("b", bookingId).query(Boolean.class).single()).isTrue();
    }

    private static ConsumerRecord<String, String> rec(String topic, String key, String type, UUID eventId,
                                                      String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>(topic, 0, 0L, key, json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.OCCURRED_AT, "2026-10-09T12:00:00Z".getBytes(StandardCharsets.UTF_8));
        return r;
    }

    @Test
    void productInReviewIsQueuedAndOtherStatusesAreNot() {
        UUID product = UUID.randomUUID();
        String org = orgId();
        products.onMessage(rec("at.product.events", product.toString(), "catalog.product.created", UUID.randomUUID(),
                """
                {"productId":"%s","atin":"AT00ZZ0001","ownerKind":"SELLER","ownerOrgId":"%s","status":"IN_REVIEW",
                 "title":"Herbal weight loss tea","productTypeCode":"tea","variants":[],"sellerHidden":false}
                """.formatted(product, org)));
        UUID waiting = UUID.randomUUID();
        products.onMessage(rec("at.product.events", waiting.toString(), "catalog.product.created", UUID.randomUUID(),
                "{\"productId\":\"" + waiting + "\",\"ownerOrgId\":\"" + org
                        + "\",\"status\":\"PENDING_CATEGORY_APPROVAL\",\"title\":\"Aso-ebi set\"}"));
        products.onMessage(rec("at.product.events", "x", "catalog.product.created", UUID.randomUUID(), "not json"));

        assertThat(jdbc.sql("select status from moderation_item where product_id = :p").param("p", product)
                .query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("select count(*) from moderation_item where product_id = :p").param("p", waiting)
                .query(Long.class).single()).isZero();

        products.onMessage(rec("at.product.events", product.toString(), "catalog.product.approved", UUID.randomUUID(),
                "{\"productId\":\"" + product + "\",\"ownerOrgId\":\"" + org + "\",\"status\":\"LIVE\"}"));
        assertThat(jdbc.sql("select status from moderation_item where product_id = :p").param("p", product)
                .query(String.class).single()).isEqualTo("CLOSED");

        // S307: a rejected listing edited back into review is queued again.
        products.onMessage(rec("at.product.events", product.toString(), "catalog.product.resubmitted",
                UUID.randomUUID(), "{\"productId\":\"" + product + "\",\"ownerOrgId\":\"" + org
                        + "\",\"status\":\"IN_REVIEW\",\"title\":\"Herbal tea (edited)\"}"));
        assertThat(jdbc.sql("select status from moderation_item where product_id = :p order by status")
                .param("p", product).query(String.class).list()).containsExactly("CLOSED", "PENDING");
    }

    @Test
    void flaggedLiveListingIsQueuedAsLiveListingAndTakedownInCatalogClosesIt() {
        UUID product = UUID.randomUUID();
        String org = orgId();
        UUID eventId = UUID.randomUUID();
        String flagged = """
                {"flagId":"%s","productId":"%s","atin":"AT00FL0001","ownerKind":"SELLER","ownerOrgId":"%s",
                 "status":"LIVE","title":"Kente stole handwoven","productTypeCode":"scarves","sellerHidden":false,
                 "source":"CUSTOMER_REPORT","reasonCode":"COUNTERFEIT","details":"Printed, not woven",
                 "reporterId":"cust-9","riskLevel":null,"occurredAt":"2026-10-09T12:00:00Z"}
                """.formatted(UUID.randomUUID(), product, org);
        products.onMessage(rec("at.product.events", product.toString(), "catalog.product.flagged", eventId, flagged));
        products.onMessage(rec("at.product.events", product.toString(), "catalog.product.flagged", eventId, flagged));
        assertThat(jdbc.sql("select item_type || '|' || status || '|' || report_source || '|' || report_reason || '|' "
                        + "|| report_count from moderation_item where product_id = :p").param("p", product)
                .query(String.class).list()).containsExactly("LIVE_LISTING|PENDING|CUSTOMER_REPORT|COUNTERFEIT|1");

        // An unknown reason code still queues (as OTHER); a flag without a source is skipped.
        UUID other = UUID.randomUUID();
        products.onMessage(rec("at.product.events", other.toString(), "catalog.product.flagged", UUID.randomUUID(),
                "{\"productId\":\"" + other + "\",\"ownerOrgId\":\"" + org + "\",\"status\":\"LIVE\","
                        + "\"title\":\"x\",\"source\":\"AUTOMATED\",\"reasonCode\":\"NEW_CODE\"}"));
        assertThat(jdbc.sql("select report_reason from moderation_item where product_id = :p").param("p", other)
                .query(String.class).single()).isEqualTo("OTHER");
        UUID noSource = UUID.randomUUID();
        products.onMessage(rec("at.product.events", noSource.toString(), "catalog.product.flagged", UUID.randomUUID(),
                "{\"productId\":\"" + noSource + "\",\"ownerOrgId\":\"" + org + "\",\"status\":\"LIVE\"}"));
        assertThat(jdbc.sql("select count(*) from moderation_item where product_id = :p").param("p", noSource)
                .query(Long.class).single()).isZero();

        products.onMessage(rec("at.product.events", product.toString(), "catalog.product.taken_down",
                UUID.randomUUID(), "{\"productId\":\"" + product + "\",\"ownerOrgId\":\"" + org
                        + "\",\"status\":\"TAKEN_DOWN\",\"reasonCode\":\"COUNTERFEIT\"}"));
        assertThat(jdbc.sql("select status from moderation_item where product_id = :p").param("p", product)
                .query(String.class).single()).isEqualTo("CLOSED");
    }

    @Test
    void auditListenerCopiesMcpInvocationsVerbatim() {
        UUID eventId = UUID.randomUUID();
        String json = "{\"eventId\":\"" + eventId + "\",\"tool\":\"issue_refund\",\"toolset\":\"staff\","
                + "\"subject\":\"staff-aisha\",\"clientId\":\"mcp-client\",\"orgId\":null,\"argumentsHash\":\"ab12\","
                + "\"outcome\":\"ok\",\"durationMs\":42,\"at\":\"2026-10-09T12:00:00Z\"}";
        audit.onMessage(rec("at.mcp-tool.events", "staff-aisha", "mcp.tool.invoked", eventId, json));
        audit.onMessage(rec("at.mcp-tool.events", "staff-aisha", "mcp.tool.invoked", eventId, json));

        assertThat(jdbc.sql("select payload from audit_entry where source_event_id = :id").param("id", eventId)
                .query(String.class).list()).containsExactly(json);
        assertThat(jdbc.sql("select actor_id || '|' || entity_type || '|' || entity_id || '|' || (occurred_at at time zone 'UTC')::text "
                        + "from audit_entry where source_event_id = :id").param("id", eventId).query(String.class)
                .single()).startsWith("staff-aisha|MCP_TOOL|issue_refund|2026-10-09 12:00:00");
    }

    @Test
    void orderEventsFeedTheShipmentProjection() {
        String org = orgId();
        UUID shipment = UUID.randomUUID();
        LocalDate promised = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        orders.onMessage(rec("at.order.events", "o-1", "orders.order.placed", UUID.randomUUID(), """
                {"orderId":"%s","orderNumber":"AT-2026-TEST01","customerId":"cust-1","currency":"USD",
                 "itemsMinor":1000,"shippingMinor":0,"taxMinor":0,"discountMinor":0,"totalMinor":1000,
                 "placedAt":"%s","shipments":[{"shipmentId":"%s","sellerOrgId":"%s",
                 "fulfilmentType":"SELLER_FULFILLED","deliveryMethod":"CARRIER","promisedBy":"%s",
                 "totalMinor":1000,"lines":[]}]}
                """.formatted(UUID.randomUUID(), Instant.now(), shipment, org, promised)));
        orders.onMessage(rec("at.order.events", "o-1", "orders.shipment.status_changed", UUID.randomUUID(), """
                {"orderId":"o-1","orderNumber":"AT-2026-TEST01","customerId":"cust-1","shipmentId":"%s",
                 "sellerOrgId":"%s","fulfilmentType":"SELLER_FULFILLED","deliveryMethod":"CARRIER",
                 "from":"in_transit","to":"delivered","actorType":"CARRIER","actorId":"ups","source":"carrier",
                 "occurredAt":"%s"}
                """.formatted(shipment, org, Instant.now())));

        assertThat(jdbc.sql("select last_status || '|' || (delivered_at is not null) || '|' || promised_by::text "
                        + "from seller_shipment where shipment_id = :s").param("s", shipment.toString())
                .query(String.class).single()).isEqualTo("delivered|true|" + promised);
    }

    @Test
    void sellerHandoverFromFulfilmentRecordsShipByLateness() {
        String org = orgId();
        UUID shipment = UUID.randomUUID();
        LocalDate shipBy = LocalDate.of(2026, 10, 6);
        orders.onMessage(rec("at.order.events", "o-2", "orders.order.placed", UUID.randomUUID(), """
                {"orderId":"o-2","orderNumber":"AT-2026-TEST02","customerId":"cust-1","currency":"USD",
                 "placedAt":"2026-10-05T15:00:00Z","shipments":[{"shipmentId":"%s","sellerOrgId":"%s",
                 "fulfilmentType":"SELLER_FULFILLED","deliveryMethod":"AT_DELIVERY","promisedBy":"2026-10-09",
                 "shipBy":"%s","lines":[]}]}
                """.formatted(shipment, org, shipBy)));
        String handedOver = """
                {"shipmentId":"%s","orderId":"o-2","orderNumber":"AT-2026-TEST02","sellerOrgId":"%s",
                 "fulfilmentType":"SELLER_FULFILLED","origin":"%s","routeId":"r-1","driverId":"d-1",
                 "shipBy":"%s","handedOverAt":"%s","late":true}
                """;
        // An FC loadout is not the seller's handover.
        fulfilment.onMessage(rec("at.fulfilment.events", shipment.toString(), "fulfilment.shipment.handed_over",
                UUID.randomUUID(), handedOver.formatted(shipment, org, "FC", shipBy, "2026-10-08T15:00:00Z")));
        assertThat(jdbc.sql("select handed_over_at is null from seller_shipment where shipment_id = :s")
                .param("s", shipment.toString()).query(Boolean.class).single()).isTrue();
        // Other fulfilment events are ignored.
        fulfilment.onMessage(rec("at.fulfilment.events", shipment.toString(), "fulfilment.task.created",
                UUID.randomUUID(), "{}"));

        UUID eventId = UUID.randomUUID();
        fulfilment.onMessage(rec("at.fulfilment.events", shipment.toString(), "fulfilment.shipment.handed_over",
                eventId, handedOver.formatted(shipment, org, "SELLER", shipBy, "2026-10-07T15:00:00Z")));
        // Redelivery is deduplicated; a later handover time never overwrites the first.
        fulfilment.onMessage(rec("at.fulfilment.events", shipment.toString(), "fulfilment.shipment.handed_over",
                eventId, handedOver.formatted(shipment, org, "SELLER", shipBy, "2026-10-09T15:00:00Z")));
        assertThat(jdbc.sql("select ship_by::text || '|' || extract(epoch from handed_over_at)::bigint || '|' || last_status "
                        + "from seller_shipment "
                        + "where shipment_id = :s").param("s", shipment.toString()).query(String.class).single())
                .isEqualTo(shipBy + "|" + Instant.parse("2026-10-07T15:00:00Z").getEpochSecond() + "|placed");
    }

    @Test
    void pickedUpStatusIsTheHandoverWhenFulfilmentDoesNotReportIt() {
        String org = orgId();
        UUID shipment = UUID.randomUUID();
        orders.onMessage(rec("at.order.events", "o-3", "orders.shipment.status_changed", UUID.randomUUID(), """
                {"orderId":"o-3","shipmentId":"%s","sellerOrgId":"%s","from":"ready","to":"picked_up",
                 "actorType":"CARRIER","occurredAt":"2026-10-07T18:30:00Z"}
                """.formatted(shipment, org)));
        assertThat(jdbc.sql("select extract(epoch from handed_over_at)::bigint || '|' || last_status from seller_shipment "
                        + "where shipment_id = :s")
                .param("s", shipment.toString()).query(String.class).single())
                .isEqualTo(Instant.parse("2026-10-07T18:30:00Z").getEpochSecond() + "|picked_up");
    }

    @Test
    void lolaHandoffOpensOneSupportCase() {
        UUID handoff = UUID.randomUUID();
        String json = """
                {"handoffId":"%s","sessionId":"lola-s-1","subject":"cust-kemi","reason":"payment-issue",
                 "summary":"Card charged twice for order AT-2026-X; email [EMAIL_1]","requestedAt":"%s"}
                """.formatted(handoff, Instant.now());
        handoffs.onMessage(rec("at.ai-handoff.events", handoff.toString(), "ai.handoff.requested",
                UUID.randomUUID(), json));
        handoffs.onMessage(rec("at.ai-handoff.events", handoff.toString(), "ai.handoff.requested",
                UUID.randomUUID(), json));

        assertThat(jdbc.sql("select reference || '|' || status || '|' || customer_id from support_case "
                        + "where handoff_id = :h").param("h", handoff).query(String.class).list())
                .singleElement().satisfies(r -> assertThat(r).matches("SC-\\d+\\|OPEN\\|cust-kemi"));

        assertThat(ok(send("GET", "/v1/support-cases", null)).path("total").asLong()).isPositive();
    }
}
