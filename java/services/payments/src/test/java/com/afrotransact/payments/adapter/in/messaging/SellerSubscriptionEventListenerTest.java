package com.afrotransact.payments.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.OrgPlanService;
import com.afrotransact.payments.application.SubscriptionNotice;
import com.afrotransact.platform.events.consumer.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SellerSubscriptionEventListenerTest {

    private final List<SubscriptionNotice> notices = new ArrayList<>();
    private final SellerSubscriptionEventListener listener = new SellerSubscriptionEventListener(
            new OrgPlanService() {
                @Override
                public boolean subscriptionChanged(SubscriptionNotice notice) {
                    notices.add(notice);
                    return true;
                }

                @Override
                public Optional<String> planOf(String orgId) {
                    return Optional.empty();
                }

                @Override
                public Map<String, String> plansOf(Collection<String> orgIds) {
                    return Map.of();
                }
            }, JsonMapper.builder().build());

    private static ConsumerRecord<String, String> rec(String type, String eventId, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.seller-subscription.events", 0, 0L, "org_x",
                json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            r.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    @Test
    void subscriptionChangesUpdateThePlan() {
        UUID id = UUID.randomUUID();
        listener.onMessage(rec("seller.subscription.changed", id.toString(), """
                {"orgId":"org_a","change":"UPGRADED","planCode":"seller_growth","cadence":"MONTHLY",
                 "status":"ACTIVE","cancelAtPeriodEnd":false,"dunning":"NONE","occurredAt":"2026-10-01T10:00:00Z"}
                """));
        assertThat(notices).hasSize(1);
        SubscriptionNotice n = notices.getFirst();
        assertThat(n.eventId()).isEqualTo(id);
        assertThat(n.orgId()).isEqualTo("org_a");
        assertThat(n.planCode()).isEqualTo("seller_growth");
        assertThat(n.status()).isEqualTo("ACTIVE");
        assertThat(n.occurredAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    void ignoresOtherEventsAndUnusableMessages() {
        listener.onMessage(rec("seller.billing.grant_applied", UUID.randomUUID().toString(), "{\"orgId\":\"org_a\"}"));
        assertThatThrownBy(() -> listener.onMessage(rec("seller.subscription.changed", null, "{\"orgId\":\"org_a\"}")))
                .isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> listener.onMessage(rec("seller.subscription.changed", UUID.randomUUID().toString(), "[1,2")))
                .isInstanceOf(NonRetryableEventException.class);
        listener.onMessage(rec("seller.subscription.changed", UUID.randomUUID().toString(), "{\"planCode\":\"x\"}"));
        assertThat(notices).isEmpty();
    }
}
