package com.afrotransact.fulfilment.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.fulfilment.application.DriverAccessService;
import com.afrotransact.fulfilment.application.IdentityAccessChange;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes access changes from {@code at.identity.events}: the Driver role or {@code delivery:operate} granted or
 * revoked, accounts disabled or enabled. A revocation signs the driver app out at once (live stream + next sync).
 */
@Component
class IdentityEventListener {

    static final Set<String> TYPES = Set.of("identity.role.granted", "identity.role.revoked",
            "identity.permission.granted", "identity.permission.revoked", "identity.user.disabled",
            "identity.user.enabled");

    private static final Logger LOG = LoggerFactory.getLogger(IdentityEventListener.class);

    private final DriverAccessService drivers;
    private final JsonMapper json;

    IdentityEventListener(DriverAccessService drivers, JsonMapper json) {
        this.drivers = drivers;
        this.json = json;
    }

    /** identity.yaml IdentityRoleChanged / IdentityPermissionChanged / IdentityUserStatusChanged. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccessMessage(String userId, String roleKey, String permissionKey, String reason, Instant at) {
    }

    @KafkaListener(id = "fulfilment-identity", groupId = "${spring.kafka.consumer.group-id:fulfilment-service}",
            topics = "${at.fulfilment.consumers.identity-topic:at.identity.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!TYPES.contains(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        AccessMessage m;
        try {
            m = json.readValue(rec.value(), AccessMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.userId() == null) {
            return;
        }
        drivers.onIdentityChange(eventId.get(), new IdentityAccessChange(type, m.userId(), m.roleKey(),
                m.permissionKey(), m.reason(), m.at()));
    }
}
