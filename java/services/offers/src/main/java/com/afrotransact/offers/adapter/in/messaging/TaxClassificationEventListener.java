package com.afrotransact.offers.adapter.in.messaging;

import com.afrotransact.offers.application.TaxHoldService;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code tax.classification.changed} from {@code at.taxreturns.events} (S384). For a variant's classification
 * (kind VARIANT_CLASSIFICATION, key = variant ATIN) it asks the tax release component to re-check every offer held on that
 * ATIN. The event is only the reason to look: the release asks tax-returns itself whether the seller has a RESOLVED
 * decision, so a stale or out-of-order event can never make an offer live. Re-checking is idempotent (an offer that is
 * already ACTIVE is left alone), so a redelivered event does no harm and no inbox is needed.
 */
@Component
class TaxClassificationEventListener {

    static final String TYPE = "tax.classification.changed";
    static final String VARIANT_KIND = "VARIANT_CLASSIFICATION";

    private static final Logger LOG = LoggerFactory.getLogger(TaxClassificationEventListener.class);

    private final TaxHoldService taxHold;
    private final JsonMapper json;

    TaxClassificationEventListener(TaxHoldService taxHold, JsonMapper json) {
        this.taxHold = taxHold;
        this.json = json;
    }

    /** The fields offers-service needs from the tax-returns payload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClassificationMessage(String kind, String atin, String status) {
    }

    @KafkaListener(id = "offers-tax-decisions", groupId = "${spring.kafka.consumer.group-id:offers-service}",
            topics = "${at.offers.consumers.tax-topic:at.taxreturns.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!TYPE.equals(type)) {
            return;
        }
        ClassificationMessage m;
        try {
            m = json.readValue(rec.value(), ClassificationMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || !VARIANT_KIND.equals(m.kind()) || m.atin() == null || m.atin().isBlank()) {
            return;
        }
        int released = taxHold.releaseFor(m.atin());
        if (released > 0) {
            LOG.info("Released {} offer(s) on {} after its tax classification became {}", released, m.atin(), m.status());
        }
    }
}
