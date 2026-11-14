package com.afrotransact.orders.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.port.TaxQuoteClient.ShipFrom;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Where a shipment ships from: a centre, a pickup location or the seller's default; never a guess. */
class HttpShipFromAddressesTest {

    private final List<String> paths = new ArrayList<>();
    private final Map<String, String> answers = new HashMap<>();
    private HttpServer server;
    private HttpShipFromAddresses addresses;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getPath());
            String body = answers.get(ex.getRequestURI().getPath());
            byte[] out = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(body == null ? 404 : 200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        addresses = new HttpShipFromAddresses(RestClient.builder(), url, url, Duration.ofSeconds(2), () -> "token");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aSellerFulfilledShipmentShipsFromTheSellersDefaultAddress() {
        answers.put("/v1/orgs/org_a/default-ship-from-location", """
                {"line1":"100 Commerce St","city":"Dallas","state":"TX","postalCode":"75201","country":"US"}""");

        assertThat(addresses.forShipment(FulfilmentType.SELLER_FULFILLED, "org_a", null, null)).contains(
                new ShipFrom("100 Commerce St", "Dallas", "TX", "75201", "US", "SELLER_LOCATION"));
    }

    @Test
    void aPickupLocationWinsOverTheDefaultAndAnFbaShipmentShipsFromItsCentre() {
        answers.put("/v1/ship-from-locations/loc1", """
                {"line1":"5 Dock Rd","city":"Houston","state":"TX","postalCode":"77001","country":"US"}""");
        answers.put("/v1/nodes/HOU-FC1/address", """
                {"line1":"9 Fulfilment Way","city":"Katy","region":"TX","postalCode":"77449","country":"US"}""");

        assertThat(addresses.forShipment(FulfilmentType.SELLER_FULFILLED, "org_a", null, "loc1")).get()
                .extracting(ShipFrom::line1, ShipFrom::source).containsExactly("5 Dock Rd", "SELLER_LOCATION");
        assertThat(addresses.forShipment(FulfilmentType.FBA_AFROTRANSACT, "org_a", "HOU-FC1", null)).get()
                .extracting(ShipFrom::line1, ShipFrom::state, ShipFrom::source)
                .containsExactly("9 Fulfilment Way", "TX", "FULFILMENT_CENTRE");
    }

    @Test
    void anUnknownPlaceOrAnAddressWithoutStateAndZipIsNoOrigin() {
        assertThat(addresses.forShipment(FulfilmentType.SELLER_FULFILLED, "org_none", null, null)).isEmpty();
        answers.put("/v1/orgs/org_b/default-ship-from-location", "{\"line1\":\"1 Main\",\"city\":\"Dallas\"}");
        assertThat(addresses.forShipment(FulfilmentType.SELLER_FULFILLED, "org_b", null, null)).isEmpty();
        assertThat(addresses.forShipment(FulfilmentType.RETAIL_1P, null, null, null)).isEmpty();
    }
}
