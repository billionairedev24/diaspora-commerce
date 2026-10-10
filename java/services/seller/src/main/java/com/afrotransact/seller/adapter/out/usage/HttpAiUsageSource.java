package com.afrotransact.seller.adapter.out.usage;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.seller.port.AiUsageSource;

/** ai-gateway {@code GET /v1/ai/usage/orgs?from=&to=} (scope {@code ai:usage.read}). */
public class HttpAiUsageSource implements AiUsageSource {

    private final UsageHttp http;

    public HttpAiUsageSource(String baseUrl, Duration timeout, ServiceTokens tokens) {
        this.http = new UsageHttp("ai-gateway", baseUrl, timeout, tokens);
    }

    @Override
    public List<AiCreditSample> credits(LocalDate from, LocalDate to) {
        List<AiCreditSample> out = new ArrayList<>();
        http.items("/v1/ai/usage/orgs", from, to).forEach(i -> out.add(new AiCreditSample(
                i.path("orgId").asString(), UsageHttp.day(i), i.path("credits").asLong())));
        return out;
    }
}
