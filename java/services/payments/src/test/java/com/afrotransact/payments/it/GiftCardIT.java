package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.afrotransact.payments.application.GiftCardService;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * S321 against PostgreSQL and the Stripe stand-in: buying with the card payment, the code generated and emailed on
 * the send date and stored only as a hash, redeeming once into credit, parallel double redeems, brute-force limits,
 * staff issue / void / resend / lookup, and the staff-configurable limits.
 */
class GiftCardIT extends PaymentsIntegrationTest {

    private static final Actor STAFF = new Actor("fin-gift", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.finance"), Set.of("payments:read", "ledger:adjust", "ledger:approve"));

    GiftCardService giftCards;

    @BeforeEach
    void wireGiftCards(@Autowired GiftCardService service) {
        this.giftCards = service;
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode start(String customer, long amount, Instant sendAt, String email) {
        String pi = "pi_gc_" + UUID.randomUUID().toString().substring(0, 12);
        STRIPE.giftCardIntent(customer, pi, amount);
        TestSupport.as(TestSupport.customer(customer));
        Map<String, Object> body = new HashMap<>(Map.of("amountMinor", amount, "recipientEmail", email,
                "recipientName", "Ama", "senderName", "Kofi", "message", "Happy birthday!", "locale", "en"));
        if (sendAt != null) {
            body.put("sendAt", sendAt.toString());
        }
        JsonNode started = ok(send("POST", "/v1/gift-cards/purchases", body, "buy-" + UUID.randomUUID()), 201);
        assertThat(started.path("status").asString()).isEqualTo("PENDING_PAYMENT");
        assertThat(started.path("clientSecret").asString()).endsWith("_secret_test");
        ((ObjectNode) started).put("pi", pi);
        return started;
    }

    /** The shopper confirmed the card on the page: the PaymentIntent is authorised at Stripe. */
    private void authorised(JsonNode started, long amount, long fee) {
        String pi = started.path("pi").asString();
        STRIPE.retrieveIntent(pi, amount, "requires_capture");
        STRIPE.capture(pi, "ch_" + pi, amount, "succeeded");
        STRIPE.chargeFees("ch_" + pi, new long[] {amount, fee});
    }

    private JsonNode buy(String customer, long amount, Instant sendAt, String email) {
        JsonNode started = start(customer, amount, sendAt, email);
        authorised(started, amount, 100);
        TestSupport.as(TestSupport.customer(customer));
        JsonNode confirmed = ok(send("POST", "/v1/gift-cards/purchases/" + started.path("id").asString()
                + "/confirm", null, null));
        assertThat(confirmed.path("status").asString()).isEqualTo("ACTIVE");
        return started;
    }

    private String codeOf(String cardId) {
        List<String> payloads = jdbc.sql("""
                select payload::text from platform_outbox where type = 'payments.gift_card.deliver'
                  and aggregate_id = :id order by occurred_at, id
                """).param("id", cardId).query(String.class).list();
        assertThat(payloads).as("delivery events of " + cardId).isNotEmpty();
        return JSON.readTree(payloads.getLast()).path("code").asString();
    }

    private HttpResponse<String> redeem(String customer, String code, String address) {
        TestSupport.as(TestSupport.customer(customer));
        return send("POST", "/v1/gift-cards/redeem", Map.of("code", code), null,
                address == null ? Map.of() : Map.of("X-Client-Address", address));
    }

    private long balance(String customer) {
        TestSupport.as(TestSupport.customer(customer));
        return ok(send("GET", "/v1/credit/me", null, null)).path("balanceMinor").asLong();
    }

    private Map<String, Long> journal(String key) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("""
                select jl.account_code || '/' || jl.side as k, sum(jl.amount_minor) as s
                from journal_line jl join journal_entry je on je.id = jl.journal_id
                where je.journal_key = :k group by 1
                """).param("k", key).query((rs, n) -> out.put(rs.getString("k"), rs.getLong("s"))).list();
        return out;
    }

    private JsonNode card(String id) {
        TestSupport.as(STAFF);
        return ok(send("GET", "/v1/gift-cards/" + id, null, null));
    }

    // ------------------------------------------------------------------ purchase and delivery

    @Test
    void aPurchaseIsPaidWithTheCardPaymentAndIsALiabilityNotRevenue() {
        String buyer = id("buyer");
        JsonNode bought = buy(buyer, 2500, null, "ama@example.com");
        String id = bought.path("id").asString();

        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing("metadata[afrotransact_customer_id]=" + buyer)))
                .getFirst().getBodyAsString()).contains("card").contains("metadata[kind]=gift_card");
        assertEveryJournalBalances();
        Map<String, Long> sold = journal("gift-card-sold:" + id);
        assertThat(sold).containsEntry("2650/C", 2500L).containsEntry("1000/D", 2400L)
                .containsEntry("5000/D", 100L).doesNotContainKey("4400/C");
        // nothing is revenue: no revenue account was credited by the sale
        assertThat(sold.keySet()).noneMatch(k -> k.startsWith("4") || k.startsWith("2600"));

        // the code was generated and handed to notifications; only its keyed hash is stored
        String code = codeOf(id);
        assertThat(code).matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}");
        TestSupport.as(TestSupport.customer(buyer));
        JsonNode mine = ok(send("GET", "/v1/gift-cards/mine", null, null));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(mine.get(0).path("deliveryStatus").asString()).isEqualTo("SENT");
        assertThat(mine.toString()).doesNotContain(code).doesNotContain(code.replace("-", ""));
        String row = jdbc.sql("select row_to_json(g)::text from gift_card g where id = :id::uuid").param("id", id)
                .query(String.class).single();
        assertThat(row).doesNotContain(code).doesNotContain(code.replace("-", ""));
        assertThat(jdbc.sql("select code_hash from gift_card where id = :id::uuid").param("id", id)
                .query(String.class).single()).matches("[0-9a-f]{64}");
        assertThat(card(id).path("codeLast4").asString()).isEqualTo(code.substring(code.length() - 4));
        assertThat(card(id).toString()).doesNotContain(code);
    }

    @Test
    void startingAPurchaseTwiceWithTheSameKeyIsOneCard() {
        String buyer = id("buyer");
        String pi = "pi_gc_" + UUID.randomUUID().toString().substring(0, 12);
        STRIPE.giftCardIntent(buyer, pi, 1500);
        STRIPE.retrieveIntent(pi, 1500, "requires_payment_method");
        TestSupport.as(TestSupport.customer(buyer));
        Map<String, Object> body = Map.of("amountMinor", 1500, "recipientEmail", "x@example.com");
        JsonNode first = ok(send("POST", "/v1/gift-cards/purchases", body, "same-key-" + buyer), 201);
        JsonNode second = ok(send("POST", "/v1/gift-cards/purchases", body, "same-key-" + buyer), 201);
        assertThat(second.path("id").asString()).isEqualTo(first.path("id").asString());
        assertThat(jdbc.sql("select count(*) from gift_card where purchaser_id = :b").param("b", buyer)
                .query(Long.class).single()).isEqualTo(1);
        problem(send("POST", "/v1/gift-cards/purchases", Map.of("amountMinor", 2000, "recipientEmail",
                "x@example.com"), "same-key-" + buyer), 409, "payments.idempotency-key-reused");
    }

    @Test
    void theWebhookIssuesThePurchaseTooAndAnotherShopperCannotConfirmIt() {
        String buyer = id("buyer");
        JsonNode started = start(buyer, 3000, null, "ama@example.com");
        authorised(started, 3000, 90);
        TestSupport.as(TestSupport.customer(id("other")));
        problem(send("POST", "/v1/gift-cards/purchases/" + started.path("id").asString() + "/confirm", null, null),
                403, "payments.not-your-gift-card");

        String pi = started.path("pi").asString();
        webhook(event("evt_" + pi, "payment_intent.amount_capturable_updated", "{\"id\":\"" + pi
                + "\",\"object\":\"payment_intent\",\"status\":\"requires_capture\",\"amount\":3000,"
                + "\"metadata\":{\"kind\":\"gift_card\"}}"));
        assertThat(card(started.path("id").asString()).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(outbox("payments.gift_card.deliver", started.path("id").asString())).isEqualTo(1);
        // a second delivery of the same event changes nothing
        webhook(event("evt2_" + pi, "payment_intent.succeeded", "{\"id\":\"" + pi
                + "\",\"object\":\"payment_intent\",\"status\":\"succeeded\",\"amount\":3000,"
                + "\"metadata\":{\"kind\":\"gift_card\"}}"));
        assertThat(outbox("payments.gift_card.deliver", started.path("id").asString())).isEqualTo(1);
    }

    @Test
    void aScheduledCardGetsItsCodeOnlyOnTheSendDate() {
        String buyer = id("buyer");
        JsonNode bought = buy(buyer, 5000, Instant.now().plus(Duration.ofDays(3)), "later@example.com");
        String id = bought.path("id").asString();
        assertThat(card(id).path("deliveryStatus").asString()).isEqualTo("SCHEDULED");
        assertThat(jdbc.sql("select code_hash from gift_card where id = :id::uuid").param("id", id)
                .query(String.class).optional()).isEmpty();
        assertThat(outbox("payments.gift_card.deliver", id)).isZero();
        giftCards.deliverDue();
        assertThat(outbox("payments.gift_card.deliver", id)).isZero();

        TestSupport.CLOCK.advance(Duration.ofDays(3).plusMinutes(1));
        assertThat(giftCards.deliverDue()).isGreaterThanOrEqualTo(1);
        assertThat(outbox("payments.gift_card.deliver", id)).isEqualTo(1);
        assertThat(card(id).path("deliveryStatus").asString()).isEqualTo("SENT");
        JsonNode event = JSON.readTree(jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id "
                + "and type = 'payments.gift_card.deliver'").param("id", id).query(String.class).single());
        assertThat(event.path("recipient").path("email").asString()).isEqualTo("later@example.com");
        assertThat(event.path("senderName").asString()).isEqualTo("Kofi");
        assertThat(event.path("message").asString()).isEqualTo("Happy birthday!");
        assertThat(event.path("amountMinor").asLong()).isEqualTo(5000);
        // a second run sends nothing again
        giftCards.deliverDue();
        assertThat(outbox("payments.gift_card.deliver", id)).isEqualTo(1);
    }

    @Test
    void theCodeIsHashedAtRestAndLeavesTheOutboxOnceTheEventWasRelayed() {
        String buyer = id("buyer");
        String id = buy(buyer, 2500, null, "hashed@example.com").path("id").asString();
        String code = codeOf(id);
        assertThat(code).isNotBlank();
        // the card row never holds the code, only its keyed hash and the last four characters
        String hash = jdbc.sql("select code_hash from gift_card where id = :id::uuid").param("id", id)
                .query(String.class).single();
        assertThat(hash).hasSize(64).doesNotContain(code.replace("-", ""));
        assertThat(jdbc.sql("select count(*) from gift_card where id = :id::uuid and (code_hash = :c "
                + "or code_last4 = :c)").param("id", id).param("c", code).query(Long.class).single()).isZero();

        // while the event waits for the relay the code must stay in it: notifications needs it for the email
        giftCards.scrubDeliveredCodes();
        assertThat(codeOf(id)).isEqualTo(code);

        // once it was relayed the code is gone from the database altogether
        jdbc.sql("update platform_outbox set published_at = now() where aggregate_id = :id "
                + "and type = 'payments.gift_card.deliver'").param("id", id).update();
        assertThat(giftCards.scrubDeliveredCodes()).isGreaterThanOrEqualTo(1);
        String payload = jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id "
                + "and type = 'payments.gift_card.deliver'").param("id", id).query(String.class).single();
        assertThat(payload).doesNotContain(code).contains("hashed@example.com");
        assertThat(giftCards.scrubDeliveredCodes()).as("repeating is harmless").isZero();
    }

    // ------------------------------------------------------------------ redeem

    @Test
    void aCodeRedeemsOnceIntoTheRecipientsCredit() {
        String buyer = id("buyer");
        String friend = id("friend");
        JsonNode bought = buy(buyer, 2500, null, "friend@example.com");
        String id = bought.path("id").asString();
        String code = codeOf(id);

        // typed in lower case, without dashes, with spaces: the same code
        HttpResponse<String> first = redeem(friend, code.toLowerCase().replace("-", " "), null);
        JsonNode redeemed = ok(first);
        assertThat(redeemed.path("redeemedMinor").asLong()).isEqualTo(2500);
        assertThat(redeemed.path("balanceMinor").asLong()).isEqualTo(2500);
        assertThat(balance(friend)).isEqualTo(2500);
        TestSupport.as(TestSupport.customer(friend));
        JsonNode entry = ok(send("GET", "/v1/credit/me/entries", null, null)).get(0);
        assertThat(entry.path("kind").asString()).isEqualTo("GRANT");
        assertThat(entry.path("source").asString()).isEqualTo("GIFT_CARD");
        assertThat(entry.path("reference").asString()).isEqualTo(id);

        assertThat(card(id).path("status").asString()).isEqualTo("REDEEMED");
        assertThat(card(id).path("redeemedBy").asString()).isEqualTo(friend);
        assertThat(journal("gift-card-redeemed:" + id)).containsEntry("2650/D", 2500L).containsEntry("2600/C", 2500L);
        assertEveryJournalBalances();

        problem(redeem(friend, code, null), 409, "payments.gift-card-redeemed");
        problem(redeem(buyer, code, null), 409, "payments.gift-card-redeemed");
        assertThat(balance(friend)).isEqualTo(2500);
        assertThat(balance(buyer)).isZero();

        // a redeemed card is credit now: it cannot be voided, and nothing refunds it to a card
        TestSupport.as(STAFF);
        problem(send("POST", "/v1/gift-cards/" + id + "/void", Map.of("reason", "Customer asked for the money back"),
                null), 409, "payments.gift-card-redeemed");
    }

    @Test
    void parallelRedeemsOfOneCodeCreditItOnce() throws Exception {
        String buyer = id("buyer");
        String friend = id("friend");
        JsonNode bought = buy(buyer, 4000, null, "friend@example.com");
        String code = codeOf(bought.path("id").asString());
        TestSupport.as(TestSupport.customer(friend));
        int threads = 4; // in-flight guesses count as failures, so more than five at once would be rate limited
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> send("POST", "/v1/gift-cards/redeem", Map.of("code", code), null)));
            }
            for (Future<HttpResponse<String>> call : calls) {
                statuses.add(call.get().statusCode());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses.stream().filter(s -> s == 200).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 1);
        assertThat(balance(friend)).isEqualTo(4000);
        assertThat(jdbc.sql("select count(*) from credit_entry where customer_id = :c").param("c", friend)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void guessingCodesIsRateLimitedPerShopperAndPerAddress() {
        String buyer = id("buyer");
        String guesser = id("guesser");
        JsonNode bought = buy(buyer, 2500, null, "friend@example.com");
        String code = codeOf(bought.path("id").asString());

        for (int i = 0; i < 5; i++) {
            problem(redeem(guesser, "ABCD-EFGH-JKLM-" + (2222 + i), null), 404, "payments.gift-card-invalid");
        }
        // a malformed code counts too
        // the sixth try is refused, even with the right code
        HttpResponse<String> blocked = redeem(guesser, code, null);
        JsonNode problem = problem(blocked, 429, "payments.gift-card-rate-limited");
        assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
        assertThat(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 900L);
        assertThat(problem.path("retryAfterSeconds").asLong()).isPositive();
        assertThat(balance(guesser)).isZero();
        assertThat(card(bought.path("id").asString()).path("status").asString()).isEqualTo("ACTIVE");

        // the window passes: the owner of the code gets in
        TestSupport.CLOCK.advance(Duration.ofMinutes(16));
        assertThat(ok(redeem(guesser, code, null)).path("redeemedMinor").asLong()).isEqualTo(2500);
        TestSupport.CLOCK.reset();

        // one address cannot spread guesses over many shoppers
        String address = "203.0.113." + (10 + (int) (Math.random() * 200));
        for (int i = 0; i < 20; i++) {
            problem(redeem(id("sock"), "bad-code-" + i, address), 404, "payments.gift-card-invalid");
        }
        problem(redeem(id("sock"), "ABCD-EFGH-JKLM-2222", address), 429, "payments.gift-card-rate-limited");
        // another address is not affected
        problem(redeem(id("sock"), "ABCD-EFGH-JKLM-2222", "198.51.100.7"), 404, "payments.gift-card-invalid");
    }

    // ------------------------------------------------------------------ staff

    @Test
    void staffIssueLookupResendAndVoidWithAReason() {
        String friend = id("friend");
        TestSupport.as(STAFF);
        JsonNode issued = ok(send("POST", "/v1/gift-cards", Map.of("amountMinor", 3000, "recipientEmail",
                "gift@example.com", "recipientName", "Esi", "message", "Sorry about the delay",
                "reason", "Goodwill after a delayed pre-order delivery"), null), 201);
        String id = issued.path("id").asString();
        assertThat(issued.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(issued.path("origin").asString()).isEqualTo("STAFF");
        assertThat(issued.path("issuedBy").asString()).isEqualTo("fin-gift");
        assertThat(issued.path("deliveryStatus").asString()).isEqualTo("SENT");
        assertThat(journal("gift-card-issued:" + id)).containsEntry("5200/D", 3000L).containsEntry("2650/C", 3000L);
        String firstCode = codeOf(id);

        // lookup by the last four characters of the code and by the recipient
        TestSupport.as(STAFF);
        JsonNode byCode = ok(send("GET", "/v1/gift-cards?q=" + firstCode.substring(firstCode.length() - 4), null,
                null));
        assertThat(byCode.toString()).contains(id);
        JsonNode byEmail = ok(send("GET", "/v1/gift-cards?q=gift@example.com&status=ACTIVE", null, null));
        assertThat(byEmail.get(0).path("id").asString()).isEqualTo(id);

        // resend: a new code, the old one stops working
        TestSupport.as(STAFF);
        problem(send("POST", "/v1/gift-cards/" + id + "/resend", Map.of("reason", "short"), null), 422,
                "payments.reason-required");
        ok(send("POST", "/v1/gift-cards/" + id + "/resend", Map.of("reason", "The recipient lost the email"), null));
        String secondCode = codeOf(id);
        assertThat(secondCode).isNotEqualTo(firstCode);
        problem(redeem(friend, firstCode, null), 404, "payments.gift-card-invalid");
        assertThat(outbox("payments.gift_card.resent", id)).isEqualTo(1);

        // void: the liability ends, the code stops working
        TestSupport.as(STAFF);
        problem(send("POST", "/v1/gift-cards/" + id + "/void", Map.of("reason", "x"), null), 422,
                "payments.reason-required");
        JsonNode voided = ok(send("POST", "/v1/gift-cards/" + id + "/void",
                Map.of("reason", "Issued to the wrong customer by mistake"), null));
        assertThat(voided.path("status").asString()).isEqualTo("VOID");
        assertThat(voided.path("voidedBy").asString()).isEqualTo("fin-gift");
        assertThat(journal("gift-card-voided:" + id)).containsEntry("2650/D", 3000L).containsEntry("5200/C", 3000L);
        problem(redeem(friend, secondCode, null), 404, "payments.gift-card-invalid");
        assertThat(outbox("payments.gift_card.issued", id)).isEqualTo(1);
        assertThat(outbox("payments.gift_card.voided", id)).isEqualTo(1);
        assertEveryJournalBalances();
    }

    @Test
    void voidingAnUnredeemedPurchaseRefundsTheBuyersCard() {
        String buyer = id("buyer");
        JsonNode bought = buy(buyer, 2000, null, "friend@example.com");
        String id = bought.path("id").asString();
        String pi = bought.path("pi").asString();
        STRIPE.refund(pi, "re_" + pi, 2000, "succeeded");

        TestSupport.as(STAFF);
        JsonNode voided = ok(send("POST", "/v1/gift-cards/" + id + "/void",
                Map.of("reason", "The buyer asked for a refund before it was used"), null));
        assertThat(voided.path("status").asString()).isEqualTo("VOID");
        assertThat(voided.path("refundedToCard").asBoolean()).isTrue();
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/refunds"))
                .withRequestBody(WireMock.containing("payment_intent=" + pi)))).hasSize(1);
        assertThat(journal("gift-card-voided:" + id)).containsEntry("2650/D", 2000L).containsEntry("1000/C", 2000L);
        // a replay refunds nothing twice
        ok(send("POST", "/v1/gift-cards/" + id + "/void", Map.of("reason", "The buyer asked for a refund again"),
                null));
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/refunds"))
                .withRequestBody(WireMock.containing("payment_intent=" + pi)))).hasSize(1);
        assertEveryJournalBalances();
    }

    @Test
    void amountLimitsAndTermsAreStaffSettings() {
        String buyer = id("buyer");
        TestSupport.as(TestSupport.customer(buyer));
        JsonNode config = ok(send("GET", "/v1/gift-cards/config", null, null));
        assertThat(config.path("minMinor").asLong()).isEqualTo(1000);
        assertThat(config.path("maxMinor").asLong()).isEqualTo(50_000);
        assertThat(config.path("presetsMinor")).hasSize(4);

        for (long amount : new long[] {999, 50_001}) {
            TestSupport.as(TestSupport.customer(buyer));
            JsonNode refused = problem(send("POST", "/v1/gift-cards/purchases", Map.of("amountMinor", amount,
                    "recipientEmail", "a@example.com"), "limit-" + amount + buyer), 422,
                    "payments.gift-card-invalid-request");
            assertThat(refused.path("fieldErrors").path("amountMinor").asString()).isNotBlank();
        }
        TestSupport.as(TestSupport.customer(buyer));
        problem(send("POST", "/v1/gift-cards/purchases", Map.of("amountMinor", 2000, "recipientEmail", "nope"),
                "bad-email" + buyer), 422, "payments.gift-card-invalid-request");
        TestSupport.as(TestSupport.customer(buyer));
        problem(send("POST", "/v1/gift-cards/purchases", Map.of("amountMinor", 2000, "recipientEmail",
                "a@example.com", "sendAt", Instant.now().plus(Duration.ofDays(400)).toString()), "far" + buyer), 422,
                "payments.gift-card-invalid-request");

        TestSupport.as(TestSupport.FINANCE);
        JsonNode before = ok(send("GET", "/v1/money/settings", null, null));
        long version = before.path("version").asLong();
        Map<String, Object> change = new HashMap<>(Map.of("giftCardMinMinor", 500, "giftCardMaxMinor", 100_000,
                "giftCardPresets", List.of(1000, 2500), "giftCardTerms", Map.of("en", "Gift cards never expire."),
                "creditApprovalLimitMinor", 5000, "reason", "Raise the cap for the holiday season", "version",
                version));
        try {
            TestSupport.as(MAKER_ONLY);
            problem(send("PUT", "/v1/money/settings", change, null), 403, "auth.missing-permission");
            TestSupport.as(TestSupport.FINANCE);
            Map<String, Object> shortReason = new HashMap<>(change);
            shortReason.put("reason", "short");
            problem(send("PUT", "/v1/money/settings", shortReason, null), 422, "payments.reason-required");
            Map<String, Object> inverted = new HashMap<>(change);
            inverted.put("giftCardMaxMinor", 100);
            problem(send("PUT", "/v1/money/settings", inverted, null), 422, "payments.money-settings-invalid");
            JsonNode updated = ok(send("PUT", "/v1/money/settings", change, null));
            assertThat(updated.path("version").asLong()).isEqualTo(version + 1);
            problem(send("PUT", "/v1/money/settings", change, null), 409, "payments.money-settings-changed");

            TestSupport.as(TestSupport.customer(buyer));
            JsonNode now = ok(send("GET", "/v1/gift-cards/config", null, null));
            assertThat(now.path("minMinor").asLong()).isEqualTo(500);
            assertThat(now.path("maxMinor").asLong()).isEqualTo(100_000);
            assertThat(now.path("terms").path("en").asString()).isEqualTo("Gift cards never expire.");
            STRIPE.giftCardIntent(buyer, "pi_gc_limits_" + buyer, 60_000);
            TestSupport.as(TestSupport.customer(buyer));
            ok(send("POST", "/v1/gift-cards/purchases", Map.of("amountMinor", 60_000, "recipientEmail",
                    "a@example.com"), "now-ok" + buyer), 201);

            TestSupport.as(TestSupport.FINANCE);
            JsonNode history = ok(send("GET", "/v1/money/settings/history?limit=5", null, null));
            assertThat(history.get(0).path("changedBy").asString()).isEqualTo("fin-1");
            assertThat(history.get(0).path("reason").asString()).isEqualTo("Raise the cap for the holiday season");
            assertThat(history.get(0).path("before").asString()).contains("1000");
        } finally {
            TestSupport.as(TestSupport.FINANCE);
            long current = ok(send("GET", "/v1/money/settings", null, null)).path("version").asLong();
            Map<String, Object> restore = new HashMap<>(Map.of("giftCardMinMinor", 1000, "giftCardMaxMinor", 50_000,
                    "giftCardPresets", List.of(2500, 5000, 10_000, 20_000), "giftCardTerms", Map.of(),
                    "creditApprovalLimitMinor", 10_000, "reason", "Restore the defaults after the test", "version",
                    current));
            ok(send("PUT", "/v1/money/settings", restore, null));
        }
    }

    private static final Actor MAKER_ONLY = new Actor("fin-maker-only", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.finance"), Set.of("payments:read", "ledger:adjust"));

    @Test
    void creditNeverBuysAGiftCard() {
        String buyer = id("buyer");
        TestSupport.as(PROGRAM);
        ok(send("POST", "/v1/credit/grants", Map.of("customerId", buyer, "amountMinor", 5000, "source", "PROMO",
                "reason", "Promotion for the test of gift cards"), "grant-" + buyer), 201);
        JsonNode started = start(buyer, 2000, null, "friend@example.com");
        assertThat(started.path("status").asString()).isEqualTo("PENDING_PAYMENT");
        // the purchase is a card payment only: the credit balance is untouched and there is no way to pay with it
        assertThat(balance(buyer)).isEqualTo(5000);
        assertThat(jdbc.sql("select count(*) from credit_entry where customer_id = :c and kind = 'SPEND'")
                .param("c", buyer).query(Long.class).single()).isZero();
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents"))
                .withRequestBody(WireMock.containing("metadata[afrotransact_customer_id]=" + buyer)))
                .getFirst().getBodyAsString()).contains("amount=2000").doesNotContain("credit");
    }

    private static final Actor PROGRAM = new Actor("svc-referral", PrincipalType.SERVICE, Optional.empty(), Set.of(),
            Set.of("payments:credit.grant"));
}
