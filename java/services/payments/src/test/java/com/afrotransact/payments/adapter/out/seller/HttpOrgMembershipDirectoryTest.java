package com.afrotransact.payments.adapter.out.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.payments.port.OrgMembershipUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Fail closed: anything that is not a clear answer from seller refuses the action; an answer is cached only briefly. */
class HttpOrgMembershipDirectoryTest {

    private static HttpOrgMembershipDirectory directory(String baseUrl, String secret, Duration ttl) {
        return new HttpOrgMembershipDirectory(RestClient.builder(), Clock.systemUTC(), baseUrl,
                baseUrl + "/oauth2/token", "payments-service", secret, Duration.ofSeconds(2), ttl);
    }

    @Test
    void unconfiguredRefusesInsteadOfAllowing() {
        assertThatThrownBy(() -> directory("", "", Duration.ofSeconds(3)).roleOf("org-a", "u1"))
                .isInstanceOf(OrgMembershipUnavailableException.class);
        assertThatThrownBy(() -> directory("http://localhost:9", "", Duration.ofSeconds(3)).roleOf("org-a", "u1"))
                .isInstanceOf(OrgMembershipUnavailableException.class);
    }

    @Test
    void unreachableSellerRefuses() {
        assertThatThrownBy(() -> directory("http://127.0.0.1:1", "secret", Duration.ofSeconds(3))
                .roleOf("org-a", "u1")).isInstanceOf(OrgMembershipUnavailableException.class);
    }

    @Test
    void readsTheLiveRoleAndRemembersItOnlyBriefly() throws IOException {
        AtomicInteger roleCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", ex -> reply(ex, 200,
                "{\"access_token\":\"tok\",\"expires_in\":300}"));
        server.createContext("/v1/orgs/org-a/members/u1/role", ex -> {
            roleCalls.incrementAndGet();
            reply(ex, 200, "{\"orgId\":\"org-a\",\"userId\":\"u1\",\"member\":true,\"roleKey\":\"seller.finance\"}");
        });
        server.createContext("/v1/orgs/org-a/members/gone/role", ex -> reply(ex, 200,
                "{\"orgId\":\"org-a\",\"userId\":\"gone\",\"member\":false}"));
        server.createContext("/v1/orgs/org-a/members/boom/role", ex -> reply(ex, 503, "{}"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpOrgMembershipDirectory cached = directory(base, "secret", Duration.ofSeconds(30));
            assertThat(cached.roleOf("org-a", "u1")).contains("seller.finance");
            assertThat(cached.roleOf("org-a", "u1")).contains("seller.finance");
            assertThat(roleCalls.get()).as("second answer from the few-seconds cache").isEqualTo(1);
            assertThat(cached.roleOf("org-a", "gone")).isEmpty();
            assertThatThrownBy(() -> cached.roleOf("org-a", "boom"))
                    .isInstanceOf(OrgMembershipUnavailableException.class);

            HttpOrgMembershipDirectory uncached = directory(base, "secret", Duration.ZERO);
            uncached.roleOf("org-a", "u1");
            uncached.roleOf("org-a", "u1");
            assertThat(roleCalls.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
