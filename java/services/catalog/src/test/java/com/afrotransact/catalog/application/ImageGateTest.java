package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.application.ImageSettings.Mode;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.domain.images.ImageFinding;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageVerifier;
import org.junit.jupiter.api.Test;

/** The main-image rule in catalog (S390): the verdict comes from media, never from the client's width and height. */
class ImageGateTest {

    private static final String SMALL = "This photo is too small. The main photo must be at least 1000 pixels on its "
            + "longest side so shoppers can zoom. Yours is 800 by 800.";
    private static final String NOT_WHITE = "The main photo needs a plain white background (pure white, RGB 255, 255, "
            + "255). Retake it or edit the background and upload it again.";

    /** Plays media: answers per URL, remembers what it was asked. */
    private static final class FakeMedia implements ImageVerifier {

        final Map<String, ImageCheckResult> known = new HashMap<>();
        final List<Item> asked = new ArrayList<>();
        boolean down;

        FakeMedia pass(String url, int w, int h) {
            known.put(url, new ImageCheckResult(url, true, "PASS", List.of(), w, h, 200_000, "image/jpeg", true));
            return this;
        }

        FakeMedia warn(String url) {
            known.put(url, new ImageCheckResult(url, true, "WARN", List.of(new ImageFinding("FILL_OK_NEAR", "WARN",
                    "The product could fill more of the photo. 85% or more looks best.")), 1600, 1600, 1, "image/jpeg", true));
            return this;
        }

        FakeMedia reject(String url, int w, int h, String code, String message) {
            known.put(url, new ImageCheckResult(url, true, "REJECT", List.of(new ImageFinding(code, "REJECT", message)), w,
                    h, 100_000, "image/jpeg", true));
            return this;
        }

        FakeMedia hold(String url, String message) {
            known.put(url, new ImageCheckResult(url, true, "HOLD", List.of(new ImageFinding("HELD_TEXT", "HOLD", message)),
                    1600, 1600, 100_000, "image/jpeg", true));
            return this;
        }

        @Override
        public List<ImageCheckResult> verify(List<Item> items) {
            if (down) {
                throw new ImageServiceUnavailable("media unavailable", null);
            }
            asked.addAll(items);
            return items.stream().map(i -> known.getOrDefault(i.url(), ImageCheckResult.unknown(i.url()))).toList();
        }

        @Override
        public List<ImageCheckResult> rescore(List<Item> items) {
            return verify(items);
        }
    }

    private static ImageGate gate(FakeMedia media, Mode mode) {
        return new ImageGateImpl(media, new ImageSettings(mode, 60, false, 50, Duration.ofSeconds(30), Duration.ofDays(30)));
    }

    /** What the client claims: a huge, valid-looking picture. */
    private static ProductImage claimed(String url) {
        return new ProductImage(url, 4000, 4000, 1_000_000, "image/jpeg");
    }

    @Test
    void aMainImageUnder1000PixelsIsRefusedWithTheMessageAndTheClientsNumbersAreIgnored() {
        FakeMedia media = new FakeMedia().reject("https://cdn/main.jpg", 800, 800, "MAIN_TOO_SMALL", SMALL);

        ImageGate.Outcome outcome = gate(media, Mode.ENFORCE).check(List.of(claimed("https://cdn/main.jpg")), List.of(),
                "APPAREL_DRESS", Set.of());

        assertThat(outcome.errors()).containsOnlyKeys("images[0]").containsEntry("images[0]", SMALL);
        // The client said 4000 x 4000; the picture media knows is 800 x 800.
        assertThat(outcome.images()).singleElement().satisfies(i -> {
            assertThat(i.width()).isEqualTo(800);
            assertThat(i.height()).isEqualTo(800);
            assertThat(i.bytes()).isEqualTo(100_000);
        });
        assertThat(media.asked).containsExactly(new ImageVerifier.Item("https://cdn/main.jpg", "MAIN", "APPAREL_DRESS"));
    }

    @Test
    void aNonWhiteMainImageIsRefusedButAWarningIsNot() {
        FakeMedia media = new FakeMedia().reject("https://cdn/a.jpg", 1600, 1600, "NOT_WHITE", NOT_WHITE)
                .warn("https://cdn/b.jpg");

        assertThat(gate(media, Mode.ENFORCE).check(List.of(claimed("https://cdn/a.jpg")), List.of(), "T", Set.of()).errors())
                .containsEntry("images[0]", NOT_WHITE);
        ImageGate.Outcome warned = gate(media, Mode.ENFORCE).check(List.of(claimed("https://cdn/b.jpg")), List.of(), "T", Set.of());
        assertThat(warned.errors()).isEmpty();
        assertThat(warned.images()).singleElement().extracting(ProductImage::width).isEqualTo(1600);
    }

    @Test
    void aMainImageHeldForStaffIsRefusedWithThePlainMessage() {
        String held = "We are checking this photo for text, logos or watermarks. Main photos show the product only. "
                + "We usually answer within 2 business days.";
        FakeMedia media = new FakeMedia().hold("https://cdn/held.jpg", held);

        assertThat(gate(media, Mode.ENFORCE).check(List.of(claimed("https://cdn/held.jpg")), List.of(), "T", Set.of()).errors())
                .containsEntry("images[0]", held);
    }

    @Test
    void aPictureMediaDoesNotKnowIsNotVerifiedAndRefused() {
        ImageGate.Outcome outcome = gate(new FakeMedia(), Mode.ENFORCE).check(List.of(claimed("https://elsewhere/x.jpg")),
                List.of(), "T", Set.of());

        assertThat(outcome.errors().get("images[0]")).startsWith("We could not check this photo");
        // Nothing is learned about it, so the client's numbers stay as they were (they are never used for a decision).
        assertThat(outcome.images()).singleElement().extracting(ProductImage::width).isEqualTo(4000);
    }

