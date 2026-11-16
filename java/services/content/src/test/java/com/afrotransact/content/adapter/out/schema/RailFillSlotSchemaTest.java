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

/** The V22 migration: the rail_fill slot exists, its payload schema accepts sponsored and editorial tiles, and the
    migration creates no placement (demo content only ever comes from the local seed). */
class RailFillSlotSchemaTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V22__rail_fill_slot.sql");

    private static String sql() throws IOException {
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    private static String schema() throws IOException {
        Matcher m = Pattern.compile("\\('rail_fill',.*?'(\\{\"\\$schema\".*?\\})'::jsonb, \\d+\\)", Pattern.DOTALL).matcher(sql());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private static NetworkntPayloadSchemaValidator validator() {
        ContentProperties props = new ContentProperties(null, null, null,
                new ContentProperties.Placements(null, null, null, null, List.of()), null, null, null, null);
        return new NetworkntPayloadSchemaValidator(JsonMapper.builder().build(), props);
    }

    @Test
    void slotKeyMatchesTheMigrationRow() throws IOException {
        assertThat(SlotKey.fromKey("rail_fill")).isEqualTo(SlotKey.RAIL_FILL);
        assertThat(SlotKey.RAIL_FILL.key()).isEqualTo("rail_fill");
        assertThat(sql()).contains("'" + SlotKey.RAIL_FILL.key() + "'");
    }

    @Test
    void migrationSeedsNoPlacement() throws IOException {
        assertThat(sql().toLowerCase()).doesNotContain("insert into placement");
    }

    @Test
    void sponsoredAndEditorialTilesValidate() throws IOException {
        NetworkntPayloadSchemaValidator v = validator();
        assertThat(v.validate(schema(), """
                {"advertiser": "Acme Foods", "headline": "Jollof week", "text": "Rice, tomatoes and spice.", "cta": "Shop",
                 "href": "/s?q=jollof", "imageUrl": "https://media.example/a.webp", "departments": ["Groceries"], "skus": ["AT1"]}""")).isEmpty();
        assertThat(v.validate(schema(), "{\"headline\": \"Back to school\", \"cta\": \"See\", \"href\": \"/s?q=school\"}")).isEmpty();
    }

    @Test
    void aTileNeedsHeadlineButtonAndLinkAndAtMostThreeProducts() throws IOException {
        NetworkntPayloadSchemaValidator v = validator();
        assertThat(v.validate(schema(), "{\"cta\": \"See\", \"href\": \"/x\"}")).isNotEmpty();
        assertThat(v.validate(schema(), "{\"headline\": \"H\", \"href\": \"/x\"}")).isNotEmpty();
        assertThat(v.validate(schema(), "{\"headline\": \"H\", \"cta\": \"See\", \"href\": \"javascript:alert(1)\"}")).isNotEmpty();
        assertThat(v.validate(schema(), "{\"headline\": \"H\", \"cta\": \"See\", \"href\": \"/x\", \"skus\": [\"1\",\"2\",\"3\",\"4\"]}")).isNotEmpty();
        assertThat(v.validate(schema(), "{\"headline\": \"H\", \"cta\": \"See\", \"href\": \"/x\", \"imageUrl\": \"http://x/a.png\"}")).isNotEmpty();
    }
}
