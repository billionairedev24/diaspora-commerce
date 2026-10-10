package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** tax-returns-service (port 8111) {@code POST /v1/tax/quotes} with the orders-service token (scope tax:quote). */
@Component
class HttpTaxQuoteClient implements TaxQuoteClient {

    private final Upstream taxReturns;
    private final ServiceTokenProvider tokens;

    HttpTaxQuoteClient(RestClient.Builder builder, @Value("${at.orders.upstreams.tax-returns-url}") String baseUrl,
                       @Value("${at.orders.upstreams.tax-timeout:2s}") Duration timeout, ServiceTokenProvider tokens) {
        this.taxReturns = new Upstream("tax-returns", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Quote quote(Request request) {
        Map<String, Object> shipTo = new LinkedHashMap<>();
        shipTo.put("country", request.country());
        shipTo.put("state", request.state());
        shipTo.put("postalCode", request.postalCode());
        shipTo.put("city", request.city());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Line l : request.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", l.lineId());
            m.put("atin", l.atin());
            m.put("sellerOrgId", l.sellerOrgId());
            m.put("productType", l.productType());
            m.put("quantity", l.quantity());
            m.put("unitPriceMinor", l.unitPriceMinor());
            m.put("discountMinor", l.discountMinor());
            m.put("shippingMinor", l.shippingMinor());
            lines.add(m);
        }
        JsonNode body = taxReturns.call(c -> c.post().uri("/v1/tax/quotes")
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(Map.of("currency", request.currency(), "shipTo", shipTo, "lines", lines))
                .retrieve().body(JsonNode.class));
        if (body == null) {
            throw new UpstreamFailure("tax-returns", 0, "tax-returns.invalid-response", "Empty tax quote", true,
                    List.of(), null);
        }
        List<LineTax> out = new ArrayList<>();
        for (JsonNode l : body.path("lines")) {
            out.add(new LineTax(l.path("lineId").asString(null), l.path("taxMinor").asLong(),
                    l.path("shippingTaxMinor").asLong(0), l.path("jurisdiction").asString(null),
                    l.path("localJurisdiction").asString(null)));
        }
        return new Quote(body.path("provider").asString(null), out);
    }
}
