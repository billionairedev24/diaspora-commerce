package com.afrotransact.inventory.adapter.in.module;

import com.afrotransact.inventory.api.ReturnsApi;
import com.afrotransact.inventory.application.ReturnCommand;
import com.afrotransact.inventory.application.ReturnService;
import com.afrotransact.inventory.domain.ReturnCondition;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ReturnsApi}: the use case behind {@code POST /v1/returns}, entered under the calling module's
 * principal. {@link ReturnService} applies {@code inventory:adjust} (or a service with {@code inventory:returns}) as for a token.
 */
@Component
class ReturnsApiAdapter implements ReturnsApi {

    private final ReturnService returns;
    private final ModuleEntry entry;

    ReturnsApiAdapter(ReturnService returns, ActorResolver actors) {
        this.returns = returns;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public void acceptReturn(String orderId, String sku, int quantity, String condition, String reason,
                             String receivingNodeId, String idempotencyKey) {
        entry.runVoid(() -> returns.accept(new ReturnCommand(orderId, sku, null, quantity,
                ReturnCondition.valueOf(condition), reason, receivingNodeId), idempotencyKey), InventoryFailures::map);
    }
}
