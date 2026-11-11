package com.afrotransact.catalog.adapter.out.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.FieldRule;
import com.afrotransact.catalog.domain.rules.FieldStatus;
import com.afrotransact.catalog.domain.rules.RuleBook;
import com.afrotransact.catalog.domain.rules.RuleField;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The listing rules are one source ({@code tools/listing-rules/rules.json}). These tests prove that every category
 * resolves to a kind with a status for every field, that the build copy has not drifted from the source, and that the
 * approved document, the source and the catalog taxonomy agree.
 */
class ClasspathRulesRegistryTest {

    private static final RuleBook BOOK = new ClasspathRulesRegistry().book();

    @Test
    void theMatrixHasSixteenKindsThirtyOneFieldsAndSeventyThreeApprovedCategories() throws IOException {
        assertThat(BOOK.kinds()).hasSize(16).containsKeys("PKG", "PER", "SFD", "BBY", "BEA", "HLT", "FSH", "JWL", "ELC",
                "HOM", "KID", "PET", "BOK", "DIG", "GFT", "SRV");
        assertThat(RuleField.values()).hasSize(31);
        assertThat(BOOK.approvedCodes()).hasSize(73);
        assertThat(BOOK.categories()).hasSize(74);
    }

    @Test
    void everyCategoryResolvesToAKindWithAStatusForEveryField() {
        BOOK.categories().values().forEach(c -> {
            assertThat(BOOK.kinds()).as(c.code() + " kind").containsKey(c.kindCode());
            assertThat(c.fields()).as(c.code() + " fields").hasSize(RuleField.values().length);
            for (RuleField f : RuleField.values()) {
                assertThat(c.status(f)).as(c.code() + "/" + f.id()).isNotNull();
            }
            assertThat(c.approval()).as(c.code() + " approval").isIn("open", "approval", "brand_gated",
                    "conditional_approval", "provider_application", "blocked");
        });
    }

    @Test
    void theProvisionalFabricsCategoryIsClothingAndOpenAndStaysProvisional() {
        CategoryRules fabrics = BOOK.forCategory("fabrics-textiles").orElseThrow();
        assertThat(fabrics.kindCode()).isEqualTo("FSH");
        assertThat(fabrics.approval()).isEqualTo("open");
        assertThat(fabrics.provisional()).isTrue();
        assertThat(BOOK.approvedCodes()).doesNotContain("fabrics-textiles");
    }

    @Test
    void carSeatsAreNewOnlyAndToysChooseTheCondition() {
        assertThat(BOOK.forCategory("car-seats").orElseThrow().status(RuleField.CONDITION)).isEqualTo(FieldStatus.HIDDEN);
        assertThat(BOOK.forCategory("toys-games").orElseThrow().shown(RuleField.CONDITION)).isTrue();
        assertThat(BOOK.forCategory("car-seats").orElseThrow().kindCode()).isEqualTo("KID");
    }

    @Test
    void conditionIsShownOnlyForElectronicsBooksGeneralGoodsAndToys() {
        List<String> shown = BOOK.categories().values().stream().filter(c -> c.shown(RuleField.CONDITION))
                .map(CategoryRules::kindCode).distinct().sorted().toList();
        assertThat(shown).containsExactly("BOK", "ELC", "HOM", "KID");
    }

    @Test
    void packageIsAskedForEveryPhysicalKindAndNoOther() {
        BOOK.categories().values().forEach(c -> {
            boolean physical = !List.of("DIG", "GFT", "SRV").contains(c.kindCode());
            assertThat(c.status(RuleField.PACKAGE_SIZE) == FieldStatus.REQUIRED).as(c.code()).isEqualTo(physical);
            assertThat(c.status(RuleField.PACKAGE_WEIGHT) == FieldStatus.REQUIRED).as(c.code()).isEqualTo(physical);
            assertThat(c.physical()).as(c.code()).isEqualTo(physical);
        });
    }

    @Test
    void anUnknownCategoryGetsTheGeneralGoodsRules() {
        assertThat(BOOK.forCategory("not-a-category")).isEmpty();
        CategoryRules general = BOOK.forCategoryOrGeneral("not-a-category");
        assertThat(general.kindCode()).isEqualTo("HOM");
        assertThat(general.provisional()).isTrue();
    }

