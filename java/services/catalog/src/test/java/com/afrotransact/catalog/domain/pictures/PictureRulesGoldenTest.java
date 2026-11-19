package com.afrotransact.catalog.domain.pictures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.matching.RealPhoto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The golden vectors in contracts/picture-rules/vectors.json are run by Go (go/pkg/picturerules) and by this test, so the
 * two implementations of the profiles, knobs and the real-photo test cannot drift.
 */
class PictureRulesGoldenTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode VECTORS = load();

    private static JsonNode load() {
        try {
            return JSON.readTree(Files.readString(Path.of("../../../contracts/picture-rules/vectors.json")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PictureKnobs knobs(JsonNode n) {
        return new PictureKnobs(n.get("minImages").asInt(), n.get("minLongSidePx").asInt(), n.get("minShortSidePx").asInt(),
                n.get("maxMegabytes").asInt(), n.get("checkBackground").asBoolean(), n.get("checkAspect").asBoolean(),
                n.get("checkTextOverlay").asBoolean(), n.get("requireRealPhotoForStorefront").asBoolean(),
                n.get("requirePhotoForMatching").asBoolean(), n.get("allowPlaceholders").asBoolean());
    }

    @Test
    void profileKnobsMatchTheVectors() {
        for (PictureProfile p : PictureProfile.values()) {
            assertThat(PictureKnobs.of(p)).as(p.wire()).isEqualTo(knobs(VECTORS.get("profiles").get(p.wire())));
            assertThat(PictureKnobs.of(p).problems()).as(p.wire()).isEmpty();
        }
        assertThat(VECTORS.get("profiles").size()).isEqualTo(3);
    }

    @Test
    void everyUrlAgainstEveryProfile() {
        for (JsonNode c : VECTORS.get("urls")) {
            String url = c.get("url").asString();
            for (PictureProfile p : PictureProfile.values()) {
                JsonNode want = c.get("expect").get(p.wire());
                PictureRules r = PictureRules.defaults(p);
                String at = p.wire() + " " + url;
                assertThat(r.photoOk(url)).as(at + " photoOk").isEqualTo(want.get("photoOK").asBoolean());
                assertThat(r.showOnStorefront(url)).as(at + " storefront").isEqualTo(want.get("showOnStorefront").asBoolean());
                assertThat(r.matchableWithPhoto(url)).as(at + " matching").isEqualTo(want.get("matchable").asBoolean());
            }
            assertThat(RealPhoto.is(url)).as(url).isEqualTo("real".equals(c.get("class").asString()));
        }
    }

    @Test
    void overridesMatchTheVectors() {
        for (JsonNode c : VECTORS.get("overrides")) {
            JsonNode o = c.get("overrides");
            PictureOverrides overrides = new PictureOverrides(integer(o, "minImages"), integer(o, "minLongSidePx"),
                    integer(o, "minShortSidePx"), integer(o, "maxMegabytes"), bool(o, "checkBackground"), bool(o, "checkAspect"),
                    bool(o, "checkTextOverlay"), bool(o, "requireRealPhotoForStorefront"),
                    bool(o, "requirePhotoForMatching"), bool(o, "allowPlaceholders"));
            PictureRules r = PictureRules.resolve(PictureProfile.parse(c.get("profile").asString()).orElseThrow(), overrides);
            assertThat(r.knobs()).as(c.get("name").asString()).isEqualTo(knobs(c.get("knobs")));
            assertThat(r.overridden()).isEqualTo(strings(c.get("overridden")));
            assertThat(r.isStrict()).isFalse();
        }
        assertThat(PictureRules.strict().isStrict()).isTrue();
        assertThat(PictureRules.defaults(PictureProfile.STARTUP).isStrict()).isFalse();
    }

    @Test
    void invalidKnobsAreRefused() {
        for (JsonNode c : VECTORS.get("invalid")) {
            assertThat(knobs(c.get("knobs")).problems()).as(c.get("name").asString()).isNotEmpty();
        }
    }

    @Test
    void environmentDefaults() {
        for (Map.Entry<String, JsonNode> e : VECTORS.get("envDefaults").properties()) {
            assertThat(PictureProfile.forEnvironment(e.getKey()).wire()).as(e.getKey()).isEqualTo(e.getValue().asString());
        }
    }

    @Test
    void formatsIncludeWebpAndAvifButNeverVectorOrMarkup() {
        assertThat(PictureRules.FORMATS).containsExactly("image/jpeg", "image/png", "image/webp", "image/avif");
    }

    @Test
    void theSummaryStatesTheActiveRule() {
        assertThat(PictureRules.defaults(PictureProfile.STARTUP).summary()).contains("at least 1 photo")
                .contains("at least 500 px");
        assertThat(PictureRules.strict().summary()).contains("at least 1000 px").contains("plain white background");
        assertThat(PictureRules.defaults(PictureProfile.TESTING).summary()).contains("photos are optional");
    }

    @Test
    void onlyTestingLetsVerdictsPass() {
        assertThat(PictureRules.strict().verdictsBlock()).isTrue();
        assertThat(PictureRules.defaults(PictureProfile.STARTUP).verdictsBlock()).isTrue();
        assertThat(PictureRules.defaults(PictureProfile.TESTING).verdictsBlock()).isFalse();
    }

    private static Integer integer(JsonNode o, String k) {
        return o.has(k) ? o.get(k).asInt() : null;
    }

    private static Boolean bool(JsonNode o, String k) {
        return o.has(k) ? o.get(k).asBoolean() : null;
    }

    private static List<String> strings(JsonNode a) {
        return a.valueStream().map(JsonNode::asString).toList();
    }
}
