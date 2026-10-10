package com.afrotransact.offers.it;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.offers.port.PickupLocationClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** In-memory seller-service ship-from locations (no network in tests). */
@TestConfiguration(proxyBeanMethods = false)
public class StubPickupLocations {

    private static final Map<String, PickupLocationClient.PickupLocation> BY_ID = new ConcurrentHashMap<>();

    public static void put(String id, String orgId, boolean pickupEnabled, String city) {
        BY_ID.put(id, new PickupLocationClient.PickupLocation(id, orgId, pickupEnabled, city, "TX", "US", "77002"));
    }

    @Bean
    @Primary
    PickupLocationClient stubPickupLocations() {
        return id -> Optional.ofNullable(BY_ID.get(id));
    }
}
