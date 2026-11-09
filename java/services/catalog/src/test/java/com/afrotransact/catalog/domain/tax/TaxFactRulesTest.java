package com.afrotransact.catalog.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import org.junit.jupiter.api.Test;

class TaxFactRulesTest {

    private static final Map<String, Object> NOT_CANDY = Map.of("op", "isFalse", "fact", "is_candy");
    private static final Map<String, Object> HEATED = Map.of("op", "isTrue", "fact", "sold_heated");

    private static final List<AttributeDefinition> DEFS = List.of(
            AttributeDefinition.taxFact("is_candy", "Candy or confectionery", AttributeKind.BOOLEAN, null, "VARIANT",
                    "Yes if candy.", List.of(), Map.of(), true, null, false),
            AttributeDefinition.taxFact("net_weight_oz", "Net weight (oz)", AttributeKind.DECIMAL, "oz", "VARIANT",
                    "The net weight of one package.", List.of(), Map.of(), true, NOT_CANDY, false),
            AttributeDefinition.taxFact("servings_per_package", "Servings per package", AttributeKind.INTEGER, null,
                    "VARIANT", "Servings on the label.", List.of(), Map.of(), true, NOT_CANDY, false),
            AttributeDefinition.taxFact("juice_percent", "Juice content", AttributeKind.PERCENT, "%", "VARIANT",
                    "Percent juice.", List.of(), Map.of(), false, null, false),
            AttributeDefinition.taxFact("label_type", "Label on the package", AttributeKind.ENUM, null, "VARIANT",
                    "The panel.", List.of("DRUG_FACTS", "NONE"), Map.of("DRUG_FACTS", "Drug Facts", "NONE", "No such label"),
                    true, null, false),
            AttributeDefinition.taxFact("label_evidence", "Photo of the label", AttributeKind.FILE, null, "VARIANT",
                    "Upload the label.", List.of(), Map.of(), true,
                    Map.of("op", "eq", "fact", "label_type", "value", "DRUG_FACTS"), true),
            AttributeDefinition.taxFact("sold_heated", "Sold hot", AttributeKind.BOOLEAN, null, "OFFER", "Yes if hot.",
                    List.of(), Map.of(), true, null, false),
            AttributeDefinition.taxFact("heated_by_seller", "You heat it", AttributeKind.BOOLEAN, null, "OFFER",
                    "Yes if you heat it.", List.of(), Map.of(), true, HEATED, false));

    private static Map<String, Object> answers(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aCompleteSetOfAnswersIsValidAndTyped() {
        var r = TaxFactRules.check(DEFS, answers("is_candy", "no", "net_weight_oz", "8.50", "servings_per_package", "3",
                "label_type", "NONE", "sold_heated", false));
        assertThat(r.valid()).isTrue();
        assertThat(r.facts()).containsEntry("is_candy", false).containsEntry("servings_per_package", 3L)
                .doesNotContainKey("heated_by_seller");
        assertThat(((Number) r.facts().get("net_weight_oz")).doubleValue()).isEqualTo(8.5);
    }

    @Test
    void anAnswerThatIsHiddenByShowWhenIsDroppedAndNotRequired() {
        var r = TaxFactRules.check(DEFS, answers("is_candy", true, "net_weight_oz", 8, "servings_per_package", 2,
                "label_type", "NONE", "sold_heated", false));
        assertThat(r.valid()).isTrue();
        assertThat(r.facts()).doesNotContainKeys("net_weight_oz", "servings_per_package");
    }

    @Test
    void missingAnswersAreListedInFormOrderWithPlainMessages() {
        var r = TaxFactRules.check(DEFS, answers("is_candy", false, "sold_heated", true));
        assertThat(r.missing()).extracting(MissingTaxFact::factId).containsExactly("net_weight_oz",
                "servings_per_package", "label_type", "heated_by_seller");
        assertThat(r.missing().getFirst().message()).isEqualTo("Net weight (oz) is needed. The net weight of one package.");
    }

    @Test
    void anEvidenceFileIsRequiredOnlyWhenItsConditionHolds() {
        assertThat(TaxFactRules.check(DEFS, answers("is_candy", true, "label_type", "NONE", "sold_heated", false))
                .missing()).isEmpty();
        var r = TaxFactRules.check(DEFS, answers("is_candy", true, "label_type", "DRUG_FACTS", "sold_heated", false));
        assertThat(r.missing()).extracting(MissingTaxFact::factId).containsExactly("label_evidence");
        assertThat(r.missing().getFirst().message()).startsWith("Please upload: Photo of the label.");
        assertThat(r.missingAnswers(Set.of("label_evidence"))).isEmpty();
        var ok = TaxFactRules.check(DEFS, answers("is_candy", true, "label_type", "DRUG_FACTS", "sold_heated", false,
                "label_evidence", "7b1e"));
        assertThat(ok.valid()).isTrue();
    }

    @Test
    void wrongTypesAndRangesAreRefusedOnTheFact() {
        var r = TaxFactRules.check(DEFS, answers("is_candy", false, "net_weight_oz", -1, "servings_per_package", 1.5,
                "juice_percent", 120, "label_type", "OTHER", "sold_heated", 3));
        assertThat(r.problems()).containsKeys("net_weight_oz", "servings_per_package", "juice_percent", "label_type",
                "sold_heated");
        assertThat(TaxFactRules.check(DEFS, answers("is_candy", "maybe")).problems()).containsKey("is_candy");
        assertThat(r.problems().get("juice_percent")).contains("0 to 100");
        assertThat(r.problems().get("servings_per_package")).contains("whole number");
        assertThat(r.problems().get("label_type")).contains("choose one");
    }

    @Test
    void zeroAndHugeNumbersAreNotAWeight() {
        assertThat(TaxFactRules.check(DEFS, answers("is_candy", false, "net_weight_oz", 0)).problems())
                .containsKey("net_weight_oz");
        assertThat(TaxFactRules.check(DEFS, answers("is_candy", false, "net_weight_oz", "abc")).problems().get("net_weight_oz"))
                .contains("in oz");
    }

    @Test
    void answersSplitByOwner() {
        var r = TaxFactRules.check(DEFS, answers("is_candy", true, "label_type", "NONE", "sold_heated", true,
                "heated_by_seller", false));
        List<Map<String, Object>> split = TaxFactRules.split(DEFS, r.facts());
        assertThat(split.get(0)).containsOnlyKeys("is_candy", "label_type");
        assertThat(split.get(1)).containsOnlyKeys("sold_heated", "heated_by_seller");
    }
}
