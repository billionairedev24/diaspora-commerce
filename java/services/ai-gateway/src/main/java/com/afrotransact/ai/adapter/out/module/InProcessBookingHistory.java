package com.afrotransact.ai.adapter.out.module;

import java.util.List;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.BookingHistory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.bookings.api.ShopperBookingsApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link BookingHistory} through bookings' published {@link ShopperBookingsApi} inside this JVM (mode {@code in-process}), for the
 * signed-in shopper of the request: only their own bookings. Any refusal, failure or missing person is "not available", as over HTTP.
 */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "bookings", value = ModuleMode.IN_PROCESS)
class InProcessBookingHistory implements BookingHistory {

    private static final String SERVICE = "bookings";

    private final ObjectProvider<ShopperBookingsApi> api;
    private final ModuleUpstream upstream;

    InProcessBookingHistory(ObjectProvider<ShopperBookingsApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ShopperBookingsApi.class, true, false).length > 0, "ai-gateway",
                SERVICE, "at.aigateway.bookings.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public AccountReader.Result<List<AccountReader.BookingSummary>> mine(Shopper shopper) {
        return upstream.forShopper(SERVICE, shopper, user -> AccountReader.Result.of(api.getObject().mine(user).stream()
                .map(b -> new AccountReader.BookingSummary(text(b.reference()), text(b.serviceName()), text(b.status()),
                        b.startAt() == null ? "" : b.startAt().toString(), b.priceMinor(), b.depositMinor())).toList()),
                e -> AccountReader.Result.unavailable(), AccountReader.Result::unavailable);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
