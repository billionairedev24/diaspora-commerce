package com.afrotransact.ai.adapter.out.module;

import java.util.List;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.OrderHistory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.api.ShopperOrdersApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link OrderHistory} through orders' published {@link ShopperOrdersApi} inside this JVM (mode {@code in-process}), for the signed-in
 * shopper of the request: only their own orders, newest first. Any refusal, failure or missing person is "not available", as over HTTP.
 */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "orders", value = ModuleMode.IN_PROCESS)
class InProcessOrderHistory implements OrderHistory {

    private static final String SERVICE = "orders";

    private final ObjectProvider<ShopperOrdersApi> api;
    private final ModuleUpstream upstream;

    InProcessOrderHistory(ObjectProvider<ShopperOrdersApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ShopperOrdersApi.class, true, false).length > 0, "ai-gateway",
                SERVICE, "at.aigateway.orders.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public AccountReader.Result<List<AccountReader.OrderSummary>> recent(Shopper shopper, int limit) {
        return upstream.forShopper(SERVICE, shopper, user -> AccountReader.Result.of(api.getObject().mine(user, limit).stream()
                .map(o -> new AccountReader.OrderSummary(text(o.orderNumber()), text(o.status()),
                        o.placedAt() == null ? "" : o.placedAt().toString(), o.totalMinor(), text(o.currency()), o.shipments()))
                .toList()), e -> AccountReader.Result.unavailable(), AccountReader.Result::unavailable);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
