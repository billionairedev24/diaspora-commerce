package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.bookings.adapter.in.kafka.SellerComplianceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** seller.listings.hidden / restored (at.seller-compliance.events) hide and restore a provider's public services. */
class SellerComplianceIT extends BookingsIntegrationTest {

    private final SellerComplianceListener listener;

    @Autowired
    SellerComplianceIT(SellerComplianceListener listener) {
        this.listener = listener;
    }

    @Test
    void hiddenProvidersDisappearFromPublicReadsUntilRestored() {
        Setup s = provider(false);
        String path = "/v1/public/services/" + s.serviceId();
        ok(send("GET", path, null, null));
        Instant t0 = Instant.parse("2026-10-10T10:00:00Z");

        UUID hiddenEvent = UUID.randomUUID();
        listener.onMessage(record(hiddenEvent, "seller.listings.hidden", s.org(), "CHARGES_DISABLED", t0));
        assertThat(inbox(hiddenEvent)).isEqualTo(1);
        problem(send("GET", path, null, null), 404, "bookings.service-not-found");
        problem(send("GET", path + "/slots?date=" + day(), null, null), 404, "bookings.service-not-found");
        problem(send("GET", "/v1/public/providers/" + s.org(), null, null), 404, "bookings.provider-not-found");
        assertThat(ok(send("GET", "/v1/public/services/search?q=" + tag(s.org()), null, null)).path("services").size())
                .isZero();
        TestSupport.as(TestSupport.customer("cust-hidden"));
        problem(send("POST", "/v1/bookings", bookingBody(s.serviceId(), day() + "T15:00:00Z"), id("key")), 404,
                "bookings.service-not-found");
        TestSupport.reset();

        // A stale (older) restore delivered late doesn't un-hide; the real restore does; redelivery is a no-op.
        listener.onMessage(record(UUID.randomUUID(), "seller.listings.restored", s.org(), "CLEARED",
                t0.minusSeconds(60)));
        problem(send("GET", path, null, null), 404, "bookings.service-not-found");
        UUID restored = UUID.randomUUID();
        listener.onMessage(record(restored, "seller.listings.restored", s.org(), "CLEARED", t0.plusSeconds(60)));
        listener.onMessage(record(restored, "seller.listings.restored", s.org(), "CLEARED", t0.plusSeconds(60)));
        assertThat(inbox(restored)).isEqualTo(1);
        ok(send("GET", path, null, null));
        assertThat(ok(send("GET", path + "/slots?date=" + day(), null, null)).size()).isGreaterThan(0);
        assertThat(ok(send("GET", "/v1/public/services/search?q=" + tag(s.org()), null, null)).path("services").size())
                .isEqualTo(1);

        // A redelivered hide (same event id) does not hide again.
        listener.onMessage(record(hiddenEvent, "seller.listings.hidden", s.org(), "CHARGES_DISABLED", t0));
        ok(send("GET", path, null, null));
    }

    private long inbox(UUID eventId) {
        return jdbc.sql("select count(*) from platform_inbox where consumer = 'bookings.seller-compliance' "
                + "and event_id = :id").param("id", eventId).query(Long.class).single();
    }

    private static ConsumerRecord<String, String> record(UUID eventId, String type, String org, String reason,
                                                         Instant at) {
        String payload = "{\"orgId\":\"" + org + "\",\"accountId\":\"acct_1\",\"reason\":\"" + reason
                + "\",\"occurredAt\":\"" + at + "\"}";
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.seller-compliance.events", 0, 0L, org, payload);
        r.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        r.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        return r;
    }
}
