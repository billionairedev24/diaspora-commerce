package com.afrotransact.payments.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.ProviderFeeNotice;
import com.afrotransact.payments.application.ProviderFeeService;
import com.afrotransact.payments.domain.ProviderFee;
import com.afrotransact.platform.events.consumer.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BookingsEventListenerTest {

    private final List<ProviderFeeNotice> notices = new ArrayList<>();
    private final BookingsEventListener listener = new BookingsEventListener(new ProviderFeeService() {
        @Override
        public boolean lateCancellation(ProviderFeeNotice notice) {
            notices.add(notice);
            return true;
        }

        @Override
        public int collectOutstanding(int maxFees) {
            return 0;
        }

        @Override
        public List<ProviderFee> ofOrg(String orgId) {
            return List.of();
        }
    }, JsonMapper.builder().build());

    private static ConsumerRecord<String, String> rec(String type, String eventId, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.bookings.events", 0, 0L, "b-1", json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.OCCURRED_AT, "2026-10-09T08:00:00Z".getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            r.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    private static String cancelled(String by, long fee) {
        return "{\"recipient\":{\"id\":\"cust-1\"},\"bookingId\":\"6f1c2f7e-1111-4c1a-9d1e-000000000001\","
                + "\"bookingReference\":\"BK-24018\",\"orgId\":\"org_p\",\"status\":\"CANCELLED_PROVIDER\","
                + "\"priceMinor\":10000,\"depositMinor\":2000,\"currency\":\"USD\",\"policy\":\"MODERATE\","
                + "\"refundedMinor\":2000,\"providerFeeMinor\":" + fee + ",\"reason\":\"Sick\",\"cancelledBy\":\""
                + by + "\"}";
    }

    @Test
    void lateProviderCancellationBecomesAFeeNotice() {
        UUID id = UUID.randomUUID();
        listener.onMessage(rec("booking.cancelled", id.toString(), cancelled("PROVIDER", 1500)));
        assertThat(notices).hasSize(1);
        ProviderFeeNotice n = notices.getFirst();
        assertThat(n.eventId()).isEqualTo(id);
        assertThat(n.bookingId()).isEqualTo("6f1c2f7e-1111-4c1a-9d1e-000000000001");
        assertThat(n.bookingReference()).isEqualTo("BK-24018");
        assertThat(n.orgId()).isEqualTo("org_p");
        assertThat(n.amountMinor()).isEqualTo(1500);
        assertThat(n.currency()).isEqualTo("USD");
        assertThat(n.occurredAt()).isEqualTo(Instant.parse("2026-10-09T08:00:00Z"));
    }

    @Test
    void ignoresOtherEventsCustomerCancellationsAndUnusableMessages() {
        listener.onMessage(rec("booking.confirmed", UUID.randomUUID().toString(), cancelled("PROVIDER", 1500)));
        listener.onMessage(rec("booking.cancelled", UUID.randomUUID().toString(), cancelled("CUSTOMER", 0)));
        listener.onMessage(rec("booking.cancelled", UUID.randomUUID().toString(), cancelled("PROVIDER", 0)));
        assertThatThrownBy(() -> listener.onMessage(rec("booking.cancelled", null, cancelled("PROVIDER", 1500))))
                .isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> listener.onMessage(rec("booking.cancelled", UUID.randomUUID().toString(), "{oops")))
                .isInstanceOf(NonRetryableEventException.class);
        assertThat(notices).isEmpty();
    }
}
