package com.afrotransact.offers.it;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.offers.port.CatalogClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** In-memory catalog (no network in tests): register live variants, then create offers against them. */
@TestConfiguration(proxyBeanMethods = false)
public class StubCatalog {

    private static final Map<String, CatalogClient.CatalogVariant> BY_ATIN = new ConcurrentHashMap<>();

    public static CatalogClient.CatalogVariant live(String productAtin, String variantAtin, String gtin,
                                                    String ownerOrg) {
        CatalogClient.CatalogVariant v = new CatalogClient.CatalogVariant(UUID.randomUUID(), productAtin,
                variantAtin, gtin, UUID.randomUUID(), "Jollof Seasoning Blend 8 oz", ownerOrg);
        BY_ATIN.put(variantAtin, v);
        return v;
    }

    @Bean
    @Primary
    CatalogClient stubCatalogClient() {
        return new CatalogClient() {
            @Override
            public Optional<CatalogVariant> liveVariant(String atin) {
                return Optional.ofNullable(BY_ATIN.get(atin));
            }

            @Override
            public Optional<CatalogVariant> liveVariantByGtin(String gtin) {
                return BY_ATIN.values().stream().filter(v -> gtin.equals(v.gtin())).findFirst();
            }
        };
    }
}