    @Test
    void aTypoInTheSourceStopsTheServiceAtStart() {
        JsonNode bad = JsonMapper.builder().build().readTree("""
                {"kinds":[{"code":"X","name":"x"}],"fields":[{"id":"nope","group":"g","name":"n","byKind":{"X":"required"}}],
                 "categories":[]}""");
        assertThatThrownBy(() -> ClasspathRulesRegistry.parse(bad)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nope");
    }

    /** The build copy in catalog's resources must equal the source in tools/. */
    @Test
    void theClasspathCopyHasNotDriftedFromTheSource() throws IOException {
        Optional<Path> source = repoFile("tools/listing-rules/rules.json");
        if (source.isEmpty()) {
            return; // built outside the repository (a container build context): nothing to compare
        }
        String copy;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ClasspathRulesRegistry.RESOURCE)) {
            copy = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(copy).as("run tools/listing-rules/sync.sh").isEqualTo(Files.readString(source.get()));
    }

    /** The approved document's matrix and the source must say the same for every cell. */
    @Test
    void theApprovedDocumentMatrixMatchesTheSource() throws IOException {
        Optional<Path> doc = repoFile("docs/engineering/listing-rules.md");
        if (doc.isEmpty()) {
            return;
        }
        List<String> kinds = new ArrayList<>(BOOK.kinds().keySet());
        Map<String, String[]> rows = new LinkedHashMap<>();
        Pattern row = Pattern.compile("^\\| ([^|]+) \\|((?: [A-Za-z*-]+ \\|){16})$");
        for (String line : Files.readAllLines(doc.get())) {
            Matcher m = row.matcher(line);
            if (m.matches() && !m.group(1).startsWith("**") && !m.group(1).equals("Field")) {
                rows.put(m.group(1).strip(), m.group(2).split("\\|"));
            }
        }
        CategoryRules any = BOOK.forCategory("beans-pantry-sauces").orElseThrow();
        assertThat(rows).as("doc rows").hasSize(RuleField.values().length);
        for (FieldRule rule : any.fields()) {
            String[] cells = rows.get(rule.label());
            assertThat(cells).as("doc row " + rule.label()).isNotNull();
            for (int i = 0; i < kinds.size(); i++) {
                String kind = kinds.get(i);
                CategoryRules sample = BOOK.categories().values().stream().filter(c -> c.kindCode().equals(kind)
                        && c.code().equals(sampleOf(kind))).findFirst().orElseThrow();
                assertThat(sample.status(rule.field())).as(rule.label() + " / " + kind)
                        .isEqualTo(fromDoc(cells[i].strip()));
            }
        }
    }

    /** A category of each kind without a category override (car seats override the condition of toys). */
    private static String sampleOf(String kind) {
        return switch (kind) {
            case "PKG" -> "beans-pantry-sauces";
            case "PER" -> "dairy-eggs";
            case "SFD" -> "prepared-meals";
            case "BBY" -> "baby-bottles";
            case "BEA" -> "skincare-cosmetics";
            case "HLT" -> "dietary-supplements";
            case "FSH" -> "adult-clothing-footwear";
            case "JWL" -> "jewelry-accessories";
            case "ELC" -> "phones";
            case "HOM" -> "bedding-linens";
            case "KID" -> "toys-games";
            case "PET" -> "pet-food";
            case "BOK" -> "printed-books";
            case "DIG" -> "audiobooks";
            case "GFT" -> "gift-cards";
            default -> "repairs";
        };
    }

    private static FieldStatus fromDoc(String cell) {
        return switch (cell) {
            case "R" -> FieldStatus.REQUIRED;
            case "R*" -> FieldStatus.REQUIRED_OR_EXEMPTION;
            case "DOC" -> FieldStatus.DOCUMENT;
            case "o" -> FieldStatus.OPTIONAL;
            case "-" -> FieldStatus.HIDDEN;
            default -> throw new IllegalArgumentException(cell);
        };
    }

    /** Every category of the rules is a category of the catalog taxonomy, and the taxonomy has no other approved one. */
    @Test
    void everyRuleCategoryExistsInTheSeedTaxonomy() throws IOException {
        String seed;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("db/migration/V1_1__initial_data.sql")) {
            seed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Pattern category = Pattern.compile("'([a-z0-9-]+)', '[^']*', 'CATEGORY'");
        List<String> codes = new ArrayList<>();
        Matcher m = category.matcher(seed);
        while (m.find()) {
            codes.add(m.group(1));
        }
        assertThat(codes).containsAll(BOOK.categories().keySet());
        assertThat(BOOK.categories().keySet()).containsAll(codes);
    }

    private static Optional<Path> repoFile(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }
}
