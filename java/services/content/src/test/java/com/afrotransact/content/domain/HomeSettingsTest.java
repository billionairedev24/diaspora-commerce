package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.home.CommunityEntry;
import com.afrotransact.content.domain.home.HomeCaps;
import com.afrotransact.content.domain.home.HomeSettings;
import com.afrotransact.content.domain.home.HomeSettingsDiff;
import com.afrotransact.content.domain.home.ModuleRule;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HomeSettingsTest {

    @Test
    void defaultsAreTheDocumentedThresholds() {
        HomeSettings d = HomeSettings.defaults();
        ModuleRule communities = d.modules().get("communities");
        assertThat(d.version()).isZero();
        assertThat(communities.minUnits()).as("communities needed").isEqualTo(3);
        assertThat(communities.unitMinProducts()).as("products per community").isEqualTo(8);
        assertThat(communities.unitMinSellers()).as("sellers per community").isEqualTo(2);
        assertThat(d.caps().sellerShare()).isEqualTo(2);
        assertThat(d.caps().sponsoredPerOrganic()).isEqualTo(3);
        assertThat(d.caps().poolMultiplier()).isEqualTo(3);
        assertThat(d.caps().visitWindowMinutes()).isEqualTo(30);
        assertThat(d.caps().maxModules()).isEqualTo(12);
        assertThat(d.modules().get("services-near-you").minProducts()).as("any provider").isEqualTo(1);
        assertThat(d.dynamic()).as("follows the environment default").isNull();
        assertThat(d.problems()).isEmpty();
    }

    @Test
    void rejectsNegativeWeightOutsideRangeUnknownModuleAndBadCaps() {
        Map<String, ModuleRule> modules = new HashMap<>(HomeSettings.defaultModules());
        ModuleRule deals = modules.get("deals");
        modules.put("deals", new ModuleRule(true, -1, 0, 0, 0, 0, 0, 101, 8, "discovery"));
        modules.put("nonsense", deals);
        HomeSettings bad = new HomeSettings(0, "sometimes", modules, new HomeCaps(2, 3, 0, 3, 3, 3, 1.5, 30, 2, 10, 7),
                Map.of("groceries-food", Map.of("fr", "Épicerie")), null, null);
        Map<String, String> problems = bad.problems();
        assertThat(problems).containsKeys("dynamic", "modules.deals.minProducts", "modules.deals.weight",
                "modules.nonsense", "caps.maxModules", "caps.noveltyPenalty", "departmentLabels.groceries-food");
        assertThrows(ContentRuleViolation.class, bad::validate);
    }

    @Test
    void diffNamesEveryChangedField() {
        HomeSettings before = HomeSettings.defaults();
        Map<String, ModuleRule> modules = new HashMap<>(before.modules());
        modules.put("communities", new ModuleRule(true, 0, 0, 4, 10, 2, 0, 40, 6, "discovery"));
        HomeSettings after = new HomeSettings(0, "on", modules, new HomeCaps(12, 3, 3, 3, 3, 3, 0.6, 30, 2, 10, 7),
                before.departmentLabels(), null, null);
        List<String> fields = HomeSettingsDiff.between(before, after).stream().map(HomeSettingsDiff.Change::field).toList();
        assertThat(fields).containsExactlyInAnyOrder("dynamic", "modules.communities.minUnits",
                "modules.communities.unitMinProducts", "caps.sellerShare");
        assertThat(HomeSettingsDiff.between(before, before)).isEmpty();
    }

    @Test
    void missingModuleRowsAreFilledFromTheDefaults() {
        HomeSettings partial = new HomeSettings(3, "on", Map.of("deals", new ModuleRule(false, 6, 2, 0, 0, 0, 0, 85, 8, "discovery")),
                HomeCaps.defaults(), Map.of(), null, null);
        HomeSettings filled = partial.withDefaultsFilled();
        assertThat(filled.modules()).containsKeys(HomeSettings.moduleKeys().toArray(String[]::new));
        assertThat(filled.modules().get("deals").enabled()).isFalse();
    }

    @Test
    void communityKeysNormaliseCaseAndSeparators() {
        assertThat(CommunityEntry.normalise(" Nigerian ")).isEqualTo("nigerian");
        assertThat(CommunityEntry.normalise("Sierra  Leonean")).isEqualTo("sierra-leonean");
        assertThat(CommunityEntry.normalise("  ")).isEmpty();
    }

    @Test
    void communityEntryNeedsAnEnglishLabelAndHttpsImage() {
        CommunityEntry ok = new CommunityEntry("nigerian", Map.of("en", "Nigerian", "fr", "Nigérian"), List.of("naija"),
                "https://cdn.example/n.jpg", null);
        ok.validate();
        CommunityEntry bad = new CommunityEntry("Nigerian!", Map.of("fr", "Nigérian"), List.of(" "), "http://x/y.jpg", null);
        assertThrows(ContentRuleViolation.class, bad::validate);
    }

    /** contracts/settings/home-defaults.json is the shared statement of the defaults; the gateway asserts the same file. */
    @Test
    void defaultsEqualTheSharedContractFile() throws Exception {
        Path file = Path.of("../../../contracts/settings/home-defaults.json");
        if (!Files.exists(file)) {
            return; // run from another working directory
        }
        JsonNode shared = JsonMapper.builder().build().readTree(Files.readString(file));
        JsonNode mine = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(HomeSettings.defaults()));
        assertThat(mine.get("modules")).isEqualTo(shared.get("modules"));
        assertThat(mine.get("caps")).isEqualTo(shared.get("caps"));
    }
}
