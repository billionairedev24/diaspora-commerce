package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.catalog.api.ProductListingApi;
import com.afrotransact.content.api.MarketEligibilityApi;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.adapter.out.module.InProcessWiring;
import com.afrotransact.orders.application.InProcessCallees.Offers;
import com.afrotransact.orders.application.InProcessCallees.Payments;
import com.afrotransact.orders.application.InProcessCallees.Placement;
import com.afrotransact.orders.application.InProcessCallees.Stock;
import com.afrotransact.orders.domain.DefaultShippingPolicy;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.ShippingSettingsFixtures;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.DeliveryFeeRules;
import com.afrotransact.orders.port.DeliveryRunSlots;
import com.afrotransact.orders.port.OrderCouponRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.Page;
import com.afrotransact.orders.port.PickupPoints;
import com.afrotransact.orders.port.PreorderEligibility;
import com.afrotransact.orders.port.PromiseClient;
import com.afrotransact.orders.port.ShipFromAddresses;
import com.afrotransact.payments.api.MembershipBenefitsApi;
import com.afrotransact.taxreturns.api.TaxQuoteApi;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The checkout saga in the shape release 1 runs it: the real {@link CheckoutWorkflowImpl} and the real
 * {@link CheckoutActivitiesImpl} on Temporal's in-JVM test environment (no Docker, time skipping), calling inventory, payments,
 * offers, tax-returns, catalog, content and the membership benefits through the REAL in-process adapters of orders
 * ({@code InProcessWiring}) over stateful stand-ins for those modules' published APIs ({@link InProcessCallees}). Orders' own
 * persistence is {@link Placement}. Each test forces ONE failure, through the in-process adapter, and asserts the end state:
 * stock restored, no order left active, PaymentIntent cancelled or never created, coupon released, and the failure code the
 * shopper gets. It is the in-process twin of the scripted {@code CheckoutWorkflowTest}, without scripting the steps.
 *
 * <p>Not covered here (needs Docker or a real server): the called modules' own services, Postgres, Kafka, a real Temporal server,
 * Stripe. This is a saga proof, not a database transaction: modules commit separately by design.
 */
class InProcessCheckoutSagaFailureTest {

    private static final String SKU = "ATIN-1";
    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    private TestWorkflowEnvironment env;
    private WorkflowClient client;

    private Stock stock;
    private Payments payments;
    private Offers offers;
    private Placement placement;
    private InProcessCallees.Faults taxFaults;
    private volatile String taxDecision;
    private DeliveryRunSlots runSlots;
    private OrderCouponRepository orderCoupons;
    private final AtomicInteger couponReleasesRecorded = new AtomicInteger();

