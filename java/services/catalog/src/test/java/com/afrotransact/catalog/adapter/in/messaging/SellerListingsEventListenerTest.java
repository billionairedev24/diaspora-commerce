package com.afrotransact.catalog.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.ListingEnforcement;
import com.afrotransact.catalog.application.ListingVisibilityService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SellerListingsEventListenerTest {

    private final List<String> calls = new ArrayList<>();
    private final List<ListingEnforcement> received = new ArrayList<>();
    private final SellerListingsEventListener listener = new SellerListingsEventListener(
            new ListingVisibilityService() {
                @Override
                public int hide(ListingEnforcement event) {
                    calls.add("hide");
                    received.add(event);
                    return 1;
                }

                @Override
                public int restore(ListingEnforcement event) {
                    calls.add("restore");
                    received.add(event);
                    return 1;
                }

                @Override
                public boolean hidden(String orgId) {
                    return false;
                }

                @Override
                public int resumeStalled() {
                    return 0;
                }
            }, JsonMapper.builder().build());

    private static ConsumerRecord<String, String> rec(String type, String eventId, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.seller-compliance.events", 0, 0L, "org_x", json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            r.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    @Test
    void routesHideAndRestore() {
        UUID id = UUID.randomUUID();
        listener.onMessage(rec("seller.listings.hidden", id.toString(), """
                {"orgId":"org_a","accountId":"acct_1","reason":"CHARGES_DISABLED","pastDue":[],
                 "occurredAt":"2026-10-01T10:00:00Z"}
                """));
        listener.onMessage(rec("seller.listings.restored", UUID.randomUUID().toString(),
                "{\"orgId\":\"org_a\",\"reason\":\"CLEARED\"}"));
        assertThat(calls).containsExactly("hide", "restore");
        assertThat(received.getFirst()).isEqualTo(new ListingEnforcement(id, "org_a", "CHARGES_DISABLED",
                Instant.parse("2026-10-01T10:00:00Z")));
    }

    @Test
    void ignoresOtherEventsAndUnusableMessages() {
        listener.onMessage(rec("seller.payouts.paused", UUID.randomUUID().toString(), "{\"orgId\":\"org_a\"}"));
        listener.onMessage(rec("seller.listings.hidden", null, "{\"orgId\":\"org_a\"}"));
        listener.onMessage(rec("seller.listings.hidden", UUID.randomUUID().toString(), "nope"));
        listener.onMessage(rec("seller.listings.hidden", UUID.randomUUID().toString(), "{}"));
        assertThat(calls).isEmpty();
    }
}
