package com.afrotransact.orders.adapter.out.module;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.payments.api.OrderPaymentsApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentsClient} through payments' published {@link OrderPaymentsApi} inside this JVM (mode {@code in-process}),
 * entered as orders' caller principal (scope {@code payments:service}). Same idempotency keys as the HTTP client
 * ({@code <orderId>} on create, {@code cancel-<paymentId>}, {@code capture-<shipmentId>}); failures are the same
 * {@code UpstreamFailure}s (a decline is 402, an already-captured payment 409, a missing payment 404).
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "payments", value = ModuleMode.IN_PROCESS)
class InProcessPaymentsClient implements PaymentsClient {

    private static final String SERVICE = "payments";

    private final ObjectProvider<OrderPaymentsApi> api;
    private final ModuleUpstream upstream;

    InProcessPaymentsClient(ObjectProvider<OrderPaymentsApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(OrderPaymentsApi.class, true, false).length > 0, "orders",
                "payments", "at.orders.payments.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Intent createIntent(NewIntent r) {
        Map<String, String> metadata = new LinkedHashMap<>(r.metadata() == null ? Map.of()
                : r.metadata());
        // Captures follow the order's shipments (see the HTTP client).
        metadata.putIfAbsent("capture_plan", "per_shipment");
        OrderPaymentsApi.NewPayment request = new OrderPaymentsApi.NewPayment(r.orderId(), r.amountMinor(), r.currency(),
                r.placedAt(), metadata, r.lines().stream().map(l -> new OrderPaymentsApi.Line(l.lineId(), l.sellerOrg(),
                l.category(), l.fulfilment(), l.quantity(), l.itemsMinor(), l.shippingMinor(), l.taxMinor(),
                l.taxJurisdiction(), l.shippingTaxMinor(), l.deliveryFeeMinor())).toList(),
                r.shopperId() == null || r.shopperId().isBlank() ? null : r.shopperId(), r.paymentMethodTypes(),
                r.offSession(), r.saveForOffSession(), r.useCredit(), r.membershipWaivers().stream()
                .map(w -> new OrderPaymentsApi.Waiver(w.ref(), w.benefit(), w.amountMinor(), w.tierCode())).toList());
        return intent(upstream.call(SERVICE, () -> api.getObject().create(request, r.orderId())));
    }

    @Override
    public DeliveryQuote quoteDelivery(String sellerOrgId, double distanceMiles, int packages) {
        OrderPaymentsApi.DeliveryQuoteInfo q = upstream.call(SERVICE,
                () -> api.getObject().quoteDelivery(sellerOrgId, distanceMiles, packages));
        return new DeliveryQuote(q.available(), q.unavailableReason(), q.customerChargeMinor(), q.sellerFeeMinor(),
                q.scheduleVersion());
    }

    @Override
    public Intent get(String paymentId, boolean refresh) {
        return intent(upstream.call(SERVICE, () -> api.getObject().get(paymentId, refresh)));
    }

    @Override
    public Optional<Intent> findByOrder(String orderId) {
        return upstream.call(SERVICE, () -> api.getObject().findByOrder(orderId)).map(InProcessPaymentsClient::intent);
    }

    @Override
    public void cancel(String paymentId, String reason) {
        upstream.run(SERVICE, () -> api.getObject().cancel(paymentId, reason == null ? "" : truncate(reason),
                "cancel-" + paymentId));
    }

    @Override
    public Capture capture(String paymentId, CaptureRequest r) {
        OrderPaymentsApi.CaptureInfo c = upstream.call(SERVICE, () -> api.getObject().capture(paymentId,
                new OrderPaymentsApi.CaptureRequest(r.shipmentId(), r.lineIds(), r.amountMinor(), r.finalCapture()),
                "capture-" + r.shipmentId()));
        return new Capture(c.captureId(), c.amountMinor(), c.lineIds());
    }

    @Override
    public String refund(String paymentId, String idempotencyKey, List<RefundLine> lines, String reason) {
        return upstream.call(SERVICE, () -> api.getObject().refund(paymentId, idempotencyKey, lines.stream()
                .map(l -> new OrderPaymentsApi.RefundLine(l.lineId(), l.itemsMinor(), l.shippingMinor(), l.taxMinor()))
                .toList(), truncate(reason)));
    }

    @Override
    public void delivered(String orderId, Instant deliveredAt) {
        upstream.run(SERVICE, () -> api.getObject().delivered(orderId, deliveredAt));
    }

    @Override
    public void shipmentDelivered(String orderId, String shipmentId, Instant deliveredAt) {
        upstream.run(SERVICE, () -> api.getObject().shipmentDelivered(orderId, shipmentId, deliveredAt));
    }

    @Override
    public Split split(String orderId) {
        OrderPaymentsApi.SplitInfo s = upstream.call(SERVICE, () -> api.getObject().split(orderId));
        return new Split(orderId, s.chargeMinor(), s.stripeFeeMinor(), s.taxCollectedMinor(), s.currency(),
                s.recipients().stream().map(r -> new Recipient(r.recipient(), r.kind(), r.itemsMinor(), r.shippingMinor(),
                        r.taxMinor(), r.commissionMinor(), r.fbaFeesMinor(), r.deliveryFeesMinor(), r.stripeFeeMinor(),
                        r.netMinor())).toList());
    }

    private static Intent intent(OrderPaymentsApi.PaymentInfo p) {
        return new Intent(p.paymentId(), p.status(), p.clientSecret(), p.amountMinor(), p.failureMessage(),
                p.creditMinor());
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
