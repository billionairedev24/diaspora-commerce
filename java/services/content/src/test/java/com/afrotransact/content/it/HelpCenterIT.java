package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.afrotransact.content.application.ContentPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Published help pages with topics and cursor paging; site-content staff edit them with a version check. */
class HelpCenterIT extends ContentIntegrationTest {

    @Test
    void publishedArticlesPageByCursorAndFilterByTopicAndText() {
        TestActors.anonymous();
        JsonNode first = json(get("/v1/help/articles?limit=2"));
        assertThat(first.get("items").size()).isEqualTo(2);
        assertThat(first.get("items").toString()).doesNotContain("\"help-center\"");
        assertThat(first.get("topics").toString()).contains("ORDERS_DELIVERY", "Selling & advertising");
        String cursor = first.get("nextCursor").asString();
        JsonNode second = json(get("/v1/help/articles?limit=2&cursor=" + cursor));
        assertThat(second.get("items").get(0).get("slug").asString())
                .isNotEqualTo(first.get("items").get(0).get("slug").asString());

        JsonNode selling = json(get("/v1/help/articles?topic=SELLING"));
        assertThat(selling.get("items").toString()).contains("advertise", "fulfilled-by-afrotransact").doesNotContain("press");
        assertThat(selling.path("nextCursor").isNull() || selling.path("nextCursor").isMissingNode()).isTrue();
        assertThat(json(get("/v1/help/articles?q=locker")).get("items").toString()).contains("shipping");
        assertThat(get("/v1/help/articles?cursor=nope!").statusCode()).isEqualTo(422);

        JsonNode hub = json(get("/v1/help/articles/help-center"));
        assertThat(hub.get("title").asString()).isEqualTo("Help center");
        assertThat(hub.get("sections").size()).isEqualTo(3);
        assertThat(get("/v1/help/articles/nope").statusCode()).isEqualTo(404);
        TestActors.reset();
    }

    @Test
    void siteContentEditorsChangeCopyWithAVersionCheck() {
        String draft = """
                {"topic":"ORDERS_DELIVERY","title":"Track a package","summary":"Where is my order?",
                 "sections":[{"heading":"Live tracking","body":"Open [Your orders](/orders)."}],"status":"DRAFT"}
                """;
        TestActors.anonymous();
        assertThat(put("/v1/help/articles/track-a-package", draft).statusCode()).isEqualTo(403);
        TestActors.as(TestActors.staff("marketer", ContentPermissions.SITE_EDIT));
        HttpResponse<String> created = put("/v1/help/articles/track-a-package", draft);
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(json(created).get("version").asLong()).isZero();
        assertThat(get("/v1/help/articles/track-a-package").statusCode()).isEqualTo(404);

        String publish = draft.replace("\"DRAFT\"", "\"PUBLISHED\",\"expectedVersion\":0");
        assertThat(json(put("/v1/help/articles/track-a-package", publish)).get("version").asLong()).isEqualTo(1);
        assertThat(put("/v1/help/articles/track-a-package", publish).statusCode()).isEqualTo(409);
        assertThat(json(get("/v1/help/articles/track-a-package")).get("sections").get(0).get("heading").asString())
                .isEqualTo("Live tracking");
        assertThat(put("/v1/help/articles/track-a-package", draft.replace("\"sections\":[{\"heading\":\"Live tracking\",\"body\":\"Open [Your orders](/orders).\"}]",
                "\"sections\":[]")).statusCode()).isIn(409, 422);
        TestActors.as(TestActors.staff("support", ContentPermissions.SITE_VIEW));
        assertThat(json(get("/v1/help/admin/articles")).toString()).contains("track-a-package", "help-center");
        TestActors.reset();
    }
}
