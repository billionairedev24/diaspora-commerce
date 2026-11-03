package com.afrotransact.seller.adapter.in.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.afrotransact.seller.application.SellerMetricsService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds the reward rules' metrics (S296) from other services' events, inbox-deduplicated on the event-id header:
 * <ul>
 *   <li>{@code orders.shipment.delivered} (at.order.events): gross sales and completed orders of {@code sellerOrgId};</li>
 *   <li>{@code booking.completed} (at.bookings.events): completed bookings of {@code orgId};</li>
 *   <li>{@code offers.offer.created / updated / withdrawn} (at.offers.events): whether the offer is a live listing
 *       ({@code status ACTIVE} and {@code productLive}).</li>
 * </ul>
 * Other events and malformed messages are skipped; failures of the use case propagate so the container retries.
 */
@Component
class RewardMetricsEventListener {

    static final String SHIPMENT_DELIVERED = "orders.shipment.delivered";
    static final String BOOKING_COMPLETED = "booking.completed";
    static final String OFFER_PREFIX = "offers.offer.";

    private final SellerMetricsService metrics;
    private final JsonMapper json;

    RewardMetricsEventListener(SellerMetricsService metrics, JsonMapper json) {
        this.metrics = metrics;
        this.json = json;
    }

    @KafkaListener(id = "seller-reward-metrics", groupId = "${spring.kafka.consumer.group-id:seller-service}",
            topics = {"${at.seller.consumers.orders-topic:at.order.events}",
                "${at.seller.consumers.bookings-topic:at.bookings.events}",
                "${at.seller.consumers.offers-topic:at.offers.events}"},
            autoStartup = "${at.seller.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        handle(rec);
    }

    /** Parses and records one event; returns whether a metric changed (visible for tests). */
    boolean handle(ConsumerRecord<String, String> rec) {
        String type = header(rec, "event-type").orElse("");
        if (!SHIPMENT_DELIVERED.equals(type) && !BOOKING_COMPLETED.equals(type) && !type.startsWith(OFFER_PREFIX)) {
            return false;
        }
        Optional<UUID> eventId = header(rec, "event-id").flatMap(RewardMetricsEventListener::uuid);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        JsonNode p;
        try {
            p = json.readTree(rec.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        Instant at = header(rec, "occurred-at").flatMap(RewardMetricsEventListener::instant).orElse(null);
        if (SHIPMENT_DELIVERED.equals(type)) {
            Instant delivered = instant(p.path("deliveredAt").asString("")).orElse(at);
            return metrics.shipmentDelivered(eventId.get(), text(p, "sellerOrgId"), p.path("totalMinor").asLong(0),
                    text(p, "currency"), delivered);
        }
        if (BOOKING_COMPLETED.equals(type)) {
            return metrics.bookingCompleted(eventId.get(), text(p, "orgId"), at);
        }
        if (!"offers.offer.created".equals(type) && !"offers.offer.updated".equals(type)
                && !"offers.offer.withdrawn".equals(type)) {
            return false;
        }
        boolean live = !"offers.offer.withdrawn".equals(type) && "ACTIVE".equals(text(p, "status"))
                && p.path("productLive").asBoolean(false);
        return metrics.offerChanged(eventId.get(), text(p, "offerId"), text(p, "sellerOrgId"), live, at);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static Optional<String> header(ConsumerRecord<?, ?> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null || h.value() == null ? Optional.empty()
                : Optional.of(new String(h.value(), StandardCharsets.UTF_8));
    }

    private static Optional<UUID> uuid(String s) {
        try {
            return Optional.of(UUID.fromString(s.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> instant(String s) {
        try {
            return s == null || s.isBlank() ? Optional.empty() : Optional.of(Instant.parse(s.trim()));
        } catch (DateTimeParseException ex) {
            return Optional.empty();
        }
    }
}
