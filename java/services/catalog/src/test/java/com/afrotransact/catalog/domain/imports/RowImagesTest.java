package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Library images are matched to upload rows by position in the image cell, or by SKU (then Parent SKU) and slot. */
class RowImagesTest {

    private static LibraryImage image(String sku, String slot, String status) {
        String name = sku + "." + slot + ".jpg";
        return new LibraryImage("ast-" + name, name, sku, slot, status, "READY".equals(status) ? "https://cdn/" + name : null,
                1600, 1600, 100_000, "image/jpeg");
    }

    private static final ImageLibrary LIBRARY = new ImageLibrary(List.of(
            image("AWD-S", "PT02", "READY"), image("AWD-S", "MAIN", "READY"), image("AWD-S", "PT01", "READY"),
            image("AWD-S", "SWCH", "READY"), image("AWD-M", "MAIN", "FLAGGED"), image("AWD", "MAIN", "READY"),
            image("AWD", "PT01", "READY")));

    @Test
    void entriesSplitOnSpacesNewlinesAndPipesWithoutDuplicates() {
        assertThat(RowImages.entries(" a.jpg|b.jpg\nc.png  a.jpg ")).containsExactly("a.jpg", "b.jpg", "c.png");
        assertThat(RowImages.entries(null)).isEmpty();
        assertThat(RowImages.entries("  ")).isEmpty();
        assertThat(RowImages.fileNames("AWD-S.MAIN.jpg https://x.test/a.jpg folder/b.jpg c.PNG")).containsExactly("AWD-S.MAIN.jpg", "c.PNG");
    }

    @Test
    void anEmptyCellTakesTheSkuImagesInSlotOrderWithTheSwatchLast() {
        List<RowImage> images = RowImages.of(LIBRARY, "", "awd-s", "AWD");

        assertThat(images).extracting(RowImage::slot, RowImage::source, RowImage::state, RowImage::ref).containsExactly(
                tuple("MAIN", "SKU", "FOUND", "AWD-S.MAIN.jpg"), tuple("PT01", "SKU", "FOUND", "AWD-S.PT01.jpg"),
                tuple("PT02", "SKU", "FOUND", "AWD-S.PT02.jpg"), tuple("SWCH", "SKU", "FOUND", "AWD-S.SWCH.jpg"));
        assertThat(images.getFirst().url()).isEqualTo("https://cdn/AWD-S.MAIN.jpg");
        assertThat(images.getFirst().width()).isEqualTo(1600);
    }

    @Test
    void aHeldImageIsReportedHeldWithoutAUrl() {
        List<RowImage> images = RowImages.of(LIBRARY, null, "AWD-M", null);

        assertThat(images).singleElement().satisfies(i -> {
            assertThat(i.slot()).isEqualTo("MAIN");
            assertThat(i.state()).isEqualTo("HELD");
            assertThat(i.url()).isNull();
            assertThat(i.message()).contains("moderation");
        });
    }

    @Test
    void aSkuWithoutImagesFallsBackToItsParentSku() {
        assertThat(RowImages.of(LIBRARY, "", "AWD-L", "AWD")).extracting(RowImage::ref)
                .containsExactly("AWD.MAIN.jpg", "AWD.PT01.jpg");
        assertThat(RowImages.of(LIBRARY, "", "NOBODY", "NOPE")).isEmpty();
        assertThat(RowImages.of(LIBRARY, "", null, null)).isEmpty();
    }

    @Test
    void theSkuOwnImagesWinOverTheParents() {
        assertThat(RowImages.of(LIBRARY, "", "AWD-S", "AWD")).extracting(RowImage::ref).doesNotContain("AWD.MAIN.jpg");
    }

    @Test
    void cellEntriesGoByPositionAsMainThenPt01() {
        List<RowImage> images = RowImages.of(LIBRARY, "AWD-S.PT02.jpg https://example.com/back.jpg missing.jpg nonsense", "AWD-S", null);

        assertThat(images).extracting(RowImage::slot, RowImage::source, RowImage::state).containsExactly(
                tuple("MAIN", "FILE", "FOUND"), tuple("PT01", "LINK", "LINK"), tuple("PT02", "FILE", "MISSING"),
                tuple("PT03", "LINK", "BAD_LINK"));
        // the slot comes from the position in the cell, not from the file's own name
        assertThat(images.getFirst().ref()).isEqualTo("AWD-S.PT02.jpg");
        assertThat(images.get(2).message()).isEqualTo("Not in your image library");
    }

    @Test
    void aCellNeverFillsMoreThanNineSlotsAndFileNamesAreCaseInsensitive() {
        StringBuilder cell = new StringBuilder("awd-s.main.JPG");
        for (int i = 0; i < 12; i++) {
            cell.append(" https://example.com/").append(i).append(".jpg");
        }
        List<RowImage> images = RowImages.of(LIBRARY, cell.toString(), "X", null);

        assertThat(images).hasSize(9);
        assertThat(images.getFirst().state()).isEqualTo("FOUND");
        assertThat(images.getLast().slot()).isEqualTo("PT08");
    }

    @Test
    void libraryLookupsByNameSkuSlotAndRef() {
        assertThat(LIBRARY.byFileName("awd-s.pt01.JPG")).map(LibraryImage::assetId).contains("ast-AWD-S.PT01.jpg");
        assertThat(LIBRARY.byRef("library:ast-AWD.MAIN.jpg")).map(LibraryImage::fileName).contains("AWD.MAIN.jpg");
        assertThat(LIBRARY.byRef("https://x.test/a.jpg")).isEmpty();
        assertThat(LIBRARY.slot(" awd ", "PT01")).isPresent();
        assertThat(LIBRARY.productImages("AWD-S")).extracting(LibraryImage::slot).containsExactly("MAIN", "PT01", "PT02");
        assertThat(ImageLibrary.isFileName("a.JPEG")).isTrue();
        assertThat(ImageLibrary.isFileName("dir/a.jpg")).isFalse();
        assertThat(ImageLibrary.isFileName("https://x.test/a.jpg")).isFalse();
        assertThat(ImageLibrary.isFileName("notes.txt")).isFalse();
    }
}
