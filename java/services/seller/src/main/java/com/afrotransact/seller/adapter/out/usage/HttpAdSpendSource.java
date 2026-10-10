package com.afrotransact.seller.adapter.out.usage;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.seller.port.AdSpendSource;

/** content-service {@code GET /v1/ads/spend?from=&to=} (scope {@code ads:spend.read}). */
public class HttpAdSpendSource implements AdSpendSource {

    private final UsageHttp http;

    public HttpAdSpendSource(String baseUrl, Duration timeout, ServiceTokens tokens) {
        this.http = new UsageHttp("content-service", baseUrl, timeout, tokens);
    }

    @Override
    public List<AdSpendSample> spend(LocalDate from, LocalDate to) {
        List<AdSpendSample> out = new ArrayList<>();
        http.items("/v1/ads/spend", from, to).forEach(i -> out.add(new AdSpendSample(
                i.path("sellerOrgId").asString(), UsageHttp.day(i), i.path("currency").asString("USD"),
                i.path("spendMinor").asLong())));
        return out;
    }
}
