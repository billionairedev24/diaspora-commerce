package com.afrotransact.offers.it;

import java.util.List;
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

    private static final Map<String, CatalogClient.Eligibility> ELIGIBILITY = new ConcurrentHashMap<>();

    /** Every condition allowed, nothing in the way: what catalog answers for an ordinary page. */
    private static final CatalogClient.Eligibility OPEN = new CatalogClient.Eligibility(true, List.of(),
            List.of("NEW", "RENEWED", "USED_LIKE_NEW", "USED_VERY_GOOD", "USED_GOOD", "USED_ACCEPTABLE"));

    /** Catalog refuses offers on this variant (for example a gated brand without approval). */
    public static void refuse(String variantAtin, String code, String message) {
        ELIGIBILITY.put(variantAtin, new CatalogClient.Eligibility(false, List.of(new CatalogClient.Blocker(code,
                message)), List.of("NEW")));
    }

    /** Catalog allows only these conditions on this variant. */
    public static void allowOnly(String variantAtin, String... conditions) {
        ELIGIBILITY.put(variantAtin, new CatalogClient.Eligibility(true, List.of(), List.of(conditions)));
    }

    @Bean
    @Primary
    CatalogClient stubCatalogClient() {
        return new CatalogClient() {
            @Override
            public Eligibility eligibility(String variantAtin, String sellerOrgId) {
                return ELIGIBILITY.getOrDefault(variantAtin, OPEN);
            }

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
