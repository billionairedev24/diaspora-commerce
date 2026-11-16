package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.ReturnHistory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** The shopper's own returns (tax-returns {@code GET /v1/returns}) under their token. Anything that fails is "not available". */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "tax-returns", value = ModuleMode.HTTP)
class HttpReturnHistory implements ReturnHistory {

    private static final Logger LOG = LoggerFactory.getLogger(HttpReturnHistory.class);

    private final RestClient returns;

    HttpReturnHistory(RestClient.Builder builder, @Value("${at.ai.downstream.returns-url:http://localhost:8111}") String returnsUrl,
                      @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.returns = Downstream.client(builder, returnsUrl, timeout);
    }

    @Override
    public AccountReader.Result<List<AccountReader.ReturnSummary>> mine(Shopper shopper, String orderNumber) {
        return Downstream.read(LOG, "returns", () -> {
            JsonNode body = returns.get().uri(u -> u.path("/v1/returns").queryParam("limit", 50).build())
                    .headers(h -> Downstream.relay(h, shopper.bearerToken())).retrieve().body(JsonNode.class);
            List<AccountReader.ReturnSummary> list = new ArrayList<>();
            for (JsonNode r : Downstream.items(body)) {
                String order = Downstream.text(r, "orderNumber");
                if (!orderNumber.isBlank() && !orderNumber.equalsIgnoreCase(order)) {
                    continue;
                }
                List<AccountReader.ReturnLine> lines = new ArrayList<>();
                for (JsonNode l : r.path("lines")) {
                    lines.add(new AccountReader.ReturnLine(Downstream.text(l, "title"), l.path("quantity").asInt(1),
                            Downstream.text(l, "reason"), Downstream.text(l, "outcome")));
                }
                list.add(new AccountReader.ReturnSummary(Downstream.text(r, "number"), order, Downstream.text(r, "status"),
                        Downstream.text(r, "method"), Downstream.text(r, "refundState"),
                        Downstream.number(r, "estimateMinor"), Downstream.text(r, "currency"),
                        Downstream.text(r, "dropOffBy"), Downstream.text(r, "createdAt"), lines));
            }
            return list;
        });
    }
}
