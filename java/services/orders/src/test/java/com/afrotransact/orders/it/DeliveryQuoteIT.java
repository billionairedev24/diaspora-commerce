package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.application.DeliveryQuoteSweepService;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * "Ask for a delivery quote" end to end: the shopper asks, customer care agrees an amount, the shopper checks out with
 * it (single use, same cart and address, not expired) and the order carries it as shipping.
 */
class DeliveryQuoteIT extends OrdersIntegrationTest {

    private static final String AGREE = "delivery-quotes:agree";
    private static final String VIEW = "delivery-quotes:view";
    private static final String ASSIGN = "delivery-quotes:assign";

    private DeliveryQuoteSweepService sweep;

    @BeforeEach
    void wire(@Autowired DeliveryQuoteSweepService sweepService) {
        this.sweep = sweepService;
        jdbc.sql("update delivery_quote_settings set auto_assign = 'NONE', requests_per_hour = 50, "
                + "level1_role = null, level1_user_ids = '{}', level2_role = null, level2_user_ids = '{}', "
                + "min_amount_minor = 500, max_amount_minor = 50000, validity_hours = 48, sla_business_days = 1 "
                + "where id = 1").update();
        staff("delivery-quotes:agree", "agent-1", "Amina Bello", "agent-2", "Kofi Mensah");
    }

    // ------------------------------------------------------------------ fixtures

    private static Actor agent(String id) {
        return new Actor(id, PrincipalType.STAFF, Optional.empty(), Set.of("platform.customer_support"),
                Set.of(VIEW, AGREE));
    }

