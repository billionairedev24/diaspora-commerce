package com.afrotransact.ai.adapter.out.module;

import java.util.Optional;

import com.afrotransact.ai.port.CatalogReader;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.catalog.api.PublicProductApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link CatalogReader} through catalog's published {@link PublicProductApi} inside this JVM (mode {@code in-process}), entered as
 * ai-gateway's caller principal: the same anonymous public reads the HTTP reader makes, no scope. A product that is unknown, not live
 * or whose read failed is "not found" (Lola then refuses to invent it), as over HTTP.
 */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "catalog", value = ModuleMode.IN_PROCESS)
class InProcessCatalogReader implements CatalogReader {

    private static final String SERVICE = "catalog";

    private final ObjectProvider<PublicProductApi> api;
    private final ModuleUpstream upstream;

    InProcessCatalogReader(ObjectProvider<PublicProductApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(PublicProductApi.class, true, false).length > 0, "ai-gateway",
                SERVICE, "at.aigateway.catalog.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Optional<Item> find(String atin, Shopper shopper) {
        return upstream.call(SERVICE, () -> api.getObject().find(atin).map(i -> new Item(i.atin(), i.productAtin(), nullToEmpty(i.title()),
                i.attributes(), i.priceMinor(), i.currency() == null || i.currency().isBlank() ? "USD" : i.currency(),
                nullToEmpty(i.sellerOrgId()))), e -> Optional.empty(), Optional::empty);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
