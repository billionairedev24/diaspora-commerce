package com.afrotransact.seller.adapter.out.usage;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.seller.port.StorageUsageSource;

/** inventory-service {@code GET /v1/storage/usage?from=&to=} (scope {@code inventory:storage.read}). */
public class HttpStorageUsageSource implements StorageUsageSource {

    private final UsageHttp http;

    public HttpStorageUsageSource(String baseUrl, Duration timeout, ServiceTokens tokens) {
        this.http = new UsageHttp("inventory-service", baseUrl, timeout, tokens);
    }

    @Override
    public List<StorageSample> cubicFeet(LocalDate from, LocalDate to) {
        List<StorageSample> out = new ArrayList<>();
        http.items("/v1/storage/usage", from, to).forEach(i -> out.add(new StorageSample(
                i.path("ownerOrg").asString(), UsageHttp.day(i), i.path("cubicFeet").decimalValue())));
        return out;
    }
}