    private static Actor supervisor() {
        return new Actor("sup-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.operations_manager"),
                Set.of(VIEW, ASSIGN));
    }

    private static Actor viewer() {
        return new Actor("viewer-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.auditor"), Set.of(VIEW));
    }

    /** The staff directory (identity): who holds {@code permission}. */
    private void staff(String permission, String... idsAndNames) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < idsAndNames.length; i += 2) {
            sb.append(i == 0 ? "" : ",").append("{\"userId\":\"").append(idsAndNames[i]).append("\",\"name\":\"")
                    .append(idsAndNames[i + 1]).append("\"}");
        }
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/internal/staff"))
                .withQueryParam("permission", WireMock.equalTo(permission)), 200, sb.append("]").toString());
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/internal/staff"))
                .withQueryParam("ids", WireMock.matching(".*")), 200, sb.toString());
    }

    private record Shopper(String id, String bearer, String org, String atin, String pack) {
    }

    /** A shopper with one seller line of 40.00 and a saved address in Houston. */
    private Shopper shopper() {
        String customer = id("cust");
        String bearer = "tok-" + customer;
        String org = id("org-sel");
        String atin = id("ATS");
        UPSTREAMS.cart(bearer, Upstreams.pkg(org, "Mama Tee", "SELLER", Upstreams.line(atin, org, "SELLER", 4000, 1)));
        UPSTREAMS.addresses(bearer, "addr-1", "TX");
        UPSTREAMS.reservation(atin, "HOU-FC", 1);
        return new Shopper(customer, bearer, org, atin, org + ":SELLER");
    }

    private JsonNode ask(Shopper s) {
        TestSupport.as(TestSupport.customer(s.id()));
        return ok(send("POST", "/v1/delivery-quotes", "{\"addressId\":\"addr-1\",\"contact\":\"EMAIL\","
                + "\"note\":\"Gate code 4821\"}", "Authorization", "Bearer " + s.bearer()));
    }

    private JsonNode staffPost(Actor actor, String id, String path, String body) {
        TestSupport.as(actor);
        return ok(send("POST", "/v1/staff/delivery-quotes/" + id + path, body));
    }

    private JsonNode claim(Actor actor, String id) {
        return staffPost(actor, id, "/assignment", "{\"action\":\"CLAIM\",\"actorName\":\"" + nameOf(actor) + "\"}");
    }

    private static String nameOf(Actor a) {
        return switch (a.subject()) {
            case "agent-1" -> "Amina Bello";
            case "agent-2" -> "Kofi Mensah";
            case "sup-1" -> "Sade Ojo";
            default -> "Someone";
        };
    }

    private JsonNode agree(Actor actor, String id, long amount) {
        return staffPost(actor, id, "/agree", "{\"amountMinor\":" + amount + ",\"reason\":\"Rural route, 140 miles\","
                + "\"actorName\":\"" + nameOf(actor) + "\"}");
    }

    /** A ready quote of {@code amount} for the shopper's cart. */
    private String readyQuote(Shopper s, long amount) {
        String id = ask(s).path("id").asString();
        claim(agent("agent-1"), id);
        agree(agent("agent-1"), id, amount);
        return id;
    }

    private String checkoutBody(Shopper s, String quoteId) {
        return "{\"addressId\":\"addr-1\",\"deliverySelections\":[{\"packageId\":\"" + s.pack()
                + "\",\"option\":\"carrier\"" + (quoteId == null ? "" : ",\"quoteId\":\"" + quoteId + "\"") + "}]}";
    }

    private HttpResponse<String> checkout(Shopper s, String quoteId, String key) {
        String paymentId = UUID.randomUUID().toString();
        UPSTREAMS.paymentIntent(s.id(), paymentId);
        TestSupport.as(TestSupport.customer(s.id()));
        return send("POST", "/v1/checkout", checkoutBody(s, quoteId), "Idempotency-Key", key, "Authorization",
                "Bearer " + s.bearer());
    }

    private String quoteStatus(String quoteId) {
        return jdbc.sql("select status from delivery_quote where id = :id").param("id", UUID.fromString(quoteId))
                .query(String.class).single();
    }

    // ------------------------------------------------------------------ the shopper

    @Test
    void askingIsIdempotentAndShowsAStatusInPlainWords() {
        Shopper s = shopper();
        JsonNode first = ask(s);
        assertThat(first.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(first.path("statusLabel").asString()).isEqualTo("Requested");
        assertThat(first.path("place").asString()).isEqualTo("Houston, TX 77002");
        assertThat(first.path("lines")).hasSize(1);
        assertThat(first.path("packages").get(0).path("itemCount").asInt()).isEqualTo(1);
        assertThat(first.has("amountMinor") && !first.path("amountMinor").isNull()).isFalse();

        assertThat(ask(s).path("id").asString()).isEqualTo(first.path("id").asString());
        assertThat(jdbc.sql("select count(*) from delivery_quote where customer_id = :c").param("c", s.id())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(outbox("orders.delivery-quote.requested", first.path("id").asString())).isEqualTo(1);

        TestSupport.as(TestSupport.customer(s.id()));
        JsonNode mine = ok(send("GET", "/v1/delivery-quotes", null));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("reference").asString()).startsWith("DQ-");
    }

    @Test
    void aDifferentCartIsAnotherRequestAndOnlyTheOwnerCanReadIt() {
        Shopper s = shopper();
        String id = ask(s).path("id").asString();
        String atin2 = id("ATX");
        UPSTREAMS.cart(s.bearer(), Upstreams.pkg(s.org(), "Mama Tee", "SELLER",
                Upstreams.line(s.atin(), s.org(), "SELLER", 4000, 2), Upstreams.line(atin2, s.org(), "SELLER", 100, 1)));
        assertThat(ask(s).path("id").asString()).isNotEqualTo(id);

        TestSupport.as(TestSupport.customer(id("other")));
        problem(send("GET", "/v1/delivery-quotes/" + id, null), 404, "orders.quote-not-found");
    }

    @Test
    void requestsAreRateLimitedPerShopperButRepeatsAreNot() {
        jdbc.sql("update delivery_quote_settings set requests_per_hour = 2 where id = 1").update();
        Shopper s = shopper();
        ask(s);
        String atin2 = id("ATX");
        UPSTREAMS.cart(s.bearer(), Upstreams.pkg(s.org(), "Mama Tee", "SELLER",
                Upstreams.line(atin2, s.org(), "SELLER", 100, 1)));
        ask(s);
        ask(s); // the same cart again: the same request, not a third
        String atin3 = id("ATY");
        UPSTREAMS.cart(s.bearer(), Upstreams.pkg(s.org(), "Mama Tee", "SELLER",
                Upstreams.line(atin3, s.org(), "SELLER", 100, 1)));
        TestSupport.as(TestSupport.customer(s.id()));
        HttpResponse<String> limited = send("POST", "/v1/delivery-quotes", "{\"addressId\":\"addr-1\"}",
                "Authorization", "Bearer " + s.bearer());
        problem(limited, 429, "orders.quote-rate-limited");
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void staffAndAnonymousCallersCannotUseTheShopperEndpoints() {
        TestSupport.as(agent("agent-1"));
        assertThat(send("POST", "/v1/delivery-quotes", "{\"addressId\":\"addr-1\"}").statusCode()).isEqualTo(403);
        assertThat(send("GET", "/v1/delivery-quotes", null).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ the desk

    @Test
    void theDeskNeedsThePermissionAndRespectsWhoHasTheCase() {
        Shopper s = shopper();
        String id = ask(s).path("id").asString();

        TestSupport.as(TestSupport.customer(s.id()));
        assertThat(send("GET", "/v1/staff/delivery-quotes", null).statusCode()).isEqualTo(403);
        TestSupport.as(viewer());
        JsonNode queue = ok(send("GET", "/v1/staff/delivery-quotes?queue=UNASSIGNED", null));
        assertThat(queue.path("items")).isNotEmpty();
        assertThat(send("POST", "/v1/staff/delivery-quotes/" + id + "/assignment", "{\"action\":\"CLAIM\"}")
                .statusCode()).isEqualTo(403);
        assertThat(send("POST", "/v1/staff/delivery-quotes/" + id + "/agree", "{\"amountMinor\":2000,\"reason\":"
                + "\"a good reason\"}").statusCode()).isEqualTo(403);

        JsonNode claimed = claim(agent("agent-1"), id);
        assertThat(claimed.path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(claimed.path("statusLabel").asString()).isEqualTo("Being quoted");
        assertThat(claimed.path("workflow").path("holder").asString()).isEqualTo("with Amina Bello");
        assertThat(claimed.path("workflow").path("mine").asBoolean()).isTrue();
        // repeating the claim changes nothing; someone else cannot take it, or decide it
        claim(agent("agent-1"), id);
        TestSupport.as(agent("agent-2"));
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/assignment", "{\"action\":\"CLAIM\"}"), 409,
                "orders.quote-already-assigned");
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/agree", "{\"amountMinor\":2000,\"reason\":"
                + "\"a good reason\"}"), 403, "orders.quote-held-by-other");

        // a supervisor reassigns; the timeline names people
        JsonNode moved = staffPost(supervisor(), id, "/assignment", "{\"action\":\"ASSIGN\",\"assigneeId\":\"agent-2\","
                + "\"note\":\"Amina is off\",\"actorName\":\"Sade Ojo\"}");
        assertThat(moved.path("workflow").path("assigneeName").asString()).isEqualTo("Kofi Mensah");
        assertThat(moved.path("workflow").path("assignedByName").asString()).isEqualTo("Sade Ojo");
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/assignment", "{\"action\":\"ASSIGN\","
                + "\"assigneeId\":\"nobody\"}"), 422, "orders.assignee-not-eligible");

        TestSupport.as(supervisor());
        JsonNode detail = ok(send("GET", "/v1/staff/delivery-quotes/" + id, null));
        assertThat(detail.path("timeline").get(0).path("action").asString()).isEqualTo("REQUESTED");
        assertThat(detail.toString()).contains("Taken over", "Assigned to Kofi Mensah").doesNotContain("agent-2");
        // released back to the pool by the person who has it
        TestSupport.as(agent("agent-2"));
        JsonNode released = ok(send("POST", "/v1/staff/delivery-quotes/" + id + "/assignment",
                "{\"action\":\"RELEASE\",\"note\":\"Not my area\"}"));
        assertThat(released.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(released.path("workflow").path("holder").asString()).isEqualTo("any customer care agent");
        assertThat(outbox("orders.delivery-quote.assigned", id)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void agreeingNeedsAReasonAndAnAmountInBoundsAndMakesTheQuoteReady() {
        Shopper s = shopper();
        String id = ask(s).path("id").asString();
        claim(agent("agent-1"), id);
        TestSupport.as(agent("agent-1"));
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/agree", "{\"amountMinor\":2000,\"reason\":\"  \"}"),
                400, "validation.failed");
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/agree", "{\"amountMinor\":2000,\"reason\":\"abc\"}"),
                422, "orders.invalid-quote-amount");
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/agree",
                "{\"amountMinor\":100,\"reason\":\"Too cheap to be real\"}"), 422, "orders.invalid-quote-amount");
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/agree",
                "{\"amountMinor\":9999999,\"reason\":\"Too dear to be real\"}"), 422, "orders.invalid-quote-amount");

        JsonNode ready = agree(agent("agent-1"), id, 2250);
        assertThat(ready.path("status").asString()).isEqualTo("READY");
        assertThat(ready.path("amountMinor").asLong()).isEqualTo(2250);
        assertThat(ready.path("agreedByName").asString()).isEqualTo("Amina Bello");
        assertThat(ready.path("agreedReason").asString()).isEqualTo("Rural route, 140 miles");
        Instant expires = Instant.parse(ready.path("expiresAt").asString());
        assertThat(Duration.between(Instant.now(), expires).toHours()).isBetween(47L, 48L);
        // the same call again by the same person is a no-op (a retry); the notification went out once
        agree(agent("agent-1"), id, 2250);
        assertThat(outbox("orders.delivery-quote.ready", id)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from delivery_quote_event where quote_id = :q and action = 'AGREED'")
                .param("q", UUID.fromString(id)).query(Long.class).single()).isEqualTo(1);

        // the shopper sees the amount and when it ends, not who agreed it or why
        TestSupport.as(TestSupport.customer(s.id()));
        JsonNode mine = ok(send("GET", "/v1/delivery-quotes/" + id, null));
        assertThat(mine.path("statusLabel").asString()).isEqualTo("Quote ready");
        assertThat(mine.path("amountMinor").asLong()).isEqualTo(2250);
        assertThat(mine.path("usable").asBoolean()).isTrue();
        assertThat(mine.toString()).doesNotContain("Amina", "Rural route");

        // a closed case cannot be decided again
        TestSupport.as(agent("agent-1"));
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/decline", "{\"reason\":\"Changed my mind\"}"), 409,
                "orders.quote-closed");
        // the waiting tab shows it
        assertThat(ok(send("GET", "/v1/staff/delivery-quotes?queue=WAITING", null)).path("items").toString())
                .contains(id);
    }

    @Test
    void decliningNeedsAReasonAndTheShopperIsToldPlainly() {
        Shopper s = shopper();
        String id = ask(s).path("id").asString();
        TestSupport.as(agent("agent-1"));
        problem(send("POST", "/v1/staff/delivery-quotes/" + id + "/decline", "{\"reason\":\"no\"}"), 422,
                "orders.reason-required");
        JsonNode declined = staffPost(agent("agent-1"), id, "/decline", "{\"reason\":\"Outside every route we run\","
                + "\"actorName\":\"Amina Bello\"}");
        assertThat(declined.path("status").asString()).isEqualTo("DECLINED");
        assertThat(declined.path("declinedByName").asString()).isEqualTo("Amina Bello");
        assertThat(outbox("orders.delivery-quote.declined", id)).isEqualTo(1);
        TestSupport.as(TestSupport.customer(s.id()));
        JsonNode mine = ok(send("GET", "/v1/delivery-quotes/" + id, null));
        assertThat(mine.path("statusLabel").asString()).isEqualTo("Declined");
        assertThat(mine.toString()).doesNotContain("Outside every route");
    }

    // ------------------------------------------------------------------ the order

    @Test
    void theAgreedAmountBecomesTheShippingChargeAndTheQuoteIsUsedOnce() {
        Shopper s = shopper();
        String quote = readyQuote(s, 1800);

        HttpResponse<String> placed = checkout(s, quote, "idem-" + s.id());
        JsonNode created = ok(placed, 201);
        assertThat(created.path("shippingMinor").asLong()).isEqualTo(1800);
        // 40.00 items + 8.25 % tax + the agreed 18.00
        assertThat(created.path("totalMinor").asLong()).isEqualTo(4000 + 330 + 1800);
        assertThat(quoteStatus(quote)).isEqualTo("USED");
        assertThat(jdbc.sql("select used_order_id::text from delivery_quote where id = :id")
                .param("id", UUID.fromString(quote)).query(String.class).single())
                .isEqualTo(created.path("orderId").asString());
        // the shipment says which quote priced it
        assertThat(jdbc.sql("select delivery_fee_rule->>'source' from shipment where order_id = :o")
                .param("o", UUID.fromString(created.path("orderId").asString())).query(String.class).single())
                .isEqualTo("DELIVERY_QUOTE");

        // retrying the same checkout (same key) is the same order and does not fail on the used quote
        JsonNode again = ok(send("POST", "/v1/checkout", checkoutBody(s, quote), "Idempotency-Key", "idem-" + s.id(),
                "Authorization", "Bearer " + s.bearer()), 201);
        assertThat(again.path("orderId").asString()).isEqualTo(created.path("orderId").asString());

        // another checkout cannot reuse it
        TestSupport.as(TestSupport.customer(s.id()));
        problem(send("POST", "/v1/checkout", checkoutBody(s, quote), "Idempotency-Key", "idem-2-" + s.id(),
                "Authorization", "Bearer " + s.bearer()), 422, "orders.quote-used");
    }

    @Test
    void aQuoteOfSomeoneElseOrForAChangedCartOrAddressIsVoid() {
        Shopper s = shopper();
        String quote = readyQuote(s, 1800);

        // another shopper: not found, never "belongs to someone else"
        Shopper other = shopper();
        problem(checkout(other, quote, "idem-" + other.id()), 422, "orders.quote-not-found");

        // the cart changed
        UPSTREAMS.cart(s.bearer(), Upstreams.pkg(s.org(), "Mama Tee", "SELLER",
                Upstreams.line(s.atin(), s.org(), "SELLER", 4000, 2)));
        UPSTREAMS.reservation(s.atin(), "HOU-FC", 2);
        JsonNode changed = problem(checkout(s, quote, "idem-a-" + s.id()), 422, "orders.quote-changed");
        assertThat(changed.path("detail").asString()).contains("cart or delivery address changed");

        // the address changed
        UPSTREAMS.cart(s.bearer(), Upstreams.pkg(s.org(), "Mama Tee", "SELLER",
                Upstreams.line(s.atin(), s.org(), "SELLER", 4000, 1)));
        UPSTREAMS.json(WireMock.get(WireMock.urlPathEqualTo("/v1/me/addresses"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + s.bearer())), 200,
                "[{\"id\":\"addr-1\",\"fullName\":\"Ada Obi\",\"line1\":\"9 Far Road\",\"city\":\"Marfa\","
                        + "\"state\":\"TX\",\"postalCode\":\"79843\",\"country\":\"US\"}]");
        problem(checkout(s, quote, "idem-b-" + s.id()), 422, "orders.quote-changed");
        assertThat(quoteStatus(quote)).isEqualTo("READY");
    }

    @Test
    void anExpiredOrNotYetReadyQuoteIsRefusedAndTheSweepRecordsTheExpiry() {
        Shopper s = shopper();
        String notReady = ask(s).path("id").asString();
        problem(checkout(s, notReady, "idem-n-" + s.id()), 422, "orders.quote-not-ready");

        claim(agent("agent-1"), notReady);
        agree(agent("agent-1"), notReady, 1800);
        jdbc.sql("update delivery_quote set expires_at = now() - interval '1 minute' where id = :id")
                .param("id", UUID.fromString(notReady)).update();
        problem(checkout(s, notReady, "idem-e-" + s.id()), 422, "orders.quote-expired");
        TestSupport.as(TestSupport.customer(s.id()));
        assertThat(ok(send("GET", "/v1/delivery-quotes/" + notReady, null)).path("statusLabel").asString())
                .isEqualTo("Expired");

        assertThat(sweep.expire()).isGreaterThanOrEqualTo(1);
        assertThat(sweep.expire()).isZero();
        assertThat(quoteStatus(notReady)).isEqualTo("EXPIRED");
        assertThat(outbox("orders.delivery-quote.expired", notReady)).isEqualTo(1);
    }

    @Test
    void aCheckoutThatFailsGivesTheQuoteBack() {
        Shopper s = shopper();
        String quote = readyQuote(s, 1800);
        // the stock is gone: the saga fails after the quote was used and compensates
        UPSTREAMS.insufficient(s.atin());
        HttpResponse<String> failed = checkout(s, quote, "idem-f-" + s.id());
        assertThat(failed.statusCode()).isGreaterThanOrEqualTo(400);
        eventually(() -> "READY".equals(quoteStatus(quote)), "the quote restored");
        assertThat(jdbc.sql("select count(*) from delivery_quote_event where quote_id = :q and action = 'RESTORED'")
                .param("q", UUID.fromString(quote)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aQuoteOnAPickupPackageIsRefused() {
        Shopper s = shopper();
        String quote = readyQuote(s, 1800);
        TestSupport.as(TestSupport.customer(s.id()));
        HttpResponse<String> r = send("POST", "/v1/checkout", "{\"addressId\":\"addr-1\",\"deliverySelections\":[{"
                + "\"packageId\":\"" + s.pack() + "\",\"option\":\"pickup\",\"pickupPointId\":\"PP-1\","
                + "\"quoteId\":\"" + quote + "\"}]}", "Idempotency-Key", "idem-p-" + s.id(), "Authorization",
                "Bearer " + s.bearer());
        assertThat(r.statusCode()).isGreaterThanOrEqualTo(400);
        eventually(() -> "READY".equals(quoteStatus(quote)), "the quote restored after the refused pickup");
    }

    // ------------------------------------------------------------------ escalation and settings

    @Test
    void aLateRequestIsEscalatedOnceToThePeopleTheSettingsNameAndShownToThem() {
        Shopper s = shopper();
        String id = ask(s).path("id").asString();
        // nobody is set up for level 1 yet: nothing happens, and it is retried next run
        jdbc.sql("update delivery_quote set stage_entered_at = now() - interval '6 hours' where id = :id")
                .param("id", UUID.fromString(id)).update();
        assertThat(sweep.escalate()).isZero();

        TestSupport.as(supervisor());
        ok(send("PUT", "/v1/staff/delivery-quote-settings", "{\"slaBusinessDays\":1,\"dueSoonPercent\":75,"
                + "\"unassignedAfterHours\":4,\"level2AfterHours\":24,\"autoAssign\":\"NONE\",\"level1\":{\"roleKey\":"
                + "null,\"userIds\":[\"agent-2\"]},\"level2\":{\"userIds\":[]},\"minAmountMinor\":500,"
                + "\"maxAmountMinor\":50000,\"validityHours\":48,\"requestsPerHour\":50,\"duplicateWindowMinutes\":15,"
                + "\"reason\":\"Kofi covers escalations\",\"actorName\":\"Sade Ojo\"}"));
        assertThat(sweep.escalate()).isGreaterThanOrEqualTo(1);
        assertThat(sweep.escalate()).isZero(); // once per level
        TestSupport.as(supervisor());
        JsonNode detail = ok(send("GET", "/v1/staff/delivery-quotes/" + id, null));
        assertThat(detail.path("workflow").path("escalationLevel").asInt()).isEqualTo(1);
        assertThat(detail.path("workflow").path("escalatedTo").get(0).asString()).isEqualTo("Kofi Mensah");
        assertThat(outbox("orders.delivery-quote.escalated", id)).isEqualTo(1);
        assertThat(ok(send("GET", "/v1/staff/delivery-quotes?queue=ALL&escalated=true", null)).path("items")
                .toString()).contains(id);
    }

    @Test
    void settingsChangesNeedAssignAReasonAndAreAudited() {
        TestSupport.as(agent("agent-1"));
        String change = "{\"slaBusinessDays\":2,\"dueSoonPercent\":75,\"unassignedAfterHours\":4,"
                + "\"level2AfterHours\":24,\"autoAssign\":\"NONE\",\"minAmountMinor\":500,\"maxAmountMinor\":50000,"
                + "\"validityHours\":48,\"requestsPerHour\":50,\"duplicateWindowMinutes\":15,\"reason\":\"Busy week\"}";
        assertThat(send("PUT", "/v1/staff/delivery-quote-settings", change).statusCode()).isEqualTo(403);
        TestSupport.as(supervisor());
        problem(send("PUT", "/v1/staff/delivery-quote-settings", change.replace("\"minAmountMinor\":500",
                "\"minAmountMinor\":60000")), 422, "orders.invalid-quote-settings");
        JsonNode saved = ok(send("PUT", "/v1/staff/delivery-quote-settings", change.replace("}", ",\"actorName\":"
                + "\"Sade Ojo\"}")));
        assertThat(saved.path("slaBusinessDays").asInt()).isEqualTo(2);
        assertThat(saved.path("changes").get(0).path("actorName").asString()).isEqualTo("Sade Ojo");
        assertThat(saved.path("changes").get(0).path("reason").asString()).isEqualTo("Busy week");
        TestSupport.as(viewer());
        assertThat(ok(send("GET", "/v1/staff/delivery-quote-settings", null)).path("slaBusinessDays").asInt())
                .isEqualTo(2);
    }
}
