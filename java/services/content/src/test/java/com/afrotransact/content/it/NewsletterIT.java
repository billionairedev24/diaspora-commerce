package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.afrotransact.content.application.ContentEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Double opt-in: PENDING → confirmation_requested event → signed confirm link → CONFIRMED → unsubscribe. */
class NewsletterIT extends ContentIntegrationTest {

    @Test
    void doubleOptIn() {
        String email = "ada+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        HttpResponse<String> subscribed = post("/v1/newsletter/subscriptions",
                "{\"email\":\"" + email.toUpperCase() + "\",\"locale\":\"fr\",\"source\":\"footer\"}");
        assertThat(subscribed.statusCode()).isEqualTo(202);
        assertThat(json(subscribed).get("status").asString()).isEqualTo("pending_confirmation");
        assertThat(status(email)).isEqualTo("PENDING");

        JsonNode event = event(email);
        assertThat(event.get("locale").asString()).isEqualTo("fr");
        assertThat(event.get("confirmUrl").asString()).startsWith("http://localhost:3000/newsletter/confirm?token=");
        String confirmToken = token(event.get("confirmUrl").asString());
        String unsubscribeToken = token(event.get("unsubscribeUrl").asString());

        HttpResponse<String> tampered = post("/v1/newsletter/confirmations",
                "{\"token\":\"" + confirmToken.substring(0, confirmToken.length() - 2) + "xx\"}");
        assertThat(tampered.statusCode()).isEqualTo(422);
        assertThat(json(tampered).get("code").asString()).isEqualTo("content.invalid-token");
        assertThat(post("/v1/newsletter/unsubscriptions", "{\"token\":\"" + confirmToken + "\"}").statusCode())
                .as("a confirm link cannot unsubscribe").isEqualTo(422);

        HttpResponse<String> confirmed = post("/v1/newsletter/confirmations", "{\"token\":\"" + confirmToken + "\"}");
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(json(confirmed).get("status").asString()).isEqualTo("subscribed");
        assertThat(status(email)).isEqualTo("CONFIRMED");

        assertThat(json(post("/v1/newsletter/subscriptions", "{\"email\":\"" + email + "\"}")).get("status").asString())
                .isEqualTo("subscribed");
        assertThat(count(ContentEvents.NEWSLETTER_CONFIRMATION_REQUESTED, email)).isEqualTo(1);
        assertThat(count(ContentEvents.NEWSLETTER_SUBSCRIBED, email)).isEqualTo(1);

        assertThat(json(post("/v1/newsletter/unsubscriptions", "{\"token\":\"" + unsubscribeToken + "\"}"))
                .get("status").asString()).isEqualTo("unsubscribed");
        assertThat(status(email)).isEqualTo("UNSUBSCRIBED");
    }

    @Test
    void invalidEmailIsRejected() {
        HttpResponse<String> r = post("/v1/newsletter/subscriptions", "{\"email\":\"ada@example\"}");
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(json(r).get("fieldErrors").has("email")).isTrue();
    }

    private String status(String email) {
        return jdbc.sql("select status from newsletter_subscription where email = :e").param("e", email)
                .query(String.class).single();
    }

    private JsonNode event(String email) {
        return JSON.readTree(jdbc.sql("""
                select payload::text from platform_outbox where type = :t and payload->>'email' = :e
                order by occurred_at desc limit 1
                """).param("t", ContentEvents.NEWSLETTER_CONFIRMATION_REQUESTED).param("e", email)
                .query(String.class).single());
    }

    private long count(String type, String email) {
        return jdbc.sql("select count(*) from platform_outbox where type = :t and payload->>'email' = :e")
                .param("t", type).param("e", email).query(Long.class).single();
    }

    private static String token(String url) {
        String query = URI.create(url).getRawQuery();
        return URLDecoder.decode(query.substring(query.indexOf("token=") + 6), StandardCharsets.UTF_8);
    }
}
