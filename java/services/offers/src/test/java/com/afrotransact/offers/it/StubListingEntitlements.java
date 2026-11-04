package com.afrotransact.offers.it;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.offers.port.ListingEntitlementsClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** In-memory seller-service plan listing caps (no network in tests); unknown organisations are not capped. */
@TestConfiguration(proxyBeanMethods = false)
public class StubListingEntitlements {

    private static final Map<String, ListingEntitlementsClient.ListingAllowance> BY_ORG = new ConcurrentHashMap<>();

    public static void limit(String orgId, String planName, long limit) {
        BY_ORG.put(orgId, new ListingEntitlementsClient.ListingAllowance(planName, limit));
    }

    public static void clear(String orgId) {
        BY_ORG.remove(orgId);
    }

    @Bean
    @Primary
    ListingEntitlementsClient stubListingEntitlements() {
        return org -> BY_ORG.getOrDefault(org, ListingEntitlementsClient.ListingAllowance.UNLIMITED);
    }
}
