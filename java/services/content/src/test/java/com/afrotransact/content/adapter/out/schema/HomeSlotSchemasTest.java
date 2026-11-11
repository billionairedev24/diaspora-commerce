package com.afrotransact.content.adapter.out.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.content.config.ContentProperties;
import com.afrotransact.content.domain.placement.SlotKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** The V18 migration: every new slot key has a row, and the paid slots' schemas accept good payloads and refuse bad ones. */
class HomeSlotSchemasTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V18__home_ads_and_blocks.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static String schemaOf(String sql, String slotKey) {
        Matcher m = Pattern.compile("\\('" + slotKey + "',.*?'(\\{\"\\$schema\".*?\\})'::jsonb, \\d+\\)", Pattern.DOTALL)
                .matcher(sql);
        assertThat(m.find()).as("schema of " + slotKey).isTrue();
        return m.group(1);
    }

    private static NetworkntPayloadSchemaValidator validator() {
        ContentProperties props = new ContentProperties(null, null, null,
                new ContentProperties.Placements(null, null, null, null, List.of()), null, null, null, null);
        return new NetworkntPayloadSchemaValidator(JsonMapper.builder().build(), props);
    }

    @Test
    void everyNewSlotKeyIsInsertedByTheMigration() throws IOException {
        String sql = sql();
        for (SlotKey key : List.of(SlotKey.HOME_BRAND_BANNER, SlotKey.HOME_SPONSORED_PRODUCTS, SlotKey.HOME_AD_ROW,
                SlotKey.HOME_ADVERTORIAL, SlotKey.HOME_BLOCK)) {
            assertThat(sql).contains("'" + key.key() + "'");
        }
    }

    @Test
    void brandBannerNeedsAdvertiserHeadlineAndLinkAndCapsTheProductStrip() throws IOException {
        String schema = schemaOf(sql(), "home_brand_banner");
        NetworkntPayloadSchemaValidator v = validator();
        assertThat(v.validate(schema, """
                {"advertiser": "Acme", "headline": "Jollof week", "cta": "Shop", "href": "/s?q=jollof",
                 "imageUrl": "https://media.example/a.webp", "skus": ["AT1", "AT2", "AT3"], "states": ["TX", "GA"]}""")).isEmpty();
        assertThat(v.validate(schema, "{\"headline\": \"No advertiser\", \"cta\": \"Shop\", \"href\": \"/x\"}")).isNotEmpty();
        assertThat(v.validate(schema, """
                {"advertiser": "Acme", "headline": "H", "cta": "Shop", "href": "/x", "skus": ["1", "2", "3", "4"]}""")).isNotEmpty();
        assertThat(v.validate(schema, """
                {"advertiser": "Acme", "headline": "H", "cta": "Shop", "href": "http://evil", "states": ["Texas"]}""")).hasSize(2);
    }

    @Test
    void adRowTakesTwoOrThreeAdsWithAltText() throws IOException {
        String schema = schemaOf(sql(), "home_ad_row");
        NetworkntPayloadSchemaValidator v = validator();
        String ad = "{\"imageUrl\": \"https://media.example/%d.webp\", \"alt\": \"Ad %d\", \"href\": \"/x\"}";
        assertThat(v.validate(schema, "{\"ads\": [" + ad.formatted(1, 1) + "," + ad.formatted(2, 2) + "]}")).isEmpty();
        assertThat(v.validate(schema, "{\"ads\": [" + ad.formatted(1, 1) + "]}")).isNotEmpty();
        assertThat(v.validate(schema, "{\"ads\": [" + ad.formatted(1, 1) + "," + ad.formatted(2, 2) + "," + ad.formatted(3, 3) + ","
                + ad.formatted(4, 4) + "]}")).isNotEmpty();
        assertThat(v.validate(schema, "{\"ads\": [{\"imageUrl\": \"https://m/1.webp\", \"href\": \"/x\"}, " + ad.formatted(2, 2)
                + "]}")).isNotEmpty();
    }

    @Test
    void advertorialAndSponsoredProductsRequireTheirLabelsAndText() throws IOException {
        NetworkntPayloadSchemaValidator v = validator();
        String advertorial = schemaOf(sql(), "home_advertorial");
        assertThat(v.validate(advertorial, """
                {"advertiser": "Acme", "headline": "H", "text": "Two lines.", "href": "https://acme.example/story"}""")).isEmpty();
        assertThat(v.validate(advertorial, "{\"advertiser\": \"Acme\", \"headline\": \"H\", \"href\": \"/x\"}")).isNotEmpty();
        String sponsored = schemaOf(sql(), "home_sponsored_products");
        assertThat(v.validate(sponsored, "{\"title\": \"Promoted\", \"query\": \"rice\", \"department\": \"Groceries\"}")).isEmpty();
        assertThat(v.validate(sponsored, "{\"query\": \"rice\"}")).isNotEmpty();
    }
}
