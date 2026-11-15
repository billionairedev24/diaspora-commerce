package com.afrotransact.taxreturns.adapter.out.module;

import com.afrotransact.inventory.api.ReturnsApi;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.port.InventoryGateway;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link InventoryGateway} through inventory's published {@link ReturnsApi} inside this JVM (mode {@code in-process}): the same
 * restock as {@code POST /v1/returns}, entered as tax-returns' caller principal (scope {@code inventory:adjust}). Failures are the
 * HTTP adapter's {@code UpstreamException}s (status and problem code of the refusal).
 */
@Component
@ConditionalOnExpression("'${at.taxreturns.inventory.mode:${at.taxreturns.mode:http}}' == 'in-process'")
class InProcessInventoryGateway implements InventoryGateway {

    private final ModuleUpstreams<ReturnsApi> inventory;

    InProcessInventoryGateway(ObjectProvider<ReturnsApi> api, ObjectProvider<CallerPrincipals> callers,
                              ListableBeanFactory beans) {
        this.inventory = new ModuleUpstreams<>(ReturnsApi.class, "inventory", api, callers, beans);
    }

    @Override
    public void acceptReturn(String orderId, String sku, int quantity, ItemCondition condition, String reason,
                             String receivingNodeId, String idempotencyKey) {
        String trimmed = reason.length() > 500 ? reason.substring(0, 500) : reason;
        String node = receivingNodeId == null || receivingNodeId.isBlank() ? null : receivingNodeId;
        inventory.call(api -> {
            api.acceptReturn(orderId, sku, quantity, condition.name(), trimmed, node, idempotencyKey);
            return null;
        });
    }
}
