package com.afrotransact.identity.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.application.SellerOrgChange;
import com.afrotransact.identity.application.SellerOrgSyncService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SellerOrgEventListenerTest {

    private final List<String> calls = new ArrayList<>();
    private final List<SellerOrgChange> changes = new ArrayList<>();
    private final SellerOrgEventListener listener = new SellerOrgEventListener(new SellerOrgSyncService() {
        @Override
        public boolean approved(SellerOrgChange change) {
            calls.add("approved");
            changes.add(change);
            return true;
        }

        @Override
        public boolean updated(SellerOrgChange change) {
            calls.add("updated");
            changes.add(change);
            return true;
        }
    }, JsonMapper.builder().build());

    private static ConsumerRecord<String, String> rec(String type, String eventId, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.seller-org.events", 0, 0L, "org_x", json);
        if (type != null) {
            r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        if (eventId != null) {
            r.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    @Test
    void mapsApprovedPayloadIncludingApplicantAndUnknownFields() {
        UUID id = UUID.randomUUID();
        listener.onMessage(rec("seller.org.approved", id.toString(), """
                {"orgId":"org_3k2j9x8q7w6e5r4t3y2u","kind":"SELLER","legalName":"Kemi Foods LLC",
                 "displayName":"Kemi's Pantry","country":"US","status":"APPROVED",
                 "ownerUserIds":["a5f1d2a8-3c39-4b47-9a0e-1d1f3c3e2b10"],
                 "applicantUserId":"a5f1d2a8-3c39-4b47-9a0e-1d1f3c3e2b10",
                 "occurredAt":"2026-10-01T10:00:00Z","somethingNew":42}
                """));
        assertThat(calls).containsExactly("approved");
        SellerOrgChange c = changes.getFirst();
        assertThat(c.eventId()).isEqualTo(id);
        assertThat(c.orgId()).isEqualTo("org_3k2j9x8q7w6e5r4t3y2u");
        assertThat(c.displayName()).isEqualTo("Kemi's Pantry");
        assertThat(c.country()).isEqualTo("US");
        assertThat(c.applicantUserId()).isEqualTo("a5f1d2a8-3c39-4b47-9a0e-1d1f3c3e2b10");
        assertThat(c.ownerUserIds()).hasSize(1);
        assertThat(c.occurredAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    void carriesKindBothAndThePrimaryKind() {
        listener.onMessage(rec("seller.org.updated", UUID.randomUUID().toString(), """
                {"orgId":"org_both","kind":"BOTH","primaryKind":"PROVIDER","offering":"BOTH","status":"APPROVED"}
                """));
        assertThat(changes.getFirst().kind()).isEqualTo("BOTH");
        assertThat(changes.getFirst().primaryKind()).isEqualTo("PROVIDER");
    }

    @Test
    void routesUpdatesAndIgnoresOtherTypesMissingIdsAndGarbage() {
        listener.onMessage(rec("seller.org.updated", UUID.randomUUID().toString(),
                "{\"orgId\":\"org_a\",\"kind\":\"PROVIDER\",\"status\":\"SUSPENDED\"}"));
        listener.onMessage(rec("seller.org.created", UUID.randomUUID().toString(), "{\"orgId\":\"org_a\"}"));
        listener.onMessage(rec("seller.org.updated", null, "{\"orgId\":\"org_a\"}"));
        listener.onMessage(rec("seller.org.updated", "not-a-uuid", "{\"orgId\":\"org_a\"}"));
        listener.onMessage(rec("seller.org.approved", UUID.randomUUID().toString(), "{not json"));
        listener.onMessage(rec("seller.org.approved", UUID.randomUUID().toString(), "{\"kind\":\"SELLER\"}"));
        assertThat(calls).containsExactly("updated");
        assertThat(changes.getFirst().status()).isEqualTo("SUSPENDED");
    }
}
