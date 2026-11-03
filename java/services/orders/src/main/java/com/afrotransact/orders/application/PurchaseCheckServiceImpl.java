package com.afrotransact.orders.application;

import java.util.Locale;

import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.port.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PurchaseCheckServiceImpl implements PurchaseCheckService {

    private final OrderRepository orders;
    private final OrdersAccess access;

    PurchaseCheckServiceImpl(OrderRepository orders, OrdersAccess access) {
        this.orders = orders;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseCheck check(String customerId, String productAtin) {
        access.purchaseCheckCaller();
        if (customerId == null || customerId.isBlank() || productAtin == null || productAtin.isBlank()) {
            throw new OrdersRuleViolation("orders.invalid-purchase-check", "customerId and atin are required");
        }
        return orders.deliveredPurchase(customerId.trim(), productAtin.trim().toUpperCase(Locale.ROOT))
                .map(p -> new PurchaseCheck(true, p.orderId(), p.orderNumber(), p.shipmentId(), p.deliveredAt()))
                .orElseGet(PurchaseCheck::none);
    }
}
