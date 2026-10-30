package com.afrotransact.seller.adapter.in.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.afrotransact.seller.application.OrgMemberSyncService;
import com.afrotransact.seller.application.OrgMemberSyncService.RoleChange;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code identity.role.granted} / {@code identity.role.revoked} from {@code at.identity.events} (key = user
 * id, so one member's changes arrive in order) and mirrors organisation roles into seller's member list (S236).
 * Other identity events, malformed messages and events without an event-id are skipped; failures of the use case
 * propagate so the container retries the record.
 */
@Component
class IdentityRoleEventListener {

    static final String ROLE_GRANTED = "identity.role.granted";
    static final String ROLE_REVOKED = "identity.role.revoked";

    private final OrgMemberSyncService sync;
    private final JsonMapper json;

    IdentityRoleEventListener(OrgMemberSyncService sync, JsonMapper json) {
        this.sync = sync;
        this.json = json;
    }

    /** Wire format of identity's RoleChanged payload (contracts/asyncapi/fragments/identity.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoleChangedMessage(String userId, String roleKey, String orgId, String actorId, String reason, Instant at) {
    }

    @KafkaListener(id = "seller-identity-roles", groupId = "${spring.kafka.consumer.group-id:seller-service}",
            topics = "${at.seller.consumers.identity-topic:at.identity.events}",
            autoStartup = "${at.seller.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        handle(rec);
    }

    /** Parses and applies one record; returns whether the member list changed (visible for tests). */
    boolean handle(ConsumerRecord<String, String> rec) {
        String type = header(rec, "event-type").orElse("");
        if (!ROLE_GRANTED.equals(type) && !ROLE_REVOKED.equals(type)) {
            return false;
        }
        Optional<UUID> eventId = header(rec, "event-id").flatMap(IdentityRoleEventListener::uuid);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        RoleChangedMessage m;
        try {
            m = json.readValue(rec.value(), RoleChangedMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.orgId() == null) {
            return false;
        }
        RoleChange change = new RoleChange(eventId.get(), m.userId(), m.roleKey(), m.orgId(), m.actorId(), m.at());
        return ROLE_GRANTED.equals(type) ? sync.granted(change) : sync.revoked(change);
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
}
