package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.EnforcementNotice;
import com.afrotransact.payments.application.PaymentsEvents;
import com.afrotransact.payments.application.ProviderFeeNotice;
import com.afrotransact.payments.application.ProviderFeeService;
import com.afrotransact.payments.application.SellerEnforcementService;
import com.afrotransact.payments.application.SettlementService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S220 (payments support for long-dated bookings) and S214 (payout hold in the balance): release of the remaining
 * authorisation after a deposit capture, off-session charges on saved cards with the SCA fallback, late
 * provider-cancellation fees debited from the connected account, and the effective fee schedule.
 */
class LongDatedBookingsIT extends PaymentsIntegrationTest {

    private static final long DEPOSIT = 2_000;
    private static final long BALANCE = 8_000;

    private SettlementService settlements;
    private ProviderFeeService providerFees;
    private SellerEnforcementService enforcement;

    @BeforeEach
    void wire(@Autowired SettlementService settlementService, @Autowired ProviderFeeService feeService,
              @Autowired SellerEnforcementService enforcementService) {
        this.settlements = settlementService;
        this.providerFees = feeService;
        this.enforcement = enforcementService;
    }

    private static String org(String prefix) {
        return id(prefix).toLowerCase(Locale.ROOT);
    }

    private static String account(String org) {
        return "acct_" + org.replace("-", "").replace("_", "");
    }

    private static String stripeCustomer(String customer) {
        return "cus_" + customer.replace("-", "");
    }

    private static String card(String customer) {
        return "pm_card_" + customer;
    }

    /**
     * A booking's two-line intent created with saveForOffSession (the card is attached to the shopper's Stripe
     * Customer when confirmed), authorised, with the deposit captured (multicapture keeps the rest held).
     */
    private JsonNode bookingWithDepositCaptured(String booking, String provider, String customer) {
        String cus = stripeCustomer(customer);
        STRIPE.customer(customer, cus);
        STRIPE.createIntent(booking, "pi_" + booking, DEPOSIT + BALANCE);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode created = ok(send("POST", "/v1/payments", Map.of("orderId", booking, "amountMinor",
                DEPOSIT + BALANCE, "currency", "USD", "shopperId", customer, "saveForOffSession", true, "lines",
                List.of(line("deposit", provider, "services", "SELF", 1, DEPOSIT, 0, 0),
                        line("balance", provider, "services", "SELF", 1, BALANCE, 0, 0))), "create-" + booking), 201);
        LoggedRequest create = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents")).withHeader("Idempotency-Key", WireMock.equalTo(booking + ":create-intent")))
                .getFirst();
        assertThat(create.getBodyAsString()).contains("customer=" + cus, "setup_future_usage=off_session",
                "metadata[afrotransact_customer_id]=" + customer);
        // Stripe attaches the confirmed card to the customer: the wallet records it (first method = default).
        webhook(event(id("evt"), "payment_method.attached", "{\"id\":\"" + card(customer) + "\",\"object\":"
                + "\"payment_method\",\"type\":\"card\",\"customer\":\"" + cus + "\",\"card\":{\"brand\":"
                + "\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2030}}"));
        STRIPE.retrieveIntent("pi_" + booking, DEPOSIT + BALANCE, "requires_capture");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode payment = ok(send("GET", "/v1/payments/" + created.path("id").asString() + "?refresh=true", null,
                null));
        assertThat(payment.path("status").asString()).isEqualTo("AUTHORIZED");
        STRIPE.chargeFees("ch_" + booking, new long[] {DEPOSIT, 88});
        JsonNode cap = capture(payment.path("id").asString(), booking, "deposit", List.of("deposit"), DEPOSIT, 88,
                false);
        assertThat(cap.path("paymentStatus").asString()).isEqualTo("PARTIALLY_CAPTURED");
        return payment;
    }

    private JsonNode release(String paymentId, String booking, String key) {
        STRIPE.retrieveIntent("pi_" + booking, DEPOSIT + BALANCE, "requires_capture");
        STRIPE.cancel("pi_" + booking, DEPOSIT + BALANCE);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        return ok(send("POST", "/v1/payments/" + paymentId + "/release", Map.of("reason", "Booking cancelled"), key),
                200);
    }

