package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.afrotransact.content.application.ContentEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Legal center pages, version raising with archiving, counsel sign-off and cookie consent records. */
class LegalIT extends ContentIntegrationTest {

    @Test
    void smsAndPrivacyWordingIsServedWithContactsFilledIn() {
        assertThat(json(get("/v1/legal")).get("documents").size()).isEqualTo(21);
        String program = json(get("/v1/site-config")).get("smsProgramName").asString();
        JsonNode sms = json(get("/v1/legal/sms-terms"));
        assertThat(sms.get("draftBanner").asBoolean()).isTrue();
        assertThat(sms.get("bindingLocale").asString()).isEqualTo("en");
        assertThat(sms.get("body").asString()).contains(program + " is a text-message program")
                .contains("Reply STOP").contains("HELP").contains("Message and data rates may apply")
                .contains("Carriers are not liable").contains("+1 (833) 555-0199").doesNotContain("{phone}");
        assertThat(sms.get("outline").toString()).contains("opting-out-and-help");
        JsonNode privacy = json(get("/v1/legal/privacy?locale=fr"));
        assertThat(privacy.get("translationFallback").asBoolean()).isTrue();
        assertThat(privacy.get("locale").asString()).isEqualTo("en");
        assertThat(privacy.get("body").asString())
                .contains("No mobile information will be shared with third parties or affiliates for marketing");
        assertThat(json(get("/v1/legal/conditions-of-use")).get("versions").size()).isEqualTo(3);
        assertThat(get("/v1/legal/unknown").statusCode()).isEqualTo(404);
    }

    @Test
    void raisingAVersionArchivesThePreviousOne() {
        HttpResponse<String> raised = post("/v1/legal/returns/versions", """
                {"version":"2.1","effectiveDate":"2026-11-01","changeSummary":"Longer window",
                 "bodies":{"en":"# Returns & Refunds\\n\\n## Return window\\n\\n45 days. Questions: {legal}"}}
                """);
        assertThat(raised.statusCode()).isEqualTo(201);
        assertThat(json(raised).get("draftBanner").asBoolean()).isTrue();

        JsonNode current = json(get("/v1/legal/returns"));
        assertThat(current.get("version").asString()).isEqualTo("2.1");
        assertThat(current.get("body").asString()).contains("legal@afrotransact.com");
        assertThat(current.get("versions").toString()).contains("\"version\":\"2.0\",\"effectiveDate\":\"2026-06-01\","
                + "\"status\":\"ARCHIVED\"");
        JsonNode archived = json(get("/v1/legal/returns/versions/2.0"));
        assertThat(archived.get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(archived.get("body").asString()).contains("Most items can be returned within 30 days");
        assertThat(events(ContentEvents.LEGAL_VERSION_PUBLISHED, "returns")).isEqualTo(1);

        HttpResponse<String> lower = post("/v1/legal/returns/versions",
                "{\"version\":\"2.0.5\",\"effectiveDate\":\"2026-12-01\",\"bodies\":{\"en\":\"x\"}}");
        assertThat(lower.statusCode()).isEqualTo(422);

        JsonNode signedOff = json(patch("/v1/legal/returns/current", "{\"draftBanner\":false}"));
        assertThat(signedOff.get("draftBanner").asBoolean()).isFalse();
        assertThat(json(get("/v1/site-config")).get("legal").toString())
                .contains("\"key\":\"returns\",\"title\":\"Returns & Refunds\",\"version\":\"2.1\"");
    }

    @Test
    void cookieConsentIsStoredWithGpcRespected() {
        JsonNode policy = json(get("/v1/consent/cookie-policy"));
        assertThat(policy.get("categories").size()).isEqualTo(4);
        assertThat(policy.get("cookies").size()).isEqualTo(10);
        assertThat(policy.get("noticeVersion").asString()).isEqualTo("2.1");

        HttpResponse<String> recorded = post("/v1/consent/records",
                "{\"subject\":\"device-1234567\",\"categories\":{\"analytics\":true,\"advertising\":true},\"source\":\"banner\"}",
                "Sec-GPC", "1");
        assertThat(recorded.statusCode()).isEqualTo(201);
        JsonNode r = json(recorded);
        assertThat(r.get("gpc").asBoolean()).isTrue();
        assertThat(r.get("categories").get("necessary").asBoolean()).isTrue();
        assertThat(r.get("categories").get("analytics").asBoolean()).isTrue();
        assertThat(r.get("categories").get("advertising").asBoolean()).isFalse();
        assertThat(r.get("policyVersion").asString()).isEqualTo("2.1");
        assertThat(jdbc.sql("select count(*) from consent_record where subject = 'device-1234567'")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(r.get("saleOptOut").asBoolean()).isTrue();
        assertThat(post("/v1/consent/records", "{\"subject\":\"device-1234567\",\"categories\":{\"tracking\":true}}")
                .statusCode()).isEqualTo(422);

        JsonNode optedOut = json(post("/v1/consent/records", "{\"subject\":\"device-7654321\",\"categories\":"
                + "{\"functional\":true,\"advertising\":true},\"saleOptOut\":true,\"source\":\"privacy-choices\"}"));
        assertThat(optedOut.get("gpc").asBoolean()).isFalse();
        assertThat(optedOut.get("saleOptOut").asBoolean()).isTrue();
        assertThat(optedOut.get("categories").get("functional").asBoolean()).isTrue();
        assertThat(optedOut.get("categories").get("advertising").asBoolean()).isFalse();
        assertThat(jdbc.sql("select sale_opt_out from consent_record where subject = 'device-7654321'")
                .query(Boolean.class).single()).isTrue();
    }
}
