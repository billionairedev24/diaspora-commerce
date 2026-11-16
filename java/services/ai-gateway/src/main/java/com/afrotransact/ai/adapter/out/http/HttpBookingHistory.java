package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.BookingHistory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** The shopper's own bookings (bookings {@code GET /v1/bookings/mine}) under their token. Anything that fails is "not available". */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "bookings", value = ModuleMode.HTTP)
class HttpBookingHistory implements BookingHistory {

    private static final Logger LOG = LoggerFactory.getLogger(HttpBookingHistory.class);

    private final RestClient bookings;

    HttpBookingHistory(RestClient.Builder builder, @Value("${at.ai.downstream.bookings-url}") String bookingsUrl,
                       @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.bookings = Downstream.client(builder, bookingsUrl, timeout);
    }

    @Override
    public AccountReader.Result<List<AccountReader.BookingSummary>> mine(Shopper shopper) {
        return Downstream.read(LOG, "bookings", () -> {
            JsonNode body = bookings.get().uri("/v1/bookings/mine").headers(h -> Downstream.relay(h, shopper.bearerToken()))
                    .retrieve().body(JsonNode.class);
            List<AccountReader.BookingSummary> list = new ArrayList<>();
            for (JsonNode b : Downstream.items(body)) {
                JsonNode s = b.path("service");
                String status = Downstream.text(b, "statusLabel");
                list.add(new AccountReader.BookingSummary(Downstream.text(b, "reference"), Downstream.text(s, "name"),
                        status.isBlank() ? Downstream.text(b, "status") : status, Downstream.text(b, "startAt"),
                        Downstream.number(s, "priceMinor"), Downstream.number(s, "depositMinor")));
            }
            return list;
        });
    }
}