    @Test
    void releaseAfterDepositCaptureThenChargeTheBalanceOffSessionAndSettleTheOrder() {
        String booking = id("BK");
        String provider = org("org_p");
        String customer = id("cust").toLowerCase(Locale.ROOT);
        String paymentId = bookingWithDepositCaptured(booking, provider, customer).path("id").asString();
        long journalsBefore = jdbc.sql("select count(*) from journal_entry where order_id = :o").param("o", booking)
                .query(Long.class).single();

        TestSupport.as(TestSupport.ORDERS_SERVICE);
        problem(send("POST", "/v1/payments/" + paymentId + "/cancel", Map.of(), "cancel-" + booking), 409,
                "payments.already-captured");
        problem(send("POST", "/v1/payments/" + paymentId + "/off-session-charges", Map.of("customerId", "c"),
                "os-early-" + booking), 409, "payments.authorization-still-held");

        JsonNode released = release(paymentId, booking, "release-" + booking);
        assertThat(released.path("status").asString()).isEqualTo("CAPTURED");
        assertThat(released.path("capturedMinor").asLong()).isEqualTo(DEPOSIT);
        assertThat(released.path("releasedMinor").asLong()).isEqualTo(BALANCE);
        assertThat(released.path("replayed").asBoolean()).isFalse();
        LoggedRequest cancel = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents/pi_" + booking + "/cancel"))).getFirst();
        assertThat(cancel.getHeader("Idempotency-Key")).isEqualTo(booking + ":release");
        assertThat(release(paymentId, booking, "release-" + booking).path("replayed").asBoolean()).isTrue();
        assertThat(release(paymentId, booking, "release-again-" + booking).path("replayed").asBoolean()).isTrue();
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents/pi_" + booking + "/cancel")));
        assertThat(outbox(PaymentsEvents.AUTHORIZATION_RELEASED, booking)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from journal_entry where order_id = :o").param("o", booking)
                .query(Long.class).single()).as("an authorisation never reaches the ledger").isEqualTo(journalsBefore);

        // The appointment nears: charge the balance off-session on the customer's saved card.
        String cus = stripeCustomer(customer);
        String card = card(customer);
        String childPi = "pi_os_" + booking;
        STRIPE.offSessionIntent(paymentId, childPi, BALANCE, "requires_capture");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode child = ok(send("POST", "/v1/payments/" + paymentId + "/off-session-charges",
                Map.of("customerId", customer, "lineIds", List.of("balance")), "os-" + booking), 201);
        String childId = child.path("id").asString();
        assertThat(child.path("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(child.path("orderId").asString()).isEqualTo(booking);
        assertThat(child.path("parentPaymentId").asString()).isEqualTo(paymentId);
        assertThat(child.path("offSession").asBoolean()).isTrue();
        assertThat(child.path("amountMinor").asLong()).isEqualTo(BALANCE);
        LoggedRequest create = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents")).withRequestBody(WireMock.containing("metadata[parent_payment_id]="
                + paymentId))).getFirst();
        assertThat(create.getBodyAsString()).contains("amount=8000", "capture_method=manual", "confirm=true",
                "off_session=true", "customer=" + cus, "payment_method=" + card, "transfer_group=" + booking);
        assertThat(create.getHeader("Idempotency-Key")).isEqualTo(booking + ":os:" + childId + ":create-intent");
        JsonNode replay = ok(send("POST", "/v1/payments/" + paymentId + "/off-session-charges",
                Map.of("customerId", customer, "lineIds", List.of("balance")), "os-" + booking), 200);
        assertThat(replay.path("id").asString()).isEqualTo(childId);
        problem(send("POST", "/v1/payments/" + paymentId + "/off-session-charges",
                Map.of("customerId", customer), "os-dup-" + booking), 409, "payments.lines-already-charged");
        assertThat(ok(send("GET", "/v1/payments/" + paymentId + "/off-session-charges", null, null)).size())
                .isEqualTo(1);
        assertThat(ok(send("GET", "/v1/payments?orderId=" + booking, null, null)).path("id").asString())
                .as("the order's primary payment").isEqualTo(paymentId);

