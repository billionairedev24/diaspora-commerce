package com.afrotransact.content.adapter.in.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.application.AdDeliveryService;
import com.afrotransact.content.domain.ads.AdClick;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes search-service's {@code ads.impression.recorded} and {@code ads.click.recorded} from {@code at.ads.events}
 * (key = campaign id, so one campaign's clicks are charged in order). Malformed messages are logged and skipped;
 * use-case failures propagate so the container retries the record. Duplicates are dropped by the inbox (event-id) and
 * by the click id.
 */
@Component
class AdEventsListener {

    static final String IMPRESSION = "ads.impression.recorded";
    static final String CLICK = "ads.click.recorded";

    private static final Logger LOG = LoggerFactory.getLogger(AdEventsListener.class);

    private final AdDeliveryService delivery;
    private final JsonMapper json;

    AdEventsListener(AdDeliveryService delivery, JsonMapper json) {
        this.delivery = delivery;
        this.json = json;
    }

    /** search.yaml AdImpression (fields content needs). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ImpressionMessage(String campaignId, Instant occurredAt) {
    }

    /** search.yaml AdClick. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClickMessage(String clickId, String campaignId, String atin, List<String> variantAtins, String customerId,
                        Long priceMinor, String market, String query, Instant occurredAt) {
    }

    @KafkaListener(id = "content-ads-events", groupId = "${spring.kafka.consumer.group-id:content-service}",
            topics = "${at.content.consumers.ads-topic:at.ads.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!IMPRESSION.equals(type) && !CLICK.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(), rec.partition(),
                    rec.offset());
            return;
        }
        try {
            if (IMPRESSION.equals(type)) {
                ImpressionMessage m = json.readValue(rec.value(), ImpressionMessage.class);
                if (m == null || m.campaignId() == null || m.occurredAt() == null) {
                    LOG.warn("Skipping impression {} without campaignId/occurredAt", eventId.get());
                    return;
                }
                delivery.impression(eventId.get(), m.campaignId(), m.occurredAt());
                return;
            }
            ClickMessage m = json.readValue(rec.value(), ClickMessage.class);
            if (m == null || m.clickId() == null || m.campaignId() == null || m.priceMinor() == null
                    || m.occurredAt() == null || m.atin() == null) {
                LOG.warn("Skipping click {} with missing fields", eventId.get());
                return;
            }
            delivery.click(eventId.get(), new AdClick(m.clickId(), m.campaignId(), m.atin(), m.variantAtins(),
                    blankToNull(m.customerId()), m.priceMinor(), m.market() == null ? "US" : m.market().toUpperCase(Locale.ROOT),
                    m.query(), m.occurredAt()));
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
