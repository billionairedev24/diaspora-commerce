package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.CartReader;
import com.afrotransact.ai.port.Shopper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * The shopper's cart (cart service {@code GET /v1/cart}) under their token. The cart is a Go service, not a module of core, so this edge
 * stays HTTP in every deployment (listed in {@code http-edges.txt}). Anything that fails is "not available".
 */
@Component
class HttpCartReader implements CartReader {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCartReader.class);

    private final RestClient cart;

    HttpCartReader(RestClient.Builder builder, @Value("${at.ai.downstream.cart-url:http://localhost:8203}") String cartUrl,
                   @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.cart = Downstream.client(builder, cartUrl, timeout);
    }

    @Override
    public AccountReader.Result<AccountReader.CartSummary> cart(Shopper shopper) {
        return Downstream.read(LOG, "cart", () -> {
            JsonNode body = cart.get().uri("/v1/cart").headers(h -> Downstream.relay(h, shopper.bearerToken())).retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                return null;
            }
            List<AccountReader.CartLine> lines = new ArrayList<>();
            for (JsonNode p : body.path("packages")) {
                for (JsonNode l : p.path("lines")) {
                    String atin = Downstream.text(l, "variantAtin");
                    lines.add(new AccountReader.CartLine(atin.isBlank() ? Downstream.text(l, "atin") : atin,
                            Downstream.text(l, "title"), l.path("quantity").asInt(0), Downstream.number(l, "priceMinor"),
                            l.path("unavailable").asBoolean(false), l.path("priceChanged").asBoolean(false)));
                }
            }
            return new AccountReader.CartSummary(body.path("itemCount").asInt(lines.size()),
                    Downstream.number(body, "subtotalMinor"), Downstream.text(body, "currency"),
                    body.path("checkoutable").asBoolean(false), lines);
        });
    }
}
