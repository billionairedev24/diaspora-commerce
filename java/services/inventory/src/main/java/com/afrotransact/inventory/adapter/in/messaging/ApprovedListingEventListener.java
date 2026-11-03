package com.afrotransact.inventory.adapter.in.messaging;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.application.InboundCommands;
import com.afrotransact.inventory.application.InboundShipmentService;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code catalog.product.approved} from {@code at.product.events}: a seller's Fulfilled by AfroTransact
 * listing gets its inbound plan opened automatically, with the units they listed under the offer's inventory SKU
 * (their seller SKU). AfroTransact Retail products and seller-fulfilled listings are ignored.
 */
@Component
class ApprovedListingEventListener {

    static final String APPROVED = "catalog.product.approved";


    private final InboundShipmentService inbound;
    private final JsonMapper json;

    ApprovedListingEventListener(InboundShipmentService inbound, JsonMapper json) {
        this.inbound = inbound;
        this.json = json;
    }

    /** Wire format of catalog's ProductEventPayload (the fields inventory needs). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductMessage(String atin, String ownerKind, String ownerOrgId, String fulfilment,
                          List<VariantMessage> variants) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VariantMessage(String atin, String sellerSku, int quantity) {
    }

    @KafkaListener(id = "inventory-approved-listings", groupId = "${spring.kafka.consumer.group-id:inventory-service}",
            topics = "${at.inventory.consumers.product-topic:at.product.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        if (!APPROVED.equals(EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse(""))) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(APPROVED, rec);
        }
        ProductMessage m;
        try {
            m = json.readValue(rec.value(), ProductMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(APPROVED, ex);
        }
        if (m == null || m.ownerOrgId() == null || m.variants() == null || !"AFROTRANSACT".equals(m.fulfilment())
                || "PLATFORM".equals(m.ownerKind())) {
            return;
        }
        List<InboundCommands.PlanLine> lines = m.variants().stream()
                .filter(v -> v.atin() != null && v.sellerSku() != null && v.quantity() > 0)
                .map(v -> new InboundCommands.PlanLine(v.atin(), v.sellerSku().trim().toUpperCase(Locale.ROOT),
                        v.quantity())).toList();
        inbound.listingApproved(eventId.get(), new InboundCommands.ApprovedListing(m.ownerOrgId(), m.atin(), lines));
    }
}
