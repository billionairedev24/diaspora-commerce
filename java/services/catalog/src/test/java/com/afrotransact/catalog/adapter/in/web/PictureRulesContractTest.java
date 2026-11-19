package com.afrotransact.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.afrotransact.catalog.adapter.in.web.PictureRulesDtos.UpdateRequest;
import com.afrotransact.catalog.adapter.in.web.PictureRulesDtos.ViewDto;
import com.afrotransact.catalog.application.PictureRulesService;
import com.afrotransact.catalog.domain.pictures.PictureProfile;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.catalog.domain.pictures.PictureRulesChange;
import com.afrotransact.catalog.testsupport.OpenApiContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** contracts/openapi/catalog.yaml describes the picture-rules routes and shapes exactly as the controller serves them. */
class PictureRulesContractTest {

    private static final OpenApiContract CONTRACT = OpenApiContract.of("catalog");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void everyRouteIsInTheContract() {
        assertThat(CONTRACT.routes()).containsAll(new TreeSet<>(OpenApiContract.routesOf(PictureRulesController.class)));
    }

    @Test
    void theWireRulesCarryExactlyWhatTheContractDescribes() {
        for (PictureProfile p : PictureProfile.values()) {
            JsonNode json = JSON.valueToTree(PictureRulesDtos.RulesDto.of(PictureRules.defaults(p)));
            Set<String> sent = OpenApiContract.keys(json);
            assertThat(sent).isSubsetOf(CONTRACT.properties("PictureRules"));
            assertThat(sent).containsAll(CONTRACT.required("PictureRules"));
            assertThat(OpenApiContract.keys(json.get("knobs"))).isEqualTo(CONTRACT.properties("PictureKnobs"));
        }
    }

    @Test
    void theStaffViewCarriesExactlyWhatTheContractDescribes() {
        PictureRules saved = PictureRules.strict().savedBy("user_1", NOW, 2);
        PictureRulesService.View view = new PictureRulesService.View(saved, true,
                List.of(new PictureRulesChange(2, PictureProfile.TESTING, PictureProfile.STRICT, List.of(), "user_1", NOW,
                        "Product asked for the standard")),
                List.of(PictureRules.strict(), PictureRules.defaults(PictureProfile.STARTUP),
                        PictureRules.defaults(PictureProfile.TESTING)), PictureProfile.STARTUP);
        JsonNode json = JSON.valueToTree(ViewDto.of(view));
        assertThat(OpenApiContract.keys(json)).isSubsetOf(CONTRACT.properties("PictureRulesView"));
        assertThat(OpenApiContract.keys(json)).containsAll(CONTRACT.required("PictureRulesView"));
        assertThat(OpenApiContract.keys(json.get("history").get(0))).isSubsetOf(CONTRACT.properties("PictureRulesChange"));
        assertThat(OpenApiContract.keys(json.get("overrides"))).isSubsetOf(CONTRACT.properties("PictureOverrides"));
    }

    @Test
    void theUpdateRequestAcceptsEveryContractField() {
        Set<String> accepted = new TreeSet<>();
        for (var component : UpdateRequest.class.getRecordComponents()) {
            accepted.add(component.getName());
        }
        assertThat(accepted).isEqualTo(CONTRACT.properties("PictureRulesUpdate"));
    }
}
