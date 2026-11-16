package com.afrotransact.ai.adapter.out.module;

import java.util.List;

import com.afrotransact.ai.port.ServiceDirectory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.bookings.api.ServiceSearchApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ServiceDirectory} through bookings' published {@link ServiceSearchApi} inside this JVM (mode {@code in-process}), entered as
 * ai-gateway's caller principal: the anonymous service search, no scope, at most six results as the HTTP reader asks. Any refusal (an
 * invalid ZIP, the geo service down) or failure reads as no services, as over HTTP.
 */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "bookings", value = ModuleMode.IN_PROCESS)
class InProcessServiceDirectory implements ServiceDirectory {

    private static final String SERVICE = "bookings";
    private static final int SIZE = 6;

    private final ObjectProvider<ServiceSearchApi> api;
    private final ModuleUpstream upstream;

    InProcessServiceDirectory(ObjectProvider<ServiceSearchApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ServiceSearchApi.class, true, false).length > 0, "ai-gateway",
                SERVICE, "at.aigateway.bookings.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public List<Listing> find(String query, String zip, Shopper shopper) {
        return upstream.call(SERVICE, () -> api.getObject().search(query, zip, SIZE).stream()
                .map(s -> new Listing(text(s.serviceId()), text(s.name()), text(s.providerName()), text(s.description()),
                        s.priceFromMinor(), s.currency() == null || s.currency().isBlank() ? "USD" : s.currency(),
                        s.depositMinor(), s.rating(), text(s.nextAvailable()), text(s.location()))).toList(),
                e -> List.of(), List::of);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
