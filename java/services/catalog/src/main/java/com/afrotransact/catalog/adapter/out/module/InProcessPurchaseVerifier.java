package com.afrotransact.catalog.adapter.out.module;

import java.util.Optional;

import com.afrotransact.catalog.domain.PurchaseProof;
import com.afrotransact.catalog.port.PurchaseVerifier;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.InProcessLink;
import com.afrotransact.orders.api.OrderLookupApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link PurchaseVerifier} through orders' published {@link OrderLookupApi} inside this JVM (mode {@code in-process}): the same
 * answer as {@code GET /v1/lookup/customers/{customerId}/purchases/{atin}}, entered as catalog's caller principal (scope
 * {@code orders:purchase-check}). Any failure, including a refusal of the principal, is {@link PurchaseCheckUnavailable}: a review
 * is never accepted on a guess.
 */
@Component
@ConditionalOnExpression("'${at.catalog.orders.mode:${at.catalog.mode:http}}' == 'in-process'")
class InProcessPurchaseVerifier implements PurchaseVerifier {

    private final InProcessLink<OrderLookupApi> orders;

    InProcessPurchaseVerifier(ObjectProvider<OrderLookupApi> api, ObjectProvider<CallerPrincipals> callers,
                              ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(OrderLookupApi.class, true, false).length > 0, "catalog",
                "orders", "at.catalog.orders.mode");
        this.orders = new InProcessLink<>("catalog", api::getObject, callers::getObject);
    }

    @Override
    public Optional<PurchaseProof> deliveredPurchase(String customerId, String productAtin) {
        try {
            OrderLookupApi.PurchaseProof body = orders.call(api -> api.purchase(customerId, productAtin));
            if (body == null || !body.delivered() || body.orderId() == null) {
                return Optional.empty();
            }
            return Optional.of(new PurchaseProof(body.orderId(),
                    body.orderNumber() == null ? body.orderId() : body.orderNumber(), body.deliveredAt()));
        } catch (RuntimeException e) {
            throw new PurchaseCheckUnavailable("orders could not confirm the purchase: " + e.getMessage(), e);
        }
    }
}
