package com.afrotransact.catalog.adapter.in.web;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.catalog.domain.barcode.LabelDraft;
import com.afrotransact.catalog.domain.barcode.LabelKind;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import com.afrotransact.catalog.domain.barcode.Symbology;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of the label endpoints. */
final class LabelDtos {

    private LabelDtos() {
    }

    @Schema(description = "One label. UNIT/SHELF/CUSTOM: Code 128 of `data` (or EAN-13 of `gtin`); RETAIL: EAN-13 / "
            + "UPC-A of `gtin` (GS1-128 (01) for GTIN-8/14); CASE: GS1-128 (01) `gtin` + (10) lot, (11) production, "
            + "(15) best before, (17) expiry, (21) serial; PALLET: GS1-128 (00) `sscc` + optional (02) `gtin` with "
            + "(37) `count`. `symbology` overrides the default.")
    record LabelRequest(@NotNull LabelKind kind, Symbology symbology,
                        @Schema(examples = "ATF3XK8P2QL") @Size(max = 48) String data,
                        @Schema(examples = "09501101530003") @Size(max = 20) String gtin,
                        @Schema(examples = "106141411234567897") @Size(max = 20) String sscc,
                        @Size(max = 20) String lot, LocalDate productionDate, LocalDate bestBefore, LocalDate expiry,
                        @Size(max = 20) String serial, Integer count, @Size(max = 60) String caption,
                        @Size(max = 200) String title, @Size(max = 4) List<@Size(max = 120) String> lines,
                        @Schema(description = "1–1000, default 1") Integer copies) {

        LabelDraft toDraft() {
            return new LabelDraft(kind, symbology, data, gtin, sscc, lot, productionDate, bestBefore, expiry, serial,
                    count, caption, title, lines, copies);
        }
    }

    @Schema(description = "Up to 500 labels rendered as one PDF (pages or sheets) or one ZPL stream.")
    record LabelBatchRequest(@NotEmpty @Size(max = 500) List<@Valid LabelRequest> labels,
                             @Schema(description = "PDF: LABEL = one page per label (label printers, default), SHEET "
                                     + "= tiled on paper") SheetLayout.Mode layout,
                             @Schema(description = "PDF sheets: LETTER (default) or A4") SheetLayout.Paper paper) {
    }
}
