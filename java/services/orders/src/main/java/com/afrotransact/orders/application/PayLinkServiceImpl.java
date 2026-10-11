package com.afrotransact.orders.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrdersStateConflict;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.port.CheckoutRequestRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PayLinkSigner;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves pay-now link tokens ({@link PayLinkSigner}). An unknown order and a wrong token answer the same 404, so the
 * page reveals nothing about orders it has no token for. Paid orders are shown as PAID without a client secret, so the
 * page can never take a second payment; expired or voided ones are 410.
 */
@Service
class PayLinkServiceImpl implements PayLinkService {

    private static final Set<String> AUTHORIZED = Set.of("AUTHORIZED", "PARTIALLY_CAPTURED", "CAPTURED");
    static final String AWAITING = "AWAITING_PAYMENT";
    static final String PAID = "PAID";

    private final OrdersAccess access;
    private final OrderRepository orders;
    private final CheckoutRequestRepository requests;
    private final PaymentsClient payments;
    private final OrderPlacementService placement;
    private final PayLinkSigner signer;
    private final PreorderSettings settings;
    private final Clock clock;

    PayLinkServiceImpl(OrdersAccess access, OrderRepository orders, CheckoutRequestRepository requests,
                       PaymentsClient payments, OrderPlacementService placement, PayLinkSigner signer,
                       PreorderSettings settings, Clock clock) {
        this.access = access;
        this.orders = orders;
        this.requests = requests;
        this.payments = payments;
        this.placement = placement;
        this.signer = signer;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public String page(String orderNumber) {
        return settings.payPageBaseUrl() + "/orders/" + URLEncoder.encode(orderNumber, StandardCharsets.UTF_8)
                + "/pay";
    }

    @Override
    public String link(String orderId, String orderNumber, Instant expiresAt) {
        return page(orderNumber) + "?token=" + URLEncoder.encode(signer.sign(orderId, orderNumber, expiresAt),
                StandardCharsets.UTF_8);
    }

    @Override
    public PayLinkView lookup(String orderNumber, String token) {
        access.payLinkCaller();
        Order o = verified(orderNumber, token);
        return view(o, null);
    }

    @Override
    public PayLinkView confirm(String orderNumber, String token) {
        access.payLinkCaller();
        Order o = verified(orderNumber, token);
        String failure = null;
        if (o.status() == OrderStatus.PENDING_PAYMENT && o.paymentId() != null) {
            PaymentsClient.Intent intent = payments.get(o.paymentId(), true);
            if (AUTHORIZED.contains(intent.status())) {
                o = placement.place(o.id());
            } else if ("FAILED".equals(intent.status())) {
                failure = intent.failureMessage();
            }
        }
        return view(o, failure);
    }

    /** The order of a valid, unexpired token whose order is awaiting payment or already paid. */
    private Order verified(String orderNumber, String token) {
        Order o = Optional.ofNullable(orderNumber).flatMap(orders::findByNumber)
                .filter(x -> x.origin().lateAdd() && x.origin().paymentLinkUrl() != null)
                .orElseThrow(PayLinkServiceImpl::invalid);
        Instant signed = signer.verify(o.id(), o.number(), token).orElseThrow(PayLinkServiceImpl::invalid);
        Instant recorded = o.origin().paymentLinkExpiresAt();
        Instant expiresAt = recorded != null && recorded.isBefore(signed) ? recorded : signed;
        boolean awaiting = o.status() == OrderStatus.PENDING_PAYMENT;
        if (o.status() == OrderStatus.CANCELLED || awaiting && !Instant.now(clock).isBefore(expiresAt)) {
            throw new OrdersStateConflict("orders.pay-link-expired", "This payment link has expired. Contact us if "
                    + "you still want this pre-order.");
        }
        return o;
    }

    private PayLinkView view(Order o, String failure) {
        boolean awaiting = o.status() == OrderStatus.PENDING_PAYMENT;
        String secret = awaiting ? requests.findByOrderId(o.id()).map(CheckoutRequestRepository.CheckoutRequest
                ::clientSecret).orElse(null) : null;
        List<Shipment> pre = o.preorderShipments();
        Shipment first = pre.isEmpty() ? null : pre.getFirst();
        List<PayLinkView.Line> lines = o.lines().stream()
                .map(l -> new PayLinkView.Line(l.title(), l.quantity(), l.totalMinor())).toList();
        return new PayLinkView(o.number(), awaiting ? AWAITING : PAID, o.totals().grandTotalMinor(),
                pre.stream().mapToLong(Shipment::shippingMinor).sum(), o.currency(), secret,
                awaiting ? failure : null, o.origin().paymentLinkExpiresAt(),
                first == null ? null : first.preorder().windowName(),
                first == null ? null : first.preorder().deliversOn(),
                first == null ? null : first.preorder().fulfilment().name(), lines);
    }

    private static NotFoundException invalid() {
        return new NotFoundException("orders.pay-link-not-found", "This payment link is not valid. Check that you "
                + "opened the whole link from the text message.");
    }
}
