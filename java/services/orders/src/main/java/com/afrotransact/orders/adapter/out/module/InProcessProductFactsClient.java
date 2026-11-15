package com.afrotransact.orders.adapter.out.module;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.catalog.api.ProductListingApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.orders.application.OrdersSettings;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link ProductFactsClient} through catalog's published {@link ProductListingApi} inside this JVM (mode {@code in-process}):
 * product type to perishable flag and payments commission category, and the live listing as the cart shows it. The same
 * degradation as over HTTP: an unknown variant is left out, an unavailable catalog fails listings and is skipped for facts.
 */
@Component
@ConditionalOnExpression("'${at.orders.catalog.mode:${at.orders.mode:http}}' == 'in-process'")
class InProcessProductFactsClient implements ProductFactsClient {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessProductFactsClient.class);
    private static final String SERVICE = "catalog";

    private final ObjectProvider<ProductListingApi> api;
    private final ModuleUpstream upstream;
    private final Set<String> perishableTypes;
    private final Map<String, String> categories;

    InProcessProductFactsClient(ObjectProvider<ProductListingApi> api, ModuleUpstream upstream, OrdersSettings settings,
                                ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ProductListingApi.class, true, false).length > 0,
                "orders", "catalog", "at.orders.catalog.mode");
        this.api = api;
        this.upstream = upstream;
        this.perishableTypes = settings.perishableTypes();
        this.categories = settings.commissionCategories();
    }

    @Override
    public Map<String, Listing> listings(Collection<String> variantAtins) {
        Map<String, Listing> out = new LinkedHashMap<>();
        for (String atin : variantAtins) {
            try {
                Optional<ProductListingApi.LiveListing> live = upstream.call(SERVICE,
                        () -> api.getObject().liveListing(atin));
                live.ifPresent(l -> out.put(atin, new Listing(atin, l.productAtin(), l.title(), l.imageUrl(),
                        l.sellerOrgId(), l.ownerKind() == null ? "SELLER" : l.ownerKind(), l.priceMinor(),
                        l.currency() == null ? "USD" : l.currency())));
            } catch (UpstreamFailure f) {
                if (f.retryable()) {
                    throw f;
                }
                LOG.info("catalog has no live variant {} ({})", atin, f.code());
            }
        }
        return out;
    }

    @Override
    public Map<String, Facts> facts(Collection<String> variantAtins) {
        Map<String, Facts> out = new LinkedHashMap<>();
        for (String atin : variantAtins) {
            try {
                upstream.call(SERVICE, () -> api.getObject().liveListing(atin)).ifPresent(l -> {
                    String type = l.productType();
                    boolean perishable = type != null && perishableTypes.contains(type)
                            || "true".equalsIgnoreCase(l.attributes().getOrDefault("perishable", ""));
                    out.put(atin, new Facts(atin, type, perishable, type == null ? null
                            : categories.getOrDefault(type, type), l.sellerOrgId(), behaviour(l.sellerBehaviour())));
                });
            } catch (UpstreamFailure f) {
                LOG.warn("catalog facts unavailable for {} ({})", atin, f.code());
            }
        }
        return out;
    }

    /** The typed values as the HTTP answer carried them: booleans, whole numbers (long), decimals and text; nested values are dropped. */
    static Map<String, Object> behaviour(Map<String, Object> facts) {
        Map<String, Object> out = new LinkedHashMap<>();
        facts.forEach((key, v) -> {
            if (v instanceof Boolean || v instanceof String) {
                out.put(key, v);
            } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                out.put(key, ((Number) v).longValue());
            } else if (v instanceof Number n) {
                out.put(key, new BigDecimal(n.toString()));
            }
        });
        return out;
    }
}
