package com.afrotransact.platform.web.diversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DiversionDetectorTest {

    private final DiversionDetector detector = DiversionDetector.standard();

    @Test
    void goldenVectorsAreTheSameAsTheGoDetector() throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(
                Files.readString(Path.of("../../../contracts/diversion/vectors.json")));
        JsonNode vectors = root.get("vectors");
        assertThat(vectors.size()).isGreaterThanOrEqualTo(100);
        for (JsonNode v : vectors) {
            String text = v.get("text").asString();
            boolean strict = v.get("strict").asBoolean();
            List<DiversionFinding> got = detector.scan(text, strict);
            if (v.get("reject").asBoolean()) {
                String kind = v.get("kind").asString();
                assertThat(got).as("'%s' strict=%s", text, strict).extracting(f -> f.kind().id()).contains(kind);
            } else {
                assertThat(got).as("'%s' strict=%s", text, strict).isEmpty();
            }
        }
    }

    @Test
    void messageNamesWhatToRemove() {
        String m = detector.message(detector.scan("Call 713-555-1234 or visit mystore.com", false));
        assertThat(m).contains("web address", "phone number", "AfroTransact messages");
        assertThat(detector.message(List.of())).isEmpty();
    }
}
