package com.afrotransact.identity.adapter.in.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.application.SellerOrgChange;
import com.afrotransact.identity.application.SellerOrgSyncService;
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
 * Consumes {@code seller.org.approved} and {@code seller.org.updated} from {@code at.seller-org.events} (key = org id,
 * so events of one organisation arrive in order). Malformed messages are logged and skipped; failures of the use case
 * propagate so the container retries the record.
 */
@Component
class SellerOrgEventListener {

    static final String ORG_APPROVED = "seller.org.approved";
    static final String ORG_UPDATED = "seller.org.updated";

    private static final Logger LOG = LoggerFactory.getLogger(SellerOrgEventListener.class);

    private final SellerOrgSyncService sync;
    private final JsonMapper json;

    SellerOrgEventListener(SellerOrgSyncService sync, JsonMapper json) {
        this.sync = sync;
        this.json = json;
    }

    /** Wire format of seller-service's OrgEventPayload (contracts/asyncapi/fragments/seller.yaml SellerOrgEvent). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrgEventMessage(String orgId, String kind, String primaryKind, String legalName, String displayName,
                           String country, String status, List<String> ownerUserIds, String applicantUserId,
                           Instant occurredAt) {
    }

    @KafkaListener(id = "identity-seller-org", groupId = "${spring.kafka.consumer.group-id:identity-service}",
            topics = "${at.identity.consumers.seller-org-topic:at.seller-org.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!ORG_APPROVED.equals(type) && !ORG_UPDATED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        OrgEventMessage m;
        try {
            m = json.readValue(rec.value(), OrgEventMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.orgId() == null) {
            LOG.warn("Skipping {} event {} without orgId", type, eventId.get());
            return;
        }
        SellerOrgChange change = new SellerOrgChange(eventId.get(), m.orgId(), m.kind(), m.primaryKind(),
                m.legalName(), m.displayName(), m.country(), m.status(), m.ownerUserIds(), m.applicantUserId(),
                m.occurredAt());
        if (ORG_APPROVED.equals(type)) {
            sync.approved(change);
        } else {
            sync.updated(change);
        }
    }
}
