package com.afrotransact.trustops.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.afrotransact.trustops.it.TrustOpsIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The Kafka listeners, called directly with records shaped like the producers' outbox output. */
class ListenersIT extends TrustOpsIntegrationTest {

    private final CatalogProductEventListener products;
    private final AuditEventListener audit;
    private final OrderEventListener orders;
    private final AiHandoffEventListener handoffs;

    @Autowired
    ListenersIT(CatalogProductEventListener products, AuditEventListener audit, OrderEventListener orders,
                AiHandoffEventListener handoffs) {
        this.products = products;
        this.audit = audit;
        this.orders = orders;
        this.handoffs = handoffs;
    }

    private static ConsumerRecord<String, String> rec(String topic, String key, String type, UUID eventId,
                                                      String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>(topic, 0, 0L, key, json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.OCCURRED_AT, "2026-10-09T12:00:00Z".getBytes(StandardCharsets.UTF_8));
        return r;
    }

    @Test
    void productInReviewIsQueuedAndOtherStatusesAreNot() {
        UUID product = UUID.randomUUID();
        String org = orgId();
        products.onMessage(rec("at.product.events", product.toString(), "catalog.product.created", UUID.randomUUID(),
                """
                {"productId":"%s","atin":"AT00ZZ0001","ownerKind":"SELLER","ownerOrgId":"%s","status":"IN_REVIEW",
                 "title":"Herbal weight loss tea","productTypeCode":"tea","variants":[],"sellerHidden":false}
                """.formatted(product, org)));
        UUID waiting = UUID.randomUUID();
        products.onMessage(rec("at.product.events", waiting.toString(), "catalog.product.created", UUID.randomUUID(),
                "{\"productId\":\"" + waiting + "\",\"ownerOrgId\":\"" + org
                        + "\",\"status\":\"PENDING_CATEGORY_APPROVAL\",\"title\":\"Aso-ebi set\"}"));
        products.onMessage(rec("at.product.events", "x", "catalog.product.created", UUID.randomUUID(), "not json"));

        assertThat(jdbc.sql("select status from moderation_item where product_id = :p").param("p", product)
                .query(String.class).single()).isEqualTo("PENDING");
        assertThat(jdbc.sql("select count(*) from moderation_item where product_id = :p").param("p", waiting)
                .query(Long.class).single()).isZero();

        products.onMessage(rec("at.product.events", product.toString(), "catalog.product.approved", UUID.randomUUID(),
                "{\"productId\":\"" + product + "\",\"ownerOrgId\":\"" + org + "\",\"status\":\"LIVE\"}"));
        assertThat(jdbc.sql("select status from moderation_item where product_id = :p").param("p", product)
                .query(String.class).single()).isEqualTo("CLOSED");
    }

    @Test
    void auditListenerCopiesMcpInvocationsVerbatim() {
        UUID eventId = UUID.randomUUID();
        String json = "{\"eventId\":\"" + eventId + "\",\"tool\":\"issue_refund\",\"toolset\":\"staff\","
                + "\"subject\":\"staff-aisha\",\"clientId\":\"mcp-client\",\"orgId\":null,\"argumentsHash\":\"ab12\","
                + "\"outcome\":\"ok\",\"durationMs\":42,\"at\":\"2026-10-09T12:00:00Z\"}";
        audit.onMessage(rec("at.mcp-tool.events", "staff-aisha", "mcp.tool.invoked", eventId, json));
        audit.onMessage(rec("at.mcp-tool.events", "staff-aisha", "mcp.tool.invoked", eventId, json));

        assertThat(jdbc.sql("select payload from audit_entry where source_event_id = :id").param("id", eventId)
                .query(String.class).list()).containsExactly(json);
        assertThat(jdbc.sql("select actor_id || '|' || entity_type || '|' || entity_id || '|' || occurred_at::text "
                        + "from audit_entry where source_event_id = :id").param("id", eventId).query(String.class)
                .single()).startsWith("staff-aisha|MCP_TOOL|issue_refund|2026-10-09 12:00:00");
    }

    @Test
    void orderEventsFeedTheShipmentProjection() {
        String org = orgId();
        UUID shipment = UUID.randomUUID();
        LocalDate promised = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        orders.onMessage(rec("at.order.events", "o-1", "orders.order.placed", UUID.randomUUID(), """
                {"orderId":"%s","orderNumber":"AT-2026-TEST01","customerId":"cust-1","currency":"USD",
                 "itemsMinor":1000,"shippingMinor":0,"taxMinor":0,"discountMinor":0,"totalMinor":1000,
                 "placedAt":"%s","shipments":[{"shipmentId":"%s","sellerOrgId":"%s",
                 "fulfilmentType":"SELLER_FULFILLED","deliveryMethod":"CARRIER","promisedBy":"%s",
                 "totalMinor":1000,"lines":[]}]}
                """.formatted(UUID.randomUUID(), Instant.now(), shipment, org, promised)));
        orders.onMessage(rec("at.order.events", "o-1", "orders.shipment.status_changed", UUID.randomUUID(), """
                {"orderId":"o-1","orderNumber":"AT-2026-TEST01","customerId":"cust-1","shipmentId":"%s",
                 "sellerOrgId":"%s","fulfilmentType":"SELLER_FULFILLED","deliveryMethod":"CARRIER",
                 "from":"in_transit","to":"delivered","actorType":"CARRIER","actorId":"ups","source":"carrier",
                 "occurredAt":"%s"}
                """.formatted(shipment, org, Instant.now())));

        assertThat(jdbc.sql("select last_status || '|' || (delivered_at is not null) || '|' || promised_by::text "
                        + "from seller_shipment where shipment_id = :s").param("s", shipment.toString())
                .query(String.class).single()).isEqualTo("delivered|true|" + promised);
    }

    @Test
    void lolaHandoffOpensOneSupportCase() {
        UUID handoff = UUID.randomUUID();
        String json = """
                {"handoffId":"%s","sessionId":"lola-s-1","subject":"cust-kemi","reason":"payment-issue",
                 "summary":"Card charged twice for order AT-2026-X; email [EMAIL_1]","requestedAt":"%s"}
                """.formatted(handoff, Instant.now());
        handoffs.onMessage(rec("at.ai-handoff.events", handoff.toString(), "ai.handoff.requested",
                UUID.randomUUID(), json));
        handoffs.onMessage(rec("at.ai-handoff.events", handoff.toString(), "ai.handoff.requested",
                UUID.randomUUID(), json));

        assertThat(jdbc.sql("select reference || '|' || status || '|' || customer_id from support_case "
                        + "where handoff_id = :h").param("h", handoff).query(String.class).list())
                .singleElement().satisfies(r -> assertThat(r).matches("SC-\\d+\\|OPEN\\|cust-kemi"));

        assertThat(ok(send("GET", "/v1/support-cases", null)).path("total").asLong()).isPositive();
    }
}