    @Test
    void otherPicturesMaySmallerAndOnlyWarnButARejectedOneIsRefused() {
        FakeMedia media = new FakeMedia().pass("https://cdn/main.jpg", 1600, 1600).pass("https://cdn/small.jpg", 700, 700)
                .reject("https://cdn/tiny.jpg", 300, 300, "TOO_SMALL", "This photo is too small. Use a photo that is at least 500 pixels wide and tall.");

        ImageGate.Outcome outcome = gate(media, Mode.ENFORCE).check(List.of(claimed("https://cdn/main.jpg"),
                claimed("https://cdn/small.jpg"), claimed("https://cdn/tiny.jpg")), List.of(), "T", Set.of());

        assertThat(outcome.errors()).containsOnlyKeys("images[2]");
        assertThat(media.asked).extracting(ImageVerifier.Item::slot).containsExactly("MAIN", "OTHER", "OTHER");
    }

    @Test
    void theLabelPhotoIsALabelAndTheFirstPictureAfterItIsStillTheMainImage() {
        FakeMedia media = new FakeMedia().pass("https://cdn/label.jpg", 1000, 800).pass("https://cdn/main.jpg", 1600, 1600)
                .pass("https://cdn/v.jpg", 1200, 1200);
        List<ProductImage> images = List.of(claimed("https://cdn/label.jpg").asLabel(), claimed("https://cdn/main.jpg"));

        gate(media, Mode.ENFORCE).check(images, List.of(List.of(claimed("https://cdn/v.jpg"))), "T", Set.of());

        assertThat(media.asked).extracting(ImageVerifier.Item::url, ImageVerifier.Item::slot).containsExactly(
                tuple("https://cdn/label.jpg", "LABEL"),
                tuple("https://cdn/main.jpg", "MAIN"),
                tuple("https://cdn/v.jpg", "OTHER"));
    }

    @Test
    void aVariantPictureIsJudgedAndAProblemIsReportedOnThatVariant() {
        FakeMedia media = new FakeMedia().pass("https://cdn/main.jpg", 1600, 1600)
                .reject("https://cdn/v.jpg", 100, 100, "TOO_SMALL", "This photo is too small.");

        ImageGate.Outcome outcome = gate(media, Mode.ENFORCE).check(List.of(claimed("https://cdn/main.jpg")),
                List.of(List.of(), List.of(claimed("https://cdn/v.jpg"))), "T", Set.of());

        assertThat(outcome.errors()).containsOnlyKeys("variants[1].images");
    }

    @Test
    void picturesTheListingAlreadyHasAreNotJudgedAgainSoGrandfatheredListingsCanBeEdited() {
        FakeMedia media = new FakeMedia().pass("https://cdn/new.jpg", 1600, 1600);

        ImageGate.Outcome outcome = gate(media, Mode.ENFORCE).check(
                List.of(claimed("https://cdn/legacy-main.jpg"), claimed("https://cdn/new.jpg")), List.of(), "T",
                Set.of("https://cdn/legacy-main.jpg"));

        assertThat(outcome.errors()).isEmpty();
        assertThat(media.asked).extracting(ImageVerifier.Item::url).containsExactly("https://cdn/new.jpg");
        // Nothing new at all: media is not asked.
        FakeMedia silent = new FakeMedia();
        gate(silent, Mode.ENFORCE).check(List.of(claimed("https://cdn/legacy-main.jpg")), List.of(), "T",
                Set.of("https://cdn/legacy-main.jpg"));
        assertThat(silent.asked).isEmpty();
    }

    @Test
    void shadowModeRecordsButRefusesNothing() {
        FakeMedia media = new FakeMedia().reject("https://cdn/main.jpg", 800, 800, "MAIN_TOO_SMALL", SMALL);

        ImageGate.Outcome outcome = gate(media, Mode.SHADOW).check(List.of(claimed("https://cdn/main.jpg")), List.of(), "T",
                Set.of());

        assertThat(outcome.errors()).isEmpty();
        assertThat(media.asked).hasSize(1);
        assertThat(outcome.images()).singleElement().extracting(ProductImage::width).isEqualTo(800);
    }

    @Test
    void offMeansMediaIsNotAskedAndNothingChanges() {
        FakeMedia media = new FakeMedia().reject("https://cdn/main.jpg", 800, 800, "MAIN_TOO_SMALL", SMALL);
        List<ProductImage> images = List.of(claimed("https://cdn/main.jpg"));

        ImageGate.Outcome outcome = gate(media, Mode.OFF).check(images, List.of(), "T", Set.of());

        assertThat(outcome.errors()).isEmpty();
        assertThat(outcome.images()).isEqualTo(images);
        assertThat(media.asked).isEmpty();
    }

    @Test
    void whenMediaIsDownEnforcingFailsClosedAndShadowCarriesOn() {
        FakeMedia media = new FakeMedia();
        media.down = true;

        assertThatThrownBy(() -> gate(media, Mode.ENFORCE).check(List.of(claimed("https://cdn/main.jpg")), List.of(), "T",
                Set.of())).isInstanceOf(ImageServiceUnavailable.class);
        assertThat(gate(media, Mode.SHADOW).check(List.of(claimed("https://cdn/main.jpg")), List.of(), "T", Set.of()).errors())
                .isEmpty();
    }

    @Test
    void aSettingsObjectNeedsSaneNumbers() {
        assertThatThrownBy(() -> new ImageSettings(Mode.ENFORCE, -1, false, 50, Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageSettings(Mode.ENFORCE, 60, false, 0, Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
