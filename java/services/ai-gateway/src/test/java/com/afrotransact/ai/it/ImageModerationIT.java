package com.afrotransact.ai.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** POST /v1/ai/moderate-image: the media-service hook (vision model behind WireMock, never a real provider). */
class ImageModerationIT extends AiGatewayIntegrationTest {

    /** A JPEG header plus a unique marker so stubs never collide between tests. */
    private static String jpeg(String marker) {
        byte[] head = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] tail = marker.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return Base64.getEncoder().encodeToString(all);
    }

    @Test
    void sendsTheImageInlineToTheVisionRouteAndAppliesThePolicy() throws Exception {
        TestActors.as(TestActors.seller(unique("svc-media"), "ai:use"));
        String image = jpeg(unique("dress"));
        WireMock.stubFor(WireMock.post("/v1/chat/completions")
                .withRequestBody(WireMock.containing(image.substring(0, 12)))
                .withRequestBody(WireMock.containing("\"model\":\"test/vision\""))
                .willReturn(WireMock.okJson(OpenRouterStubs.completion("test/vision",
                        "{\"decision\":\"ALLOW\",\"labels\":[{\"code\":\"watermark\",\"description\":\"Shop logo\"}]}"))));

        HttpResponse<String> response = postJson("/v1/ai/moderate-image",
                Map.of("image", image, "contentType", "image/jpeg", "purpose", "product-image"), "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = readJson(response);
        // The model said ALLOW but named a watermark: the policy holds it for review.
        assertThat(body.path("decision").asString()).isEqualTo("REVIEW");
        assertThat(body.path("labels").get(0).path("code").asString()).isEqualTo("WATERMARK");
        assertThat(body.path("model").asString()).isEqualTo("test/vision");
        WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/chat/completions"))
                .withRequestBody(WireMock.containing("data:image/jpeg;base64," + image.substring(0, 12))));
        Integer ledger = jdbc.sql("select count(*) from ai_usage where feature = 'image-moderation'")
                .query(Integer.class).single();
        assertThat(ledger).isPositive();
    }

    @Test
    void unreadableAnswerIsReviewNotAllow() throws Exception {
        TestActors.as(TestActors.seller(unique("svc-media"), "ai:use"));
        String image = jpeg(unique("bag"));
        WireMock.stubFor(WireMock.post("/v1/chat/completions")
                .withRequestBody(WireMock.containing(image.substring(0, 12)))
                .willReturn(WireMock.okJson(OpenRouterStubs.completion("test/vision", "Looks lovely!"))));

        HttpResponse<String> response = postJson("/v1/ai/moderate-image",
                Map.of("image", image, "contentType", "image/jpeg"), "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(readJson(response).path("decision").asString()).isEqualTo("REVIEW");
    }

    @Test
    void rejectsBadInputAndCallersWithoutThePermission() throws Exception {
        TestActors.as(TestActors.seller(unique("svc-media"), "ai:use"));
        HttpResponse<String> notJpeg = postJson("/v1/ai/moderate-image", Map.of("image",
                Base64.getEncoder().encodeToString("<html>".getBytes(StandardCharsets.UTF_8)),
                "contentType", "image/jpeg"), "application/json");
        assertThat(notJpeg.statusCode()).isEqualTo(422);
        assertThat(readJson(notJpeg).path("code").asString()).isEqualTo("ai.unsupported-image");

        HttpResponse<String> gif = postJson("/v1/ai/moderate-image", Map.of("image", jpeg("x"),
                "contentType", "image/gif"), "application/json");
        assertThat(gif.statusCode()).isEqualTo(422);

        TestActors.as(TestActors.customer(unique("shopper")));
        HttpResponse<String> forbidden = postJson("/v1/ai/moderate-image", Map.of("image", jpeg("y"),
                "contentType", "image/jpeg"), "application/json");
        assertThat(forbidden.statusCode()).isEqualTo(403);
    }
}
