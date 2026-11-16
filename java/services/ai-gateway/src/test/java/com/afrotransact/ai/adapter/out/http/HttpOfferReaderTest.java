package com.afrotransact.ai.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.OfferReader;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** compare_offers reads the endpoint offers really serves: the buy boxes of the product page, winner first, then the other offers. */
class HttpOfferReaderTest {

    private static final String BOXES = "{\"items\":[{\"atin\":\"ATV\",\"buyBox\":{\"offerId\":\"o1\",\"seller\":{\"name\":\"Malo\","
            + "\"kind\":\"SELLER\"},\"fulfilment\":\"SELLER\",\"priceMinor\":899,\"currency\":\"USD\",\"stockStatus\":\"LOW_STOCK\","
            + "\"deliveryDays\":3,\"condition\":\"NEW\"},\"otherOffers\":[{\"offerId\":\"o2\",\"seller\":{\"name\":\"Ada\",\"kind\":\"SELLER\"},"
            + "\"fulfilment\":\"AFROTRANSACT\",\"priceMinor\":949,\"currency\":\"USD\",\"stockStatus\":\"OUT_OF_STOCK\",\"deliveryDays\":5,"
            + "\"condition\":\"NEW\"}]}]}";

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = (status == 200 ? BOXES : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OfferReader reader() {
        return new HttpOfferReader(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2));
    }

    @Test
    void readsTheBuyBoxesEndpointAndFlattensWinnerFirst() {
        assertThat(reader().offers("ATP", null)).containsExactly(
                new OfferReader.Offer("o1", "Malo", "SELLER", "SELLER", 899, "USD", true, 3, "NEW"),
                new OfferReader.Offer("o2", "Ada", "SELLER", "AFROTRANSACT", 949, "USD", false, 5, "NEW"));
        assertThat(paths).containsExactly("/v1/public/products/ATP/buy-boxes");
    }

    @Test
    void aFailureIsNoOffers() {
        status = 503;
        assertThat(reader().offers("ATP", null)).isEmpty();
    }
}
