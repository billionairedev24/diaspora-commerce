package com.afrotransact.ai.adapter.out.module;

import java.util.List;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.ReturnHistory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.taxreturns.api.ShopperReturnsApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ReturnHistory} through tax-returns' published {@link ShopperReturnsApi} inside this JVM (mode {@code in-process}), for the
 * signed-in shopper of the request: only their own returns (up to 50, as the HTTP reader asks), narrowed to one order here when asked.
 * Any refusal, failure or missing person is "not available", as over HTTP.
 */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "tax-returns", value = ModuleMode.IN_PROCESS)
class InProcessReturnHistory implements ReturnHistory {

    private static final String SERVICE = "tax-returns";
    private static final int LIMIT = 50;

    private final ObjectProvider<ShopperReturnsApi> api;
    private final ModuleUpstream upstream;

    InProcessReturnHistory(ObjectProvider<ShopperReturnsApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ShopperReturnsApi.class, true, false).length > 0, "ai-gateway",
                SERVICE, "at.aigateway.taxreturns.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public AccountReader.Result<List<AccountReader.ReturnSummary>> mine(Shopper shopper, String orderNumber) {
        return upstream.forShopper(SERVICE, shopper, user -> AccountReader.Result.of(api.getObject().mine(user, LIMIT).stream()
                .filter(r -> orderNumber.isBlank() || orderNumber.equalsIgnoreCase(text(r.orderNumber())))
                .map(InProcessReturnHistory::summary).toList()), e -> AccountReader.Result.unavailable(),
                AccountReader.Result::unavailable);
    }

    private static AccountReader.ReturnSummary summary(ShopperReturnsApi.ReturnSummary r) {
        return new AccountReader.ReturnSummary(text(r.number()), text(r.orderNumber()), text(r.status()), text(r.method()),
                text(r.refundState()), r.estimateMinor(), text(r.currency()), r.dropOffBy() == null ? "" : r.dropOffBy().toString(),
                r.createdAt() == null ? "" : r.createdAt().toString(), r.lines().stream().map(l -> new AccountReader.ReturnLine(
                text(l.title()), l.quantity(), text(l.reason()), text(l.outcome()))).toList());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
