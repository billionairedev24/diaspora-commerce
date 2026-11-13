package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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

import com.afrotransact.orders.domain.CourierProofPolicy;
import com.afrotransact.orders.domain.DefaultShipmentTransitionPolicy;
import com.afrotransact.orders.domain.DeliveryQuote;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.MissedDeliveryChoice;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.ReturnWindowPolicy;
import com.afrotransact.orders.domain.ShipByPolicy;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.port.CheckoutRequestRepository;
import com.afrotransact.orders.port.CheckoutRequestRepository.CheckoutRequest;
import com.afrotransact.orders.port.DeliveryQuoteRepository;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.HandoverCodeVault;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.Page;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.SellerAnalyticsRepository;
import com.afrotransact.orders.port.ShipmentFeed;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.tenant.CrossTenant;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Tenant isolation of the orders use cases with the production RBAC-only checkers (OpenFGA is not deployed): tenant A's
 * token against tenant B's order, shipment, queue, analytics, quotes and checkout requests.
 */
class OrdersTenantIsolationTest {

    private static final String SELLER_PERMS_READ = "orders:read";
    private static final String SELLER_PERMS_STATUS = "orders:update-status";

    private final TenantFixture tenants = new TenantFixture();
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentsClient payments = mock(PaymentsClient.class);
    private final SellerAnalyticsRepository analytics = mock(SellerAnalyticsRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
    private final OrdersSettings settings = new OrdersSettings("retail", Duration.ofMinutes(15),
            Duration.ofMillis(10), Duration.ofHours(1), Set.of(), Map.of(), 10, 3, "UTC");

    private OrdersAccess access;
    private OrderViews views;
    private OrderQueryServiceImpl queries;
    private ShipmentServiceImpl shipments;

    private Shipment shipmentA;
    private Shipment shipmentB;
    private Shipment shipmentB2;
    private Order orderAB;
    private Order orderB;

    @BeforeEach
    void setUp() {
        access = new OrdersAccess(tenants.permissions(), tenants.access(), tenants.resolver());
        ReturnWindowPolicy windows = mock(ReturnWindowPolicy.class);
        when(windows.windowEndsAt(any())).thenReturn(Optional.empty());
        views = new OrderViews(new DefaultShipmentTransitionPolicy(), mock(HandoverCodeVault.class), windows, access,
                settings, clock);
        queries = new OrderQueryServiceImpl(access, orders, views, payments);
        ShipmentTransitions transitions = new ShipmentTransitions(orders, mock(FollowUpRepository.class),
                new DefaultShipmentTransitionPolicy(), mock(DomainEventPublisher.class), mock(ShipmentFeed.class),
                clock);
        shipments = new ShipmentServiceImpl(access, orders, transitions,
                mock(CourierProofPolicy.class),
                mock(HandoverPolicy.class), views, settings, clock);

        String orderAbId = UUID.randomUUID().toString();
        String orderBId = UUID.randomUUID().toString();
        shipmentA = shipment(orderAbId, 1, TenantFixture.ORG_A, "L-A");
        shipmentB = shipment(orderAbId, 2, TenantFixture.ORG_B, "L-B");
        shipmentB2 = shipment(orderBId, 1, TenantFixture.ORG_B, "L-B2");
        orderAB = order(orderAbId, "AT-2026-AAAAAA", TenantFixture.CUSTOMER_1, shipmentA, shipmentB);
        orderB = order(orderBId, "AT-2026-BBBBBB", TenantFixture.CUSTOMER_2, shipmentB2);
        for (Order o : List.of(orderAB, orderB)) {
            when(orders.findByNumber(o.number())).thenReturn(Optional.of(o));
            when(orders.findById(o.id())).thenReturn(Optional.of(o));
            when(orders.lockById(o.id())).thenReturn(Optional.of(o));
            when(orders.history(o.id())).thenReturn(List.of());
            for (Shipment s : o.shipments()) {
                when(orders.orderIdOfShipment(s.id())).thenReturn(Optional.of(o.id()));
            }
        }
    }

    // ----------------------------------------------------------------- GET /v1/orders/{number}

    @Test
    void customerTwoCannotReadCustomerOnesOrderByNumberOrId() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));

        CrossTenant.assertDenied(() -> queries.get(orderAB.number()));
        CrossTenant.assertDenied(() -> queries.get(orderAB.id()));
    }

    @Test
    void sellerAcannotReadAnOrderThatHasOnlySellerBShipments() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));

        CrossTenant.assertDenied(() -> queries.get(orderB.number()));
        CrossTenant.assertDenied(() -> queries.get(orderB.id()));
    }

    @Test
    void sellerAseesOnlyItsOwnShipmentOfASharedOrderWithoutTheCustomerIdOrB() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));

        OrderView view = queries.get(orderAB.number());

        assertThat(view.shipments()).extracting(OrderView.ShipmentView::sellerOrgId)
                .containsExactly(TenantFixture.ORG_A);
        assertThat(view.customerId()).isNull();
        assertThat(view.paymentId()).isNull();
    }

    @Test
    void aSellerWithoutTheOrdersReadPermissionIsRefusedEvenOnItsOwnOrder() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A));

        CrossTenant.assertDenied(() -> queries.get(orderAB.number()));
    }

    // ------------------------------------------- POST courier-events, GET delivery-brief (relay only)

    @Test
    void courierEventsAndDeliveryBriefsAreServedOnlyThroughTheFulfilmentRelay() {
        CourierEventCommand event = new CourierEventCommand("e-1", "picked_up", null, null, null, "driver-7");
        tenants.as(new Actor("driver-7", PrincipalType.DRIVER, Optional.empty(), Set.of("platform.driver"),
                Set.of("delivery:operate")));
        CrossTenant.assertDenied(() -> shipments.courierEvent(orderAB.shipments().getFirst().id(), event));
        CrossTenant.assertDenied(() -> shipments.deliveryBrief(orderAB.shipments().getFirst().id()));
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, "delivery:relay", "delivery:operate"));
        CrossTenant.assertDenied(() -> shipments.courierEvent(orderAB.shipments().getFirst().id(), event));
        tenants.as(new Actor("fulfilment-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("delivery:relay")));
        assertThat(access.courier("driver-7")).isEqualTo("driver-7");
        assertThat(access.briefReader().subject()).isEqualTo("fulfilment-service");
    }

    // ----------------------------------------------------------------- GET /v1/orders (mine)

    @Test
    void myOrdersAreQueriedByTheTokenSubjectOnly() {
        when(orders.byCustomer(any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0, 20, 0));
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));

        queries.mine(null, 20);

        verify(orders).byCustomer(eq(TenantFixture.CUSTOMER_2), anyInt(), anyInt());
        verify(orders, never()).byCustomer(eq(TenantFixture.CUSTOMER_1), anyInt(), anyInt());
    }

    @Test
    void sellersAndStaffHaveNoCustomerOrderList() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));
        CrossTenant.assertDenied(() -> queries.mine(null, 20));
        tenants.as(TenantFixture.driver("drv-1", "delivery:operate"));
        CrossTenant.assertDenied(() -> queries.mine(null, 20));
    }

    // ----------------------------------------------------------------- GET /v1/seller/shipments

    @Test
    void sellerQueueIgnoresAClientSuppliedOrgForSellers() {
        when(orders.sellerShipments(any(), any(), anyInt(), anyInt())).thenReturn(new Page<>(List.of(), 0, 20, 0));
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));

        queries.sellerQueue(TenantFixture.ORG_B, null, null, 20);

        ArgumentCaptor<String> org = ArgumentCaptor.forClass(String.class);
        verify(orders).sellerShipments(org.capture(), any(), anyInt(), anyInt());
        assertThat(org.getValue()).isEqualTo(TenantFixture.ORG_A);
    }

    @Test
    void sellerQueueOfASellerWithoutAnOrgClaimOrReadPermissionIsRefused() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1, SELLER_PERMS_READ));
        CrossTenant.assertDenied(() -> queries.sellerQueue(TenantFixture.ORG_B, null, null, 20));
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A));
        CrossTenant.assertDenied(() -> queries.sellerQueue(null, null, null, 20));
        verify(orders, never()).sellerShipments(any(), any(), anyInt(), anyInt());
    }

    @Test
    void staffMustNameTheOrgAndABlankOrgNeverMeansEveryOrg() {
        tenants.as(TenantFixture.staff(SELLER_PERMS_READ));

        CrossTenant.assertDenied(() -> queries.sellerQueue(null, null, null, 20));
        CrossTenant.assertDenied(() -> queries.sellerQueue("  ", null, null, 20));
        verify(orders, never()).sellerShipments(any(), any(), anyInt(), anyInt());
    }

    // ----------------------------------------------------------------- GET /v1/admin/orders

    @Test
    void staffOrderSearchRefusesSellersAndCustomers() {
        OrderSearchQuery everything = new OrderSearchQuery(null, null, TenantFixture.ORG_B, null, null, null, null);
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));
        CrossTenant.assertDenied(() -> queries.search(everything, null, 20));
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1, SELLER_PERMS_READ));
        CrossTenant.assertDenied(() -> queries.search(everything, null, 20));
        verify(orders, never()).search(any(), anyInt(), anyInt());
    }

    // ----------------------------------------------------------------- GET /v1/shipments/stream

    @Test
    void theLiveFeedOfASellerIsScopedToItsOwnOrgAndCustomersGetNone() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));
        assertThat(queries.liveFeedScope()).contains(TenantFixture.ORG_A);

        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        CrossTenant.assertDenied(() -> queries.liveFeedScope());
    }

    // ----------------------------------------------------------------- GET /v1/orders/{number}/payment-split

    @Test
    void paymentSplitIsRefusedToCustomersAndToSellersWithoutAShipmentInTheOrder() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1));
        CrossTenant.assertDenied(() -> queries.paymentSplit(orderAB.number()));

        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));
        CrossTenant.assertDenied(() -> queries.paymentSplit(orderB.number()));
        verify(payments, never()).split(orderB.id());
    }

    @Test
    void sellerAseesOnlyItsOwnSplitRowAndNotTheWholeOrdersCharge() {
        when(payments.split(orderAB.id())).thenReturn(new PaymentsClient.Split(orderAB.id(), 9_999, 300, 200, "USD",
                List.of(recipient(TenantFixture.ORG_A, 1_000, 100, 80), recipient(TenantFixture.ORG_B, 7_000, 700, 500),
                        recipient("afrotransact", 0, 400, 0))));
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));

        PaymentsClient.Split split = queries.paymentSplit(orderAB.number());

        assertThat(split.recipients()).extracting(PaymentsClient.Recipient::recipient)
                .containsExactly(TenantFixture.ORG_A);
        assertThat(split.chargeMinor()).as("co-sellers' amounts must not leak through the order charge")
                .isEqualTo(1_000 + 100 + 80);
    }

    // ----------------------------------------------------------------- shipment transitions and tracking

    @Test
    void sellerAcannotMoveOrTrackSellerBsShipment() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ, SELLER_PERMS_STATUS));

        CrossTenant.assertDenied(() -> shipments.transition(shipmentB.id(), "CONFIRMED", null, null, null));
        CrossTenant.assertDenied(() -> shipments.transition(shipmentB2.id(), "CONFIRMED", null, null, null));
        CrossTenant.assertDenied(() -> shipments.attachTracking(shipmentB.id(), "usps", "9400"));
        verify(orders, never()).update(any());
    }

    @Test
    void aSellerWithoutTheStatusPermissionCannotMoveItsOwnShipment() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));

        CrossTenant.assertDenied(() -> shipments.transition(shipmentA.id(), "CONFIRMED", null, null, null));
        verify(orders, never()).update(any());
    }

    @Test
    void customersCannotUseTheTransitionOrTrackingRoutes() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1, SELLER_PERMS_STATUS));

        CrossTenant.assertDenied(() -> shipments.transition(shipmentA.id(), "CANCELLED", null, null, null));
        CrossTenant.assertDenied(() -> shipments.attachTracking(shipmentA.id(), "usps", "9400"));
    }

    // ----------------------------------------------------------------- customer cancel

    @Test
    void customerTwoCannotCancelACustomerOnesShipment() {
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));

        CrossTenant.assertDenied(() -> shipments.cancelByCustomer(orderAB.number(), shipmentA.id(), null, null));
        verify(orders, never()).update(any());
    }

    @Test
    void aSellerCannotUseTheCustomerCancelRoute() {
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_STATUS));

        CrossTenant.assertDenied(() -> shipments.cancelByCustomer(orderAB.number(), shipmentA.id(), null, null));
    }

    // ----------------------------------------------------------------- seller analytics

    @Test
    void sellerAnalyticsIgnoreAClientSuppliedOrgForSellers() {
        when(analytics.days(any(), any(), any(), any())).thenReturn(List.of());
        when(analytics.topProducts(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(analytics.byFulfilment(any(), any(), any())).thenReturn(List.of());
        SellerAnalyticsServiceImpl service = new SellerAnalyticsServiceImpl(access, analytics, settings, clock);
        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));

        service.analytics(TenantFixture.ORG_B, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 10));

        verify(analytics).days(eq(TenantFixture.ORG_A), any(), any(), any());
        verify(analytics, never()).days(eq(TenantFixture.ORG_B), any(), any(), any());
        verify(analytics, never()).toShip(TenantFixture.ORG_B);
    }

    @Test
    void sellerAnalyticsRefuseCustomersAndBlankStaffOrgs() {
        SellerAnalyticsServiceImpl service = new SellerAnalyticsServiceImpl(access, analytics, settings, clock);
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_1, SELLER_PERMS_READ));
        CrossTenant.assertDenied(() -> service.analytics(TenantFixture.ORG_B, null, null));
        tenants.as(TenantFixture.staff(SELLER_PERMS_READ));
        CrossTenant.assertDenied(() -> service.analytics("", null, null));
        CrossTenant.assertDenied(() -> service.analytics(null, null, null));
        verify(analytics, never()).toShip(any());
    }

    // ----------------------------------------------------------------- checkout status / confirm

    @Test
    void customerTwoCannotReadOrConfirmCustomerOnesCheckout() {
        CheckoutRequestRepository requests = mock(CheckoutRequestRepository.class);
        CheckoutRequest mine = new CheckoutRequest(TenantFixture.CUSTOMER_1, "key", "fp", orderAB.id(),
                orderAB.number(), "wf", "AWAITING_PAYMENT", "pay-1", "secret", null, null, Instant.now());
        when(requests.findByNumber(orderAB.number())).thenReturn(Optional.of(mine));
        CheckoutServiceImpl checkout = new CheckoutServiceImpl(access, requests, orders, null, null, null, payments,
                null, null, null, settings, null, clock);

        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));
        CrossTenant.assertDenied(() -> checkout.status(orderAB.number()));
        CrossTenant.assertDenied(() -> checkout.confirmPayment(orderAB.number()));
        verify(payments, never()).get(any(), eq(true));

        tenants.as(TenantFixture.sellerOf(TenantFixture.ORG_A, SELLER_PERMS_READ));
        CrossTenant.assertDenied(() -> checkout.status(orderAB.number()));
    }

    // ----------------------------------------------------------------- delivery quotes (customer)

    @Test
    void customerTwoCannotReadCustomerOnesDeliveryQuoteAndMyQuotesUseTheSubject() {
        DeliveryQuoteRepository quotes = mock(DeliveryQuoteRepository.class);
        String quoteId = UUID.randomUUID().toString();
        DeliveryQuote quote = mock(DeliveryQuote.class);
        when(quote.customerId()).thenReturn(TenantFixture.CUSTOMER_1);
        when(quotes.findById(quoteId)).thenReturn(Optional.of(quote));
        when(quotes.forCustomer(any(), anyInt())).thenReturn(List.of());
        DeliveryQuoteServiceImpl service = new DeliveryQuoteServiceImpl(access, quotes, null, null, null, null, null,
                mock(TransactionOperations.class), clock);
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));

        CrossTenant.assertDenied(() -> service.get(quoteId));
        service.mine();

        verify(quotes).forCustomer(eq(TenantFixture.CUSTOMER_2), anyInt());
        verify(quotes, never()).forCustomer(eq(TenantFixture.CUSTOMER_1), anyInt());
    }

    // ----------------------------------------------------------------- missed pre-order delivery

    @Test
    void customerTwoCannotAnswerCustomerOnesMissedPreorderDelivery() {
        ShipmentTransitions transitions = mock(ShipmentTransitions.class);
        PreorderDeliveryServiceImpl service = new PreorderDeliveryServiceImpl(access, orders, transitions,
                mock(DomainEventPublisher.class), views, mock(TransactionOperations.class),
                mock(PreorderSettings.class), settings, clock);
        tenants.as(TenantFixture.customer(TenantFixture.CUSTOMER_2));

        CrossTenant.assertDenied(() -> service.answerMissedDelivery(orderAB.number(), shipmentA.id(),
                MissedDeliveryChoice.REFUND));
        verify(orders, never()).update(any());
    }

    // ----------------------------------------------------------------- service-only lookups

    @Test
    void orderLookupAndPurchaseCheckAreClosedToSellersCustomersAndDrivers() {
        OrderLookupServiceImpl lookups = new OrderLookupServiceImpl(orders, access,
                mock(ShipByPolicy.class), mock(ReturnWindowPolicy.class), settings);
        PurchaseCheckServiceImpl purchases = new PurchaseCheckServiceImpl(orders, access);
        List<Actor> outsiders = List.of(
                TenantFixture.sellerOf(TenantFixture.ORG_A, "orders:read", "orders:lookup", "orders:purchase-check"),
                TenantFixture.customer(TenantFixture.CUSTOMER_1, "orders:read", "orders:lookup"),
                TenantFixture.driver("drv-1", "delivery:operate", "orders:lookup"));
        for (Actor outsider : outsiders) {
            tenants.as(outsider);
            CrossTenant.assertDenied(() -> lookups.lookup(orderAB.number()));
            CrossTenant.assertDenied(() -> purchases.check(TenantFixture.CUSTOMER_1, "ATIN-1"));
        }
        verify(orders, never()).deliveredPurchase(any(), any());
    }

    // ----------------------------------------------------------------- fixtures

    private static PaymentsClient.Recipient recipient(String org, long items, long shipping, long tax) {
        return new PaymentsClient.Recipient(org, "SELLER", items, shipping, tax, 0, 0, 0, 0, 0);
    }

    private static Shipment shipment(String orderId, int seq, String org, String lineId) {
        OrderLine line = new OrderLine(lineId, seq, "ATIN-" + seq, "P", "Item " + seq, null, 1, 1500, 124, 0,
                "groceries", false, "US-TX");
        return new Shipment(UUID.randomUUID().toString(), orderId, seq, org, "Seller " + org,
                FulfilmentType.SELLER_FULFILLED, null, DeliveryMethod.CARRIER, "standard", null,
                DeliveryPreferences.defaults(), false, false, null, null, ShipmentStatus.PLACED, 599, null, null,
                LocalDate.now(), LocalDate.now().plusDays(3), null, null, null, null, Instant.now(), List.of(line), 0);
    }

    private static Order order(String id, String number, String customerId, Shipment... shipments) {
        return new Order(id, number, customerId, "USD", OrderStatus.PLACED,
                OrderTotals.ofShipments(List.of(shipments)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Houston", "TX", "77002", "US", null), UUID.randomUUID().toString(),
                "checkout-" + id, null, Instant.now(), Instant.now(), List.of(shipments), 0);
    }
}
