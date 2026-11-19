package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.pictures.PictureOverrides;
import com.afrotransact.catalog.domain.pictures.PictureProfile;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import org.junit.jupiter.api.Test;

/** The publish rule for pictures under every profile: how many, which type, how large. */
class DefaultProductDraftPictureRulesTest {

    private static final ProductType BOOK = new ProductType("book", "Books",
            List.of(new AttributeDefinition("author", "Author", List.of(), true, false)),
            List.of(new VariationTheme("Format", List.of(new VariationDimension("Format", List.of("Hardcover"))))),
            IdentifierRule.ISBN);

    private static ProductImage image(String type, long bytes) {
        return new ProductImage("https://utfs.io/f/a", 800, 800, bytes, type);
    }

    private static ProductDraft draft(List<ProductImage> images) {
        return new ProductDraft(BOOK, "Pepper & Palm: A West African Kitchen", Map.of("author", "Ifeoma Ade"), images,
                "Format", List.of(new VariantDraft(Map.of("Format", "Hardcover"), "PEPPER-HC", "9780306406157", false,
                        3499, 5)));
    }

    private static DefaultProductDraftPolicy policy(PictureProfile profile) {
        return new DefaultProductDraftPolicy(() -> PictureRules.defaults(profile));
    }

    private static Map<String, String> errors(DefaultProductDraftPolicy policy, List<ProductImage> images) {
        try {
            policy.validate(draft(images), null);
            return Map.of();
        } catch (CatalogRuleViolation ex) {
            return ex.fieldErrors();
        }
    }

    @Test
    void aProductWithNoPhotoIsRefusedUnderStrictAndStartupAndAllowedUnderTesting() {
        assertThat(errors(policy(PictureProfile.STRICT), List.of())).containsEntry("images", "Add at least one image");
        assertThat(errors(policy(PictureProfile.STARTUP), List.of())).containsEntry("images", "Add at least one image");
        assertThat(errors(policy(PictureProfile.TESTING), List.of())).doesNotContainKey("images");
    }

    @Test
    void theDefaultConstructorIsTheStrictStandard() {
        assertThat(errors(new DefaultProductDraftPolicy(), List.of())).containsKey("images");
    }

    @Test
    void everyProfileAcceptsJpegPngWebpAndAvifAndRefusesTheRest() {
        for (PictureProfile p : PictureProfile.values()) {
            for (String type : List.of("image/jpeg", "image/png", "image/webp", "image/avif")) {
                assertThat(errors(policy(p), List.of(image(type, 100_000)))).as(p + " " + type).doesNotContainKey("images[0]");
            }
            for (String type : List.of("image/svg+xml", "text/html", "image/gif", "application/x-msdownload", "")) {
                assertThat(errors(policy(p), List.of(image(type, 100_000)))).as(p + " " + type)
                        .containsEntry("images[0]", "Use JPG, PNG, WebP or AVIF");
            }
        }
    }

    @Test
    void theSizeCapIsTheProfilesMegabytes() {
        ProductImage nine = image("image/avif", 9L << 20);
        assertThat(errors(policy(PictureProfile.STARTUP), List.of(nine))).containsEntry("images[0]", "Larger than 8 MB");
        DefaultProductDraftPolicy twelve = new DefaultProductDraftPolicy(() -> PictureRules.resolve(
                PictureProfile.STARTUP, new PictureOverrides(null, null, null, 12, null, null, null, null, null, null)));
        assertThat(errors(twelve, List.of(nine))).doesNotContainKey("images[0]");
        assertThat(errors(twelve, List.of(image("image/png", 13L << 20)))).containsEntry("images[0]", "Larger than 12 MB");
    }

    @Test
    void theMinimumCountIsAKnob() {
        DefaultProductDraftPolicy three = new DefaultProductDraftPolicy(() -> PictureRules.resolve(PictureProfile.STRICT,
                new PictureOverrides(3, null, null, null, null, null, null, null, null, null)));
        assertThat(errors(three, List.of(image("image/jpeg", 1000)))).containsEntry("images", "Add at least 3 images");
        assertThat(errors(three, List.of(image("image/jpeg", 1000), image("image/png", 1000), image("image/webp", 1000))))
                .doesNotContainKey("images");
    }

    @Test
    void atMostTenImagesUnderEveryProfile() {
        List<ProductImage> eleven = Collections.nCopies(11, image("image/jpeg", 1000));
        for (PictureProfile p : PictureProfile.values()) {
            assertThat(errors(policy(p), eleven)).as(p.wire()).containsEntry("images", "Up to 10 images");
        }
    }

    @Test
    void aTestingProductWithOnePhotoStillValidates() {
        assertThatCode(() -> policy(PictureProfile.TESTING).validate(draft(List.of(image("image/avif", 1000))), null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy(PictureProfile.STARTUP).validate(draft(List.of()), null))
                .isInstanceOf(CatalogRuleViolation.class);
    }
}
