package com.afrotransact.catalog.domain.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The dedicated image columns of the template are folded into the one image cell the import reads. */
class ImageColumnsTest {

    @Test
    void foldsMainOtherAndSwatchColumnsIntoImageUrlsInOrder() {
        ParsedSheet sheet = new ParsedSheet(List.of("SKU *", "Main image URL", "Other image URL 1", "other_image_url_2",
                "Swatch image URL", "Title *"), List.of(new SheetRow(2, List.of("A-1", "https://x.test/a.jpg",
                "A-1.PT01.jpg", "", "A-1.SWCH.jpg", "Dress"))));

        ParsedSheet folded = ImageColumns.fold(sheet);

        assertThat(folded.header()).containsExactly("SKU *", "Title *", ImportColumns.IMAGES);
        assertThat(folded.rows().getFirst().cells()).containsExactly("A-1", "Dress",
                "https://x.test/a.jpg A-1.PT01.jpg A-1.SWCH.jpg");
        assertThat(folded.rows().getFirst().rowNumber()).isEqualTo(2);
    }

    @Test
    void keepsAnExistingImageUrlsCellAfterTheDedicatedColumns() {
        ParsedSheet sheet = new ParsedSheet(List.of("SKU", "Image URLs", "main_image_url"),
                List.of(new SheetRow(2, List.of("A-1", "https://x.test/b.jpg", "https://x.test/a.jpg"))));

        ParsedSheet folded = ImageColumns.fold(sheet);

        assertThat(folded.header()).containsExactly("SKU", "Image URLs");
        assertThat(folded.rows().getFirst().cells()).containsExactly("A-1", "https://x.test/a.jpg https://x.test/b.jpg");
    }

    @Test
    void leavesASheetWithoutTheColumnsAlone() {
        ParsedSheet sheet = new ParsedSheet(List.of("SKU", "Image URLs"), List.of(new SheetRow(2, List.of("A", "x"))));

        assertThat(ImageColumns.fold(sheet)).isSameAs(sheet);
    }

    @Test
    void theTemplateOffersMainEightOthersAndSwatch() {
        assertThat(ImageColumns.template()).hasSize(10);
        assertThat(ImageColumns.template().getFirst().key()).isEqualTo("main_image_url");
        assertThat(ImageColumns.template().getLast().key()).isEqualTo("swatch_image_url");
    }
}
