package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.SettlementService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AcknowledgingConsumerAwareMessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.JsonNode;

/**
 * S256: labels bought through carrier-gateway are charged to the seller. Events are handed to the registered Kafka
 * listener ({@code payments-carrier-shipment-events}) directly, as the container would, without a broker.
 */
class LabelChargesIT extends PaymentsIntegrationTest {

    private static final String PURCHASED = "carrier.label.purchased";
    private static final String VOIDED = "carrier.label.voided";

    private AcknowledgingConsumerAwareMessageListener<String, String> listener;
    private SettlementService settlements;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire(@Autowired KafkaListenerEndpointRegistry registry, @Autowired SettlementService settlementService) {
        MessageListenerContainer container = registry.getListenerContainer("payments-carrier-shipment-events");
        assertThat(container).as("listener registered").isNotNull();
        this.listener = (AcknowledgingConsumerAwareMessageListener<String, String>) container.getContainerProperties()
                .getMessageListener();
        this.settlements = settlementService;
    }

    private void deliver(String type, UUID eventId, String payload) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("at.carrier-shipment.events", 0, 0L, "ups:1Z",
                payload);
        rec.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        rec.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        rec.headers().add("occurred-at", "2026-10-10T12:00:00Z".getBytes(StandardCharsets.UTF_8));
        listener.onMessage(rec, null, null);
    }

    private static String label(String labelId, String org, String tracking, String fulfilment, String billTo,
                                long cost, long handling) {
        return "{\"labelId\":\"" + labelId + "\",\"orgId\":\"" + org + "\",\"shipmentId\":\"shp-" + tracking
                + "\",\"orderId\":\"ORD-" + tracking + "\",\"reference\":\"shp-" + tracking + "\",\"originNode\":\""
                + org + "\",\"carrier\":\"ups\",\"service\":\"03\",\"trackingNumber\":\"" + tracking + "\","
                + "\"kind\":\"outbound\",\"fulfilment\":\"" + fulfilment + "\",\"billTo\":\"" + billTo + "\","
                + "\"billingReason\":\"SELLER_SHIPPED\",\"costMinor\":" + cost + ",\"handlingMinor\":" + handling
                + ",\"amountMinor\":" + (cost + handling) + ",\"currency\":\"USD\","
                + "\"purchasedAt\":\"2026-10-10T12:00:00Z\"}";
    }

    private static String org(String prefix) {
        return id(prefix).toLowerCase(Locale.ROOT);
    }

    private long journals(String key) {
        return jdbc.sql("select count(*) from journal_entry where journal_key = :k").param("k", key)
                .query(Long.class).single();
    }

    private long labelRows(String labelId) {
        return jdbc.sql("select count(*) from label_charge where label_id = :l").param("l", labelId)
                .query(Long.class).single();
    }

    /** Credit-normal balance of an account's lines attributed to {@code org} (all orders). */
    private long orgBalance(String code, String key, String org) {
        return jdbc.sql("""
                select coalesce(sum(case side when 'C' then amount_minor else -amount_minor end), 0)
                from journal_line where account_code = :c and account_key = :k and org_id = :o
                """).param("c", code).param("k", key).param("o", org).query(Long.class).single();
    }

    private JsonNode balanceOf(String org) {
        TestSupport.as(TestSupport.seller(org, "seller.owner", "payouts:request"));
        return ok(send("GET", "/v1/payouts/balance", null, null));
    }

    @Test
    void purchaseChargesTheSellerAndAVoidCreditsItBackOnce() {
        String org = org("org_l");
        String labelId = UUID.randomUUID().toString();
        String tracking = id("1Z");
        String payload = label(labelId, org, tracking, "SELLER_SHIPPED", "SELLER", 999, 125);

        UUID purchase = UUID.randomUUID();
        deliver(PURCHASED, purchase, payload);
        deliver(PURCHASED, purchase, payload);
        deliver(PURCHASED, UUID.randomUUID(), payload);
        assertThat(journals("label-charge:" + labelId)).as("one charge per label, redeliveries ignored").isEqualTo(1);
        assertThat(orgBalance("2000", org, org)).isEqualTo(-1124);
        assertThat(orgBalance("2400", "", org)).isEqualTo(999);
        assertThat(orgBalance("4500", "", org)).isEqualTo(125);
        assertEveryJournalBalances();

        // Nothing earned yet: the charge still posts and the balance goes negative; no payout is possible.
        JsonNode balance = balanceOf(org);
        assertThat(balance.path("labelChargesMinor").asLong()).isEqualTo(1124);
        assertThat(balance.path("availableMinor").asLong()).isEqualTo(-1124);
        assertThat(balance.path("onHold").size()).isZero();
        problem(send("POST", "/v1/payouts/requests", Map.of("amountMinor", 2500), null), 422,
                "payments.payout-exceeds-available");

        JsonNode charges = ok(send("GET", "/v1/payouts/label-charges", null, null));
        assertThat(charges.size()).isEqualTo(1);
        JsonNode c = charges.get(0);
        assertThat(c.path("labelId").asString()).isEqualTo(labelId);
        assertThat(c.path("trackingNumber").asString()).isEqualTo(tracking);
        assertThat(c.path("shipmentId").asString()).isEqualTo("shp-" + tracking);
        assertThat(c.path("orderId").asString()).isEqualTo("ORD-" + tracking);
        assertThat(c.path("costMinor").asLong()).isEqualTo(999);
        assertThat(c.path("handlingMinor").asLong()).isEqualTo(125);
        assertThat(c.path("amountMinor").asLong()).isEqualTo(1124);
        assertThat(c.path("status").asString()).isEqualTo("CHARGED");

        JsonNode statement = ok(send("GET", "/v1/payouts/statement", null, null));
        assertThat(statement.size()).isEqualTo(1);
        assertThat(statement.get(0).path("kind").asString()).isEqualTo("LABEL_CHARGE");
        assertThat(statement.get(0).path("amountMinor").asLong()).isEqualTo(-1124);
        assertThat(statement.get(0).path("trackingNumber").asString()).isEqualTo(tracking);
        assertThat(statement.get(0).path("shipmentId").asString()).isEqualTo("shp-" + tracking);
        assertThat(statement.get(0).path("labelId").asString()).isEqualTo(labelId);
        // Filters for the Seller Center label-charge and statement tabs (S256).
        assertThat(ok(send("GET", "/v1/payouts/label-charges?status=CREDITED", null, null)).size()).isZero();
        assertThat(ok(send("GET", "/v1/payouts/label-charges?status=CHARGED&from=2000-01-01T00:00:00Z", null, null))
                .size()).isEqualTo(1);
        assertThat(ok(send("GET", "/v1/payouts/label-charges?to=2000-01-01T00:00:00Z", null, null)).size()).isZero();
        assertThat(ok(send("GET", "/v1/payouts/statement?kind=CAPTURE", null, null)).size()).isZero();
        assertThat(ok(send("GET", "/v1/payouts/statement?kind=LABEL_CHARGE&kind=LABEL_CREDIT", null, null)).size())
                .isEqualTo(1);

        TestSupport.as(TestSupport.seller(org("org_other"), "seller.owner"));
        assertThat(ok(send("GET", "/v1/payouts/label-charges?orgId=" + org, null, null)).size())
                .as("members only ever see their own organisation").isZero();
        TestSupport.as(TestSupport.FINANCE);
        assertThat(ok(send("GET", "/v1/payouts/label-charges?orgId=" + org, null, null)).size()).isEqualTo(1);

        deliver(VOIDED, UUID.randomUUID(), payload);
        deliver(VOIDED, UUID.randomUUID(), payload);
        assertThat(journals("label-credit:" + labelId)).as("credited back once").isEqualTo(1);
        assertThat(orgBalance("2000", org, org)).isZero();
        assertThat(orgBalance("2400", "", org)).isZero();
        assertThat(orgBalance("4500", "", org)).isZero();
        assertEveryJournalBalances();

        balance = balanceOf(org);
        assertThat(balance.path("labelChargesMinor").asLong()).isZero();
        assertThat(balance.path("availableMinor").asLong()).isZero();
        assertThat(ok(send("GET", "/v1/payouts/label-charges", null, null)).get(0).path("status").asString())
                .isEqualTo("CREDITED");
        statement = ok(send("GET", "/v1/payouts/statement", null, null));
        assertThat(statement.size()).isEqualTo(2);
        assertThat(statement.get(0).path("kind").asString()).isEqualTo("LABEL_CREDIT");
        assertThat(statement.get(1).path("kind").asString()).isEqualTo("LABEL_CHARGE");
        assertThat(statement.get(0).path("amountMinor").asLong()).isEqualTo(1124);
        assertThat(statement.get(0).path("trackingNumber").asString()).isEqualTo(tracking);

        // A late redelivery of the purchase does not charge again.
        deliver(PURCHASED, UUID.randomUUID(), payload);
        assertThat(orgBalance("2000", org, org)).isZero();
    }

    @Test
    void aVoidThatOvertakesItsPurchaseMeansTheLabelIsNeverCharged() {
        String org = org("org_v");
        String labelId = UUID.randomUUID().toString();
        String payload = label(labelId, org, id("1Z"), "SELLER_SHIPPED", "SELLER", 800, 0);
        deliver(VOIDED, UUID.randomUUID(), payload);
        deliver(PURCHASED, UUID.randomUUID(), payload);
        assertThat(journals("label-charge:" + labelId)).isZero();
        assertThat(orgBalance("2000", org, org)).isZero();
        assertThat(balanceOf(org).path("labelChargesMinor").asLong()).isZero();
    }

    @Test
    void afroTransactRetailLabelsAreNeverCharged() {
        String labelId = UUID.randomUUID().toString();
        String payload = label(labelId, RETAIL, id("1Z"), "SELLER_SHIPPED", "SELLER", 999, 0);
        deliver(PURCHASED, UUID.randomUUID(), payload);
        deliver(VOIDED, UUID.randomUUID(), payload);
        String billedToPlatform = UUID.randomUUID().toString();
        deliver(PURCHASED, UUID.randomUUID(), label(billedToPlatform, RETAIL, id("1Z"), "SELLER_SHIPPED",
                "AFROTRANSACT", 999, 0));
        assertThat(journals("label-charge:" + labelId)).isZero();
        assertThat(journals("label-charge:" + billedToPlatform)).isZero();
        assertThat(labelRows(labelId)).isZero();
        assertThat(jdbc.sql("select count(*) from journal_line where account_code = '2000' and account_key = :r")
                .param("r", RETAIL).query(Long.class).single()).as("Retail never has a payable").isZero();
    }

    @Test
    void fbaAndAfroTransactDeliveryLabelsAreNeverCharged() {
        String org = org("org_f");
        for (String fulfilment : List.of("FBA", "AFROTRANSACT_DELIVERY")) {
            for (String billTo : List.of("SELLER", "AFROTRANSACT")) {
                String labelId = UUID.randomUUID().toString();
                deliver(PURCHASED, UUID.randomUUID(), label(labelId, org, id("1Z"), fulfilment, billTo, 1250, 0));
                assertThat(journals("label-charge:" + labelId)).as(fulfilment + " / " + billTo).isZero();
                assertThat(labelRows(labelId)).isZero();
            }
        }
        assertThat(orgBalance("2000", org, org)).isZero();
        JsonNode balance = balanceOf(org);
        assertThat(balance.path("labelChargesMinor").asLong()).isZero();
        assertThat(ok(send("GET", "/v1/payouts/label-charges", null, null)).size()).isZero();
    }

    @Test
    void outstandingChargesAreRecoveredFromTheNextTransfer() {
        String order = id("ORD");
        String org = org("org_t");
        String account = "acct_" + org.replace("-", "").replace("_", "");
        String labelId = UUID.randomUUID().toString();
        String payload = label(labelId, org, id("1Z"), "SELLER_SHIPPED", "SELLER", 999, 125);
        deliver(PURCHASED, UUID.randomUUID(), payload);

        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + org, Map.of("stripeAccountId", account), null));
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", org, "Groceries", "SELF", 1, 10_000, 0,
                825)));
        STRIPE.chargeFees("ch_" + order, new long[] {10_825, 320});
        capture(payment.path("id").asString(), order, order + "-S1", List.of(), 10_825, 320, true);
        long net = 10_000 - 800 - 320;
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of(), null));
        JsonNode balance = balanceOf(org);
        assertThat(balance.path("onHoldMinor").asLong()).as("the label charge is not an order's").isEqualTo(net);
        assertThat(balance.path("availableMinor").asLong()).isEqualTo(-1124);

        STRIPE.transfer(order, account, "tr_" + org, net - 1124);
        TestSupport.CLOCK.advance(Duration.ofDays(8));
        settlements.releaseMatured(500);
        LoggedRequest transfer = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + order))).getFirst();
        assertThat(transfer.getBodyAsString()).contains("amount=" + (net - 1124));
        assertThat(balance(order, "2000", org)).isZero();
        assertThat(orgBalance("2000", org, org)).isZero();
        assertEveryJournalBalances();

        balance = balanceOf(org);
        assertThat(balance.path("labelChargesMinor").asLong()).isZero();
        assertThat(balance.path("availableMinor").asLong()).isEqualTo(net - 1124);

        // Voided after it was recovered: the credit is owed back and paid with the next transfer, not out of
        // what is available now.
        deliver(VOIDED, UUID.randomUUID(), payload);
        balance = balanceOf(org);
        assertThat(balance.path("labelChargesMinor").asLong()).isEqualTo(-1124);
        assertThat(balance.path("availableMinor").asLong()).isEqualTo(net - 1124);
        assertEveryJournalBalances();
    }
}
