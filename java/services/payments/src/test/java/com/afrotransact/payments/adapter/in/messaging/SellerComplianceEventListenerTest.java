package com.afrotransact.payments.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.EnforcementNotice;
import com.afrotransact.payments.application.SellerEnforcementService;
import com.afrotransact.platform.events.consumer.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SellerComplianceEventListenerTest {

    private final List<String> calls = new ArrayList<>();
    private final List<EnforcementNotice> notices = new ArrayList<>();
    private final SellerComplianceEventListener listener = new SellerComplianceEventListener(
            new SellerEnforcementService() {
                @Override
                public boolean payoutsPaused(EnforcementNotice notice) {
                    calls.add("paused");
                    notices.add(notice);
                    return true;
                }

                @Override
                public boolean payoutsResumed(EnforcementNotice notice) {
                    calls.add("resumed");
                    notices.add(notice);
                    return true;
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
    void routesPauseAndResume() {
        UUID id = UUID.randomUUID();
        listener.onMessage(rec("seller.payouts.paused", id.toString(), """
                {"orgId":"org_a","accountId":"acct_1","reason":"AUTO_PAST_DUE","actorId":null,"note":null,
                 "pastDue":["individual.verification.document"],"occurredAt":"2026-10-01T10:00:00Z"}
                """));
        listener.onMessage(rec("seller.payouts.resumed", UUID.randomUUID().toString(),
                "{\"orgId\":\"org_a\",\"accountId\":\"acct_1\",\"reason\":\"CLEARED\",\"pastDue\":[]}"));
        assertThat(calls).containsExactly("paused", "resumed");
        EnforcementNotice n = notices.getFirst();
        assertThat(n.eventId()).isEqualTo(id);
        assertThat(n.orgId()).isEqualTo("org_a");
        assertThat(n.accountId()).isEqualTo("acct_1");
        assertThat(n.reason()).isEqualTo("AUTO_PAST_DUE");
        assertThat(n.occurredAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    void ignoresOtherEventsAndUnusableMessages() {
        listener.onMessage(rec("seller.listings.hidden", UUID.randomUUID().toString(), "{\"orgId\":\"org_a\"}"));
        listener.onMessage(rec("seller.compliance.changed", UUID.randomUUID().toString(), "{\"orgId\":\"org_a\"}"));
        assertThatThrownBy(() -> listener.onMessage(rec("seller.payouts.paused", null, "{\"orgId\":\"org_a\"}")))
                .isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> listener.onMessage(rec("seller.payouts.paused", UUID.randomUUID().toString(), "[1,2")))
                .isInstanceOf(NonRetryableEventException.class);
        listener.onMessage(rec("seller.payouts.paused", UUID.randomUUID().toString(), "{\"reason\":\"MANUAL\"}"));
        assertThat(calls).isEmpty();
    }
}
