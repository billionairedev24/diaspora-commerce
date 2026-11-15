package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** tax-returns-service (port 8111) {@code POST /v1/tax/quotes} with the orders-service token (scope tax:quote). */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "tax-returns", value = ModuleMode.HTTP)
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
        if (request.line1() != null && !request.line1().isBlank()) {
            shipTo.put("line1", request.line1());
        }
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
            m.put("sellerBehaviour", l.sellerBehaviour());
            if (l.shipFrom() != null) {
                Map<String, Object> from = new LinkedHashMap<>();
                from.put("line1", l.shipFrom().line1());
                from.put("city", l.shipFrom().city());
                from.put("state", l.shipFrom().state());
                from.put("postalCode", l.shipFrom().postalCode());
                from.put("country", l.shipFrom().country());
                from.put("source", l.shipFrom().source());
                m.put("shipFrom", from);
            }
            lines.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("currency", request.currency());
        payload.put("shipTo", shipTo);
        payload.put("lines", lines);
        if (request.orderId() != null) {
            payload.put("orderId", request.orderId());
        }
        JsonNode body = taxReturns.call(c -> c.post().uri("/v1/tax/quotes")
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(payload)
                .retrieve().body(JsonNode.class));
        if (body == null) {
            throw new UpstreamFailure("tax-returns", 0, "tax-returns.invalid-response", "Empty tax quote", true,
                    List.of(), null);
        }
        List<LineTax> out = new ArrayList<>();
        for (JsonNode l : body.path("lines")) {
            out.add(new LineTax(l.path("lineId").asString(null), l.path("taxMinor").asLong(),
                    l.path("shippingTaxMinor").asLong(0), l.path("jurisdiction").asString(null),
                    l.path("localJurisdiction").asString(null), l.path("decisionStatus").asString(null),
                    l.path("outcome").asString(null), l.path("internalTaxCode").asString(null),
                    l.path("providerTaxCode").asString(null), l.path("ruleVersion").asString(null),
                    l.path("blockedReason").asString(null), l.path("addressPrecision").asString(null)));
        }
        return new Quote(body.path("provider").asString(null), out);
    }
}