        // Completed: capture the balance through the usual endpoint, then settle the whole order.
        STRIPE.capture(childPi, "ch_os_" + booking, BALANCE, "succeeded");
        STRIPE.chargeFees("ch_os_" + booking, new long[] {BALANCE, 262});
        ok(send("POST", "/v1/payments/" + childId + "/capture", Map.of("shipmentId", "balance", "lineIds",
                List.of("balance"), "amountMinor", BALANCE, "finalCapture", true), "cap-os-" + booking), 201);
        LoggedRequest cap = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/payment_intents/" + childPi + "/capture"))).getFirst();
        assertThat(cap.getHeader("Idempotency-Key")).isEqualTo(booking + ":os:" + childId + ":capture:balance");
        assertEveryJournalBalances();
        long net = (DEPOSIT - 200 - 88) + (BALANCE - 800 - 262);
        assertThat(balance(booking, "2000", provider)).isEqualTo(net);

        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + provider, Map.of("stripeAccountId", account(provider)), null));
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + booking + "/delivered", Map.of("kind", "SERVICE"), null));
        STRIPE.transfer(booking, account(provider), "tr_" + booking, net);
        TestSupport.CLOCK.advance(Duration.ofDays(7).plusMinutes(5));
        settlements.releaseMatured(500);
        LoggedRequest transfer = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + booking))).getFirst();
        assertThat(transfer.getBodyAsString()).contains("amount=" + net);
        assertThat(balance(booking, "2000", provider)).isZero();
    }

    @Test
    void offSessionChargeNeedingAuthenticationWaitsForTheCustomer() {
        String booking = id("BK");
        String provider = org("org_p");
        String customer = id("cust").toLowerCase(Locale.ROOT);
        String paymentId = bookingWithDepositCaptured(booking, provider, customer).path("id").asString();
        release(paymentId, booking, "release-" + booking);

        String childPi = "pi_sca_" + booking;
        STRIPE.offSessionAuthenticationRequired(paymentId, childPi, BALANCE);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode child = ok(send("POST", "/v1/payments/" + paymentId + "/off-session-charges",
                Map.of("customerId", customer), "os-" + booking), 201);
        String childId = child.path("id").asString();
        assertThat(child.path("status").asString()).isEqualTo("REQUIRES_ACTION");
        assertThat(child.path("failureMessage").asString()).isEqualTo("authentication_required");
        assertThat(child.path("clientSecret").asString()).isEqualTo(childPi + "_secret_test");
        assertThat(outbox(PaymentsEvents.INTENT_ACTION_REQUIRED, booking)).isEqualTo(1);
        assertThat(outbox(PaymentsEvents.INTENT_FAILED, booking)).isZero();
        String payload = jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :a")
                .param("t", PaymentsEvents.INTENT_ACTION_REQUIRED).param("a", booking).query(String.class).single();
        JsonNode action = JSON.readTree(payload);
        assertThat(action.path("customerId").asString()).isEqualTo(customer);
        assertThat(action.path("paymentId").asString()).isEqualTo(childId);
        assertThat(action.path("parentPaymentId").asString()).isEqualTo(paymentId);
        assertThat(action.path("lineIds").get(0).asString()).isEqualTo("balance");

        // Stripe's payment_failed webhook and a status read are not a decline: the customer can still authenticate.
        webhook(event(id("evt"), "payment_intent.payment_failed", "{\"id\":\"" + childPi + "\",\"object\":"
                + "\"payment_intent\",\"status\":\"requires_payment_method\",\"last_payment_error\":{\"code\":"
                + "\"authentication_required\",\"decline_code\":\"authentication_required\"}}"));
        STRIPE.retrieveIntent(childPi, BALANCE, "requires_payment_method");
        assertThat(ok(send("GET", "/v1/payments/" + childId + "?refresh=true", null, null)).path("status")
                .asString()).isEqualTo("REQUIRES_ACTION");
        problem(send("POST", "/v1/payments/" + paymentId + "/off-session-charges", Map.of("customerId", customer),
                "os-again-" + booking), 409, "payments.lines-already-charged");

        // The customer confirms on-session (3-D Secure) → authorised, ready to capture.
        webhook(event(id("evt"), "payment_intent.amount_capturable_updated", StripeStubs.intent(childPi, BALANCE,
                "requires_capture", null, 0)));
        assertThat(ok(send("GET", "/v1/payments/" + childId, null, null)).path("status").asString())
                .isEqualTo("AUTHORIZED");
        assertThat(outbox(PaymentsEvents.INTENT_SUCCEEDED, booking)).as("parent + off-session charge").isEqualTo(2);
    }

    @Test
    void lateProviderCancellationFeeIsDebitedFromTheConnectedAccount() {
        String provider = org("org_p");
        String account = account(provider);
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + provider, Map.of("stripeAccountId", account), null));
        String order = id("ORD");
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", provider, "services", "SELF", 1, 10_000,
                0, 0)));
        STRIPE.chargeFees("ch_" + order, new long[] {10_000, 320});
        capture(payment.path("id").asString(), order, "S1", List.of(), 10_000, 320, true);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of(), null));
        long net = 10_000 - 1000 - 320;
        STRIPE.transfer(order, account, "tr_" + order, net);
        TestSupport.CLOCK.advance(Duration.ofDays(7).plusMinutes(5));
        settlements.releaseMatured(500);

        String booking = id("BK");
        String bookingId = UUID.randomUUID().toString();
        String debit = "py_" + provider.replace("-", "");
        STRIPE.accountDebit(account, debit, 1500);
        ProviderFeeNotice notice = new ProviderFeeNotice(UUID.randomUUID(), bookingId, booking, provider, 1500, "USD",
                Instant.now());
        assertThat(providerFees.lateCancellation(notice)).isTrue();
        assertThat(providerFees.lateCancellation(notice)).as("redelivery").isFalse();
        assertThat(providerFees.lateCancellation(new ProviderFeeNotice(UUID.randomUUID(), bookingId, booking,
                provider, 1500, "USD", Instant.now()))).as("one fee per booking").isFalse();

        LoggedRequest charge = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/charges"))
                .withRequestBody(WireMock.containing("source=" + account))).getFirst();
        assertThat(charge.getBodyAsString()).contains("amount=1500", "currency=usd");
        assertThat(charge.getHeader("Idempotency-Key")).startsWith("provider-fee:").endsWith(":0");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/charges"))
                .withRequestBody(WireMock.containing("source=" + account)));
        assertEveryJournalBalances();
        assertThat(balance(booking, "4300", "")).isEqualTo(1500);
        assertThat(balance(booking, "1200", provider)).isZero();
        assertThat(balance(booking, "1000", "")).isEqualTo(-1500);
        assertThat(jdbc.sql("select stripe_ref from journal_entry where order_id = :o and type = "
                + "'PROVIDER_FEE_COLLECTED'").param("o", booking).query(String.class).single()).isEqualTo(debit);
        assertThat(outbox(PaymentsEvents.PROVIDER_FEE_RECORDED, provider)).isEqualTo(1);
        assertThat(outbox(PaymentsEvents.PROVIDER_FEE_COLLECTED, provider)).isEqualTo(1);

        TestSupport.as(TestSupport.seller(provider, "provider.owner", "payouts:request"));
        JsonNode balance = ok(send("GET", "/v1/payouts/balance", null, null));
        assertThat(balance.path("availableMinor").asLong()).isEqualTo(net - 1500);
        assertThat(balance.path("providerFeesMinor").asLong()).isEqualTo(1500);
        JsonNode fees = ok(send("GET", "/v1/payouts/provider-fees", null, null));
        assertThat(fees.size()).isEqualTo(1);
        assertThat(fees.get(0).path("status").asString()).isEqualTo("COLLECTED");
        assertThat(fees.get(0).path("stripeDebitId").asString()).isEqualTo(debit);
    }

    @Test
    void feeWithoutAConnectedAccountStaysOutstandingAndIsCollectedLater() {
        String provider = org("org_p");
        String booking = id("BK");
        assertThat(providerFees.lateCancellation(new ProviderFeeNotice(UUID.randomUUID(), UUID.randomUUID()
                .toString(), booking, provider, 1500, "USD", null))).isTrue();
        String status = jdbc.sql("select status from provider_fee where org_id = :o").param("o", provider)
                .query(String.class).single();
        assertThat(status).isEqualTo("OUTSTANDING");
        assertThat(balance(booking, "1200", provider)).as("receivable accrued").isEqualTo(-1500);

        String account = account(provider);
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + provider, Map.of("stripeAccountId", account), null));
        STRIPE.accountDebit(account, "py_late_" + provider.replace("-", ""), 1500);
        TestSupport.CLOCK.advance(Duration.ofHours(1));
        for (int i = 0; i < 20 && "OUTSTANDING".equals(status); i++) {
            providerFees.collectOutstanding(50);
            status = jdbc.sql("select status from provider_fee where org_id = :o").param("o", provider)
                    .query(String.class).single();
        }
        assertThat(status).isEqualTo("COLLECTED");
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/charges"))
                .withRequestBody(WireMock.containing("source=" + account))).getFirst().getHeader("Idempotency-Key"))
                .endsWith(":1");
        assertThat(balance(booking, "1200", provider)).isZero();
        assertEveryJournalBalances();
    }

    @Test
    void payoutBalanceShowsTheComplianceHold() {
        String provider = org("org_s");
        TestSupport.as(TestSupport.seller(provider, "seller.owner", "payouts:request"));
        JsonNode before = ok(send("GET", "/v1/payouts/balance", null, null));
        assertThat(before.path("payoutsPaused").asBoolean()).isFalse();
        assertThat(before.path("payoutsPausedReason").isNull()).isTrue();

        Instant t0 = TestSupport.CLOCK.instant();
        enforcement.payoutsPaused(new EnforcementNotice(UUID.randomUUID(), provider, account(provider),
                "AUTO_PAST_DUE", null, null, t0));
        JsonNode paused = ok(send("GET", "/v1/payouts/balance", null, null));
        assertThat(paused.path("payoutsPaused").asBoolean()).isTrue();
        assertThat(paused.path("payoutsPausedReason").asString()).isEqualTo("AUTO_PAST_DUE");

        enforcement.payoutsResumed(new EnforcementNotice(UUID.randomUUID(), provider, account(provider), "CLEARED",
                null, null, t0.plusSeconds(60)));
        JsonNode resumed = ok(send("GET", "/v1/payouts/balance", null, null));
        assertThat(resumed.path("payoutsPaused").asBoolean()).isFalse();
        assertThat(resumed.path("payoutsPausedReason").isNull()).isTrue();
    }

    @Test
    void servicesReadEffectiveFeesFromTheSchedule() {
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode active = ok(send("GET", "/v1/fee-schedules/active", null, null));
        JsonNode fees = ok(send("GET", "/v1/fee-schedules/effective?category=services&orgId=org_any_provider", null,
                null));
        assertThat(fees.path("version").asInt()).isEqualTo(active.path("version").asInt());
        int expected = active.path("categoryBps").has("services") ? active.path("categoryBps").path("services").asInt()
                : active.path("defaultCommissionBps").asInt();
        assertThat(fees.path("commissionBps").asInt()).isEqualTo(expected);
        assertThat(fees.path("cardPctBps").asInt()).isEqualTo(active.path("cardPctBps").asInt());
        assertThat(fees.path("cardFixedMinor").asLong()).isEqualTo(active.path("cardFixedMinor").asLong());
        assertThat(fees.path("currency").asString()).isEqualTo("USD");
        JsonNode retail = ok(send("GET", "/v1/fee-schedules/effective?category=services&orgId=" + RETAIL, null,
                null));
        assertThat(retail.path("commissionBps").asInt()).isZero();
        assertThat(retail.path("commissionSource").asString()).isEqualTo("RETAIL");
    }
}