    @BeforeEach
    void setUp() {
        stock = new Stock().with(SKU, 10).with("ATIN-2", 1);
        payments = new Payments();
        offers = new Offers();
        offers.inventorySkus.put("offer-1", SKU);
        placement = new Placement();
        taxFaults = new InProcessCallees.Faults();
        taxDecision = "RESOLVED";
        runSlots = mock(DeliveryRunSlots.class);
        orderCoupons = new OrderCouponRepository() {
            private final Map<String, OrderCoupon> byOrder = new ConcurrentHashMap<>();

            @Override
            public void save(OrderCoupon coupon) {
                byOrder.putIfAbsent(coupon.orderId(), coupon);
            }

            @Override
            public Optional<OrderCoupon> find(String orderId) {
                return Optional.ofNullable(byOrder.get(orderId));
            }

            @Override
            public boolean markReleased(String orderId, Instant at) {
                couponReleasesRecorded.incrementAndGet();
                return byOrder.containsKey(orderId);
            }
        };
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(CheckoutWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationFactory(CheckoutWorkflow.class, CheckoutWorkflowImpl::new);
        worker.registerActivitiesImplementations(build(false), (CheckoutTotalActivities) (in, draft) -> { });
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private CheckoutActivitiesImpl build(boolean runs) {
        OrdersSettings settings = new OrdersSettings("org-retail", Duration.ofMinutes(15), Duration.ofSeconds(5),
                Duration.ofHours(1), Set.of(), Map.of(), 10, 3, "America/Chicago");
        ProductListingApi catalog = atin -> Optional.of(new ProductListingApi.LiveListing(atin, "P-" + atin, "Item " + atin,
                null, "org-seller", "SELLER", "pantry", Map.of(), 1000, "USD", Map.of()));
        MarketEligibilityApi content = (country, state, zip) -> new MarketEligibilityApi.Eligibility(true, null, null);
        TaxQuoteApi tax = request -> {
            taxFaults.enter("quote");
            return new TaxQuoteApi.Quote("RULES", request.lines().stream().map(l -> new TaxQuoteApi.LineTax(l.lineId(),
                    Math.round((l.unitPriceMinor() * l.quantity() - l.discountMinor() + l.shippingMinor()) * 0.0825), 0,
                    "US-TX", null, taxDecision, "TAXABLE", "AT.PANTRY.TAXABLE", "txcd_1", "tx-1", null, "ZIP")).toList());
        };
        MembershipBenefitsApi benefits = new MembershipBenefitsApi() {
            @Override
            public int earlyAccessHours(String customerId) {
                return 0;
            }

            @Override
            public WaiverQuoteInfo quote(String customerId, long itemsMinor, String currency, List<Fee> fees) {
                return new WaiverQuoteInfo(false, null, null, List.of());
            }
        };
        CartClient cart = mock(CartClient.class);
        PromiseClient promises = mock(PromiseClient.class);
        when(promises.days(any(), any())).thenReturn(Map.of());
        PreorderEligibility preorders = mock(PreorderEligibility.class);
        when(preorders.eligibility(any(), any(), any(), any(), anyInt())).thenReturn(Map.of());
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.byCustomer(anyString(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0, 5, 0));
        CheckoutTax checkoutTax = new CheckoutTaxImpl(InProcessWiring.tax(tax), new TaxSettings(null),
                ShipFromAddresses.NONE);
        return new CheckoutActivitiesImpl(InProcessWiring.markets(content),
                InProcessWiring.productFacts(catalog, settings), promises, checkoutTax,
                new DefaultShippingPolicy(() -> ShippingSettingsFixtures.of(599, null, true),
                        DefaultShippingPolicy.Rates.defaults()), mock(HandoverPolicy.class), null,
                InProcessWiring.inventory(stock), InProcessWiring.offerInventory(offers),
                InProcessWiring.payments(payments), cart, InProcessWiring.coupons(offers), orderCoupons, orders, placement,
                mock(LocalDeliveryQuotes.class), mock(PickupPoints.class), preorders, runSlots,
                mock(DeliveryFeeRules.class), InProcessWiring.benefits(benefits),
                new PreorderSettings(runs, runs, 100, 24, null, null), settings, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A two-unit order of SKU-1 (FBA stock, so inventory holds it), optionally with a coupon. */
    private static CheckoutInput input(String orderId, String coupon, String sku, int qty) {
        CartClient.Item item = new CartClient.Item(sku, "P-" + sku, "Item " + sku, null, "org-seller", "Seller", "SELLER",
                "FBA", 1000, "USD", qty, false, SKU.equals(sku) ? "offer-1" : null);
        return new CheckoutInput(orderId, "AT-2026-TESTAB", "customer-1", "key-1", "checkout-" + orderId, "USD",
                new ShipTo("addr-1", "Ada", "+17135550142", "1 Main St", null, "Houston", "TX", "77002", "US", null),
                List.of(item), List.of(), 900, Instant.now(), coupon);
    }

    private CheckoutWorkflow start(CheckoutInput input) {
        CheckoutWorkflow wf = client.newWorkflowStub(CheckoutWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(input.workflowId()).setTaskQueue(CheckoutWorkflow.TASK_QUEUE).build());
        WorkflowClient.start(wf::checkout, input);
        return wf;
    }

    private static void awaitPhase(CheckoutWorkflow wf, CheckoutProgress.Phase phase) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            CheckoutProgress p = wf.progress();
            if (p != null && p.phase() == phase) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("workflow never reached " + phase + " (now " + wf.progress() + ")");
    }

    private CheckoutProgress result(CheckoutInput in) {
        return client.newUntypedWorkflowStub(in.workflowId()).getResult(CheckoutProgress.class);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static ModuleApiException refusal(int status, String code) {
        return new ModuleApiException(status, code, "refused: " + code);
    }

    // ------------------------------------------------------------------ control: the happy path through the same wiring

    @Test
    void happyPathHoldsStockCreatesTheIntentAndPlacesTheOrderEntirelyAsOrdersServiceCaller() {
        CheckoutInput in = input(id(), "WELCOME10", SKU, 2);
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        assertThat(stock.available(SKU)).isEqualTo(8);
        assertThat(payments.status(in.orderId())).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(placement.status.get(in.orderId())).isEqualTo("PENDING_PAYMENT");

        wf.paymentAuthorized();
        assertThat(result(in).phase()).isEqualTo(CheckoutProgress.Phase.PLACED);

        assertThat(stock.status(in.orderId())).isEqualTo("CONFIRMED");
        assertThat(stock.available(SKU)).isEqualTo(8);
        assertThat(placement.status.get(in.orderId())).isEqualTo("PLACED");
        assertThat(offers.redemptionStatus(in.orderId())).isEqualTo("REDEEMED");
        assertThat(stock.faults.principals).isNotEmpty().containsOnly("orders-service");
        assertThat(payments.faults.principals).isNotEmpty().containsOnly("orders-service");
        assertThat(offers.faults.principals).isNotEmpty().containsOnly("orders-service");
    }

    // ------------------------------------------------------------------ inventory reservation fails

    @Test
    void insufficientStockFailsWithTheStockCodeAndTheShortfallAndCreatesNothing() {
        CheckoutInput in = input(id(), null, "ATIN-2", 5);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.phase()).isEqualTo(CheckoutProgress.Phase.FAILED);
        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.INSUFFICIENT_STOCK);
        assertThat(done.details()).containsExactly("ATIN-2: requested 5, available 1");
        assertThat(stock.available("ATIN-2")).isEqualTo(1);
        assertThat(stock.status(in.orderId())).isNull();
        assertThat(stock.faults.calls("release")).isZero();
        assertThat(payments.intents()).isZero();
        assertThat(placement.persistCalls.get()).isZero();
        assertThat(placement.active(in.orderId())).isFalse();
        assertThat(placement.failure.get(in.orderId())).isEqualTo(CheckoutCodes.INSUFFICIENT_STOCK);
    }

    @Test
    void inventoryUnavailableIsRetriedThenFailsAsUpstreamUnavailableWithNothingLeftBehind() {
        stock.faults.failing("reserve", () -> refusal(503, "inventory.upstream-unavailable"));
        CheckoutInput in = input(id(), null, SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(stock.faults.calls("reserve")).isEqualTo(5);
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(stock.faults.calls("release")).isEqualTo(1);
        assertThat(payments.intents()).isZero();
        assertThat(placement.active(in.orderId())).isFalse();
    }

    @Test
    void aCrashInsideInventoryIsRetryableAndNeverLeaksItsMessageToTheShopper() {
        stock.faults.failing("reserve", () -> new IllegalStateException("could not execute statement; password=hunter2"));
        CheckoutInput in = input(id(), null, SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(done.failureMessage()).doesNotContain("hunter2").doesNotContain("statement");
        assertThat(stock.faults.calls("reserve")).isEqualTo(5);
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(payments.intents()).isZero();
    }

    @Test
    void aTransientInventoryFailureIsRetriedAndTheCheckoutGoesOn() {
        stock.faults.failing("reserve", () -> refusal(503, "inventory.upstream-unavailable"), 2);
        CheckoutInput in = input(id(), null, SKU, 2);
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);

        assertThat(stock.faults.calls("reserve")).isEqualTo(3);
        assertThat(stock.available(SKU)).isEqualTo(8);
        assertThat(payments.intents()).isEqualTo(1);
    }

    @Test
    void anInventoryRuleViolationIsRejectedWithoutRetryAndWithoutAPayment() {
        stock.faults.failing("reserve", () -> refusal(422, "inventory.sku-inactive"));
        CheckoutInput in = input(id(), null, SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_REJECTED);
        assertThat(stock.faults.calls("reserve")).isEqualTo(1);
        assertThat(payments.intents()).isZero();
        assertThat(placement.active(in.orderId())).isFalse();
    }

    // ------------------------------------------------------------------ tax quote unavailable (after the stock is held)

    @Test
    void taxUnavailableAfterTheStockWasHeldGivesTheStockBack() {
        taxFaults.failing("quote", () -> refusal(503, "tax-returns.provider-unavailable"));
        CheckoutInput in = input(id(), null, SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(taxFaults.calls("quote")).isEqualTo(5);
        assertThat(stock.status(in.orderId())).isEqualTo("RELEASED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(payments.intents()).isZero();
        assertThat(placement.active(in.orderId())).isFalse();
    }

    @Test
    void aLineWithoutADefiniteTaxDecisionStopsTheCheckoutAndGivesTheStockBack() {
        taxDecision = "AWAITING_APPROVAL";
        CheckoutInput in = input(id(), null, SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.TAX_LINE_BLOCKED);
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(payments.intents()).isZero();
        assertThat(placement.active(in.orderId())).isFalse();
    }

    // ------------------------------------------------------------------ payment intent creation fails

    @Test
    void aDeclinedIntentIsRejectedGivesTheStockAndTheCouponBackAndCreatesNoIntent() {
        payments.faults.failing("create", () -> refusal(402, "payments.card-declined"));
        CheckoutInput in = input(id(), "WELCOME10", SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_REJECTED);
        assertThat(payments.faults.calls("create")).isEqualTo(1);
        assertThat(payments.intents()).isZero();
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(stock.status(in.orderId())).isEqualTo("RELEASED");
        assertThat(offers.activeRedemptions()).isEmpty();
        assertThat(placement.active(in.orderId())).isFalse();
    }

    @Test
    void paymentsUnavailableIsRetriedThenFailsWithTheStockAndCouponGivenBack() {
        payments.faults.failing("create", () -> refusal(502, "payments.stripe-unavailable"));
        CheckoutInput in = input(id(), "WELCOME10", SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(payments.faults.calls("create")).isEqualTo(5);
        assertThat(payments.faults.calls("findByOrder")).isEqualTo(1);
        assertThat(payments.intents()).isZero();
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(offers.activeRedemptions()).isEmpty();
        assertThat(placement.active(in.orderId())).isFalse();
    }

    @Test
    void anIntentCreatedWhoseAnswerWasLostIsFoundAgainByTheRetryAndNotDuplicated() {
        payments.failAfterCreate = () -> refusal(502, "payments.stripe-unavailable");
        CheckoutInput in = input(id(), null, SKU, 2);
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);

        assertThat(payments.intents()).isEqualTo(1);
        assertThat(payments.faults.calls("create")).isEqualTo(2);
    }

    @Test
    void anIntentCreatedWhoseAnswerIsAlwaysLostIsCancelledByTheCompensation() {
        payments.failAfterCreate = () -> refusal(502, "payments.stripe-unavailable");
        payments.failAfterCreateAlways = true;
        CheckoutInput in = input(id(), null, SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(payments.intents()).isEqualTo(1);
        assertThat(payments.status(in.orderId())).isEqualTo("CANCELED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(placement.active(in.orderId())).isFalse();
    }

    // ------------------------------------------------------------------ order creation fails after reservation and payment

    @Test
    void failingToStoreTheOrderCancelsTheIntentReleasesTheStockAndTheCoupon() {
        placement.failPersist = () -> new IllegalStateException("connection to the orders schema was lost");
        CheckoutInput in = input(id(), "WELCOME10", SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(placement.persistCalls.get()).isEqualTo(5);
        assertThat(payments.status(in.orderId())).isEqualTo("CANCELED");
        assertThat(stock.status(in.orderId())).isEqualTo("RELEASED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(offers.redemptionStatus(in.orderId())).isNotEqualTo("REDEEMED");
        assertThat(placement.active(in.orderId())).isFalse();
        assertThat(placement.abandonCalls.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ coupon redemption fails

    @Test
    void aCouponThatNoLongerAppliesIsRefusedAndTheStockIsGivenBack() {
        offers.faults.failing("redeem", () -> refusal(422, "offers.coupon-not-applicable"));
        CheckoutInput in = input(id(), "WELCOME10", SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.COUPON_NOT_APPLICABLE);
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(stock.status(in.orderId())).isEqualTo("RELEASED");
        assertThat(offers.activeRedemptions()).isEmpty();
        assertThat(offers.faults.calls("release")).isZero();
        assertThat(payments.intents()).isZero();
        assertThat(placement.active(in.orderId())).isFalse();
    }

    @Test
    void offersUnavailableWhileRedeemingLeavesNoRedemptionAndNoStockHeld() {
        offers.faults.failing("redeem", () -> refusal(503, "offers.unavailable"), 5);
        CheckoutInput in = input(id(), "WELCOME10", SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.UPSTREAM_UNAVAILABLE);
        assertThat(offers.activeRedemptions()).isEmpty();
        assertThat(offers.redemptionStatus(in.orderId())).isEqualTo("RELEASED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(payments.intents()).isZero();
        assertThat(placement.active(in.orderId())).isFalse();
    }

    // ------------------------------------------------------------------ payment fails or never comes, then the intent is cancelled

    @Test
    void aFailedPaymentCancelsTheIntentReleasesStockAndCouponAndVoidsTheOrder() {
        CheckoutInput in = input(id(), "WELCOME10", SKU, 2);
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentFailed("Your card was declined");
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.PAYMENT_FAILED);
        assertThat(done.failureMessage()).isEqualTo("Your card was declined");
        assertThat(payments.status(in.orderId())).isEqualTo("CANCELED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(offers.redemptionStatus(in.orderId())).isEqualTo("RELEASED");
        assertThat(couponReleasesRecorded.get()).isEqualTo(1);
        assertThat(placement.status.get(in.orderId())).isEqualTo("CANCELLED");
        assertThat(placement.failure.get(in.orderId())).isEqualTo(CheckoutCodes.PAYMENT_FAILED);
    }

    @Test
    void noPaymentWithinTheTimeoutRunsEveryCompensation() {
        CheckoutInput in = input(id(), "WELCOME10", SKU, 2);
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.PAYMENT_TIMEOUT);
        assertThat(payments.status(in.orderId())).isEqualTo("CANCELED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(offers.redemptionStatus(in.orderId())).isEqualTo("RELEASED");
        assertThat(placement.status.get(in.orderId())).isEqualTo("CANCELLED");
    }

    @Test
    void anAuthorisationThatCannotBeCancelledBecauseItIsAlreadyCapturedDoesNotStopTheOtherCompensations() {
        payments.faults.failing("cancel", () -> refusal(409, "payments.invalid-state"));
        CheckoutInput in = input(id(), null, SKU, 2);
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentFailed("declined");
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.PAYMENT_FAILED);
        assertThat(payments.faults.calls("cancel")).isEqualTo(1);
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(placement.status.get(in.orderId())).isEqualTo("CANCELLED");
    }

    @Test
    void compensationsAreRetriedUntilTheyWork() {
        payments.faults.failing("cancel", () -> refusal(503, "payments.unavailable"), 2);
        stock.faults.failing("release", () -> new IllegalStateException("pool exhausted"), 3);
        CheckoutInput in = input(id(), null, SKU, 2);
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        wf.paymentFailed("declined");
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.PAYMENT_FAILED);
        assertThat(payments.faults.calls("cancel")).isEqualTo(3);
        assertThat(stock.faults.calls("release")).isEqualTo(4);
        assertThat(payments.status(in.orderId())).isEqualTo("CANCELED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(placement.status.get(in.orderId())).isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------ authorised, but the hold expired before it could be confirmed

    @Test
    void anExpiredHoldAtPlacementCancelsTheAuthorisationAndFailsWithReservationExpired() {
        CheckoutInput in = input(id(), null, SKU, 2);
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        stock.expire(in.orderId());
        wf.paymentAuthorized();
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.RESERVATION_EXPIRED);
        assertThat(payments.status(in.orderId())).isEqualTo("CANCELED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(placement.active(in.orderId())).isFalse();
    }

    // ------------------------------------------------------------------ delivery-run slot (delivery-runs stays HTTP in core)

    private CheckoutInput runInput(String orderId) {
        CartClient.Item item = new CartClient.Item(SKU, "P-" + SKU, "Item " + SKU, null, "org-retail", "Retail", "PLATFORM",
                "FBA", 1000, "USD", 2, false, "offer-1");
        CheckoutInput base = input(orderId, null, SKU, 2);
        return new CheckoutInput(base.orderId(), base.orderNumber(), base.customerId(), base.idempotencyKey(),
                base.workflowId(), base.currency(), base.shipTo(), List.of(item), List.of(), 900, Instant.now(), null);
    }

    private void withRuns() {
        env.close();
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(CheckoutWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationFactory(CheckoutWorkflow.class, CheckoutWorkflowImpl::new);
        worker.registerActivitiesImplementations(build(true), (CheckoutTotalActivities) (in, draft) -> { });
        env.start();
        client = env.getWorkflowClient();
        when(runSlots.openRuns(anyString(), anyInt())).thenReturn(List.of(new DeliveryRunSlots.OpenRun("run-1", "Bastrop",
                NOW.plus(Duration.ofDays(2)), LocalDate.of(2026, 11, 20), "America/Chicago", 499, "USD", true, false, null,
                null, 3, 0, false)));
    }

    @Test
    void aFullRunFailsBeforeAnyPaymentAndGivesTheStockBackWithNoSlotToRelease() {
        withRuns();
        doThrow(new DeliveryRunSlots.RunUnavailable(CheckoutCodes.RUN_FULL, "The run is full"))
                .when(runSlots).reserve(anyString(), anyString(), anyString(), anyInt(), any());
        CheckoutInput in = runInput(id());
        start(in);
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.RUN_FULL);
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(payments.intents()).isZero();
        assertThat(placement.active(in.orderId())).isFalse();
        verify(runSlots, never()).release(anyString(), anyString());
    }

    @Test
    void anOrderOnARunGivesTheSlotBackWhenPaymentFails() {
        withRuns();
        CheckoutInput in = runInput(id());
        CheckoutWorkflow wf = start(in);
        awaitPhase(wf, CheckoutProgress.Phase.AWAITING_PAYMENT);
        verify(runSlots).reserve(anyString(), anyString(), anyString(), anyInt(), any());
        wf.paymentFailed("declined");
        CheckoutProgress done = result(in);

        assertThat(done.failureCode()).isEqualTo(CheckoutCodes.PAYMENT_FAILED);
        verify(runSlots).release("run-1", in.orderId());
        assertThat(payments.status(in.orderId())).isEqualTo("CANCELED");
        assertThat(stock.available(SKU)).isEqualTo(10);
        assertThat(placement.status.get(in.orderId())).isEqualTo("CANCELLED");
    }
}
