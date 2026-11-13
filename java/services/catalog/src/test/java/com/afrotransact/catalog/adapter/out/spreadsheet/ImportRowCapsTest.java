package com.afrotransact.catalog.adapter.out.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.application.ImportSettings;
import com.afrotransact.catalog.domain.imports.ImportLimits;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.OfferImportContext;
import com.afrotransact.catalog.domain.imports.OfferImportValidator;
import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.PriceQtyValidator;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import org.junit.jupiter.api.Test;

/**
 * Row and size caps of the bulk modes (S393 to S396): 5,000 rows for creates, updates and offers, 50,000 for the price and
 * quantity feed, both settings, and a file over the cap is refused whole before anything is stored.
 */
class ImportRowCapsTest {

    private final PoiSpreadsheetReader reader = new PoiSpreadsheetReader();

    private static byte[] csv(String header, int rows, String rowTemplate) {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (int i = 0; i < rows; i++) {
            sb.append(String.format(rowTemplate, i)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theDefaultCapsAre5000RowsAndTenMegabytesExceptTheFeedWhichTakes50000RowsAndTwentyFive() {
        ImportSettings defaults = new ImportSettings(100, Duration.ofMinutes(2));

        for (ImportMode mode : List.of(ImportMode.CREATE, ImportMode.UPDATE, ImportMode.OFFER)) {
            assertThat(defaults.rowCap(mode)).as(mode.name()).isEqualTo(5_000);
            assertThat(ImportSettings.byteCap(mode)).as(mode.name()).isEqualTo(10L * 1024 * 1024);
        }
        assertThat(defaults.rowCap(ImportMode.PRICE_QTY)).isEqualTo(50_000);
        assertThat(ImportSettings.byteCap(ImportMode.PRICE_QTY)).isEqualTo(25L * 1024 * 1024);
        assertThat(defaults.maxUploadsPerDay()).isEqualTo(20);
    }

    @Test
    void anOfferFileOf5000RowsIsReadAndOneMoreIsRefusedByTheReaderAndTheValidator() {
        int cap = new ImportSettings(100, Duration.ofMinutes(2)).rowCap(ImportMode.OFFER);
        String header = "\"Barcode (UPC, EAN, ISBN)\",Price *,Quantity";

        ParsedSheet ok = reader.read(csv(header, cap, "4006381333931,1.00,%d"), SpreadsheetFormat.CSV, cap,
                ImportSettings.byteCap(ImportMode.OFFER));
        assertThat(ok.rows()).hasSize(5_000);
        OfferImportContext context = new OfferImportContext(Map.<String, OfferTarget>of(), cap);
        assertThat(OfferImportValidator.validate(ok, context).validation().rows()).hasSize(5_000);

        assertThatThrownBy(() -> reader.read(csv(header, cap + 1, "4006381333931,1.00,%d"), SpreadsheetFormat.CSV, cap,
                ImportSettings.byteCap(ImportMode.OFFER))).isInstanceOf(SpreadsheetRejected.class)
                .hasMessageContaining("more than 5000");
        List<SheetRow> rows = new ArrayList<>(ok.rows());
        rows.add(new SheetRow(5_002, List.of("4006381333931", "1.00", "1")));
        assertThatThrownBy(() -> OfferImportValidator.validate(new ParsedSheet(ok.header(), rows), context))
                .isInstanceOf(SpreadsheetRejected.class).hasMessageContaining("upload at most 5000 rows");
    }

    @Test
    void aFeedOf50000RowsIsReadAndOneMoreIsRefused() {
        int cap = new ImportSettings(100, Duration.ofMinutes(2)).rowCap(ImportMode.PRICE_QTY);
        String header = "Seller SKU,Price,Quantity";

        ParsedSheet ok = reader.read(csv(header, cap, "SKU-%d,1.00,1"), SpreadsheetFormat.CSV, cap,
                ImportSettings.byteCap(ImportMode.PRICE_QTY));
        assertThat(ok.rows()).hasSize(50_000);
        assertThat(PriceQtyValidator.validate(ok, cap).importable()).hasSize(50_000);

        assertThatThrownBy(() -> reader.read(csv(header, cap + 1, "SKU-%d,1.00,1"), SpreadsheetFormat.CSV, cap,
                ImportSettings.byteCap(ImportMode.PRICE_QTY))).isInstanceOf(SpreadsheetRejected.class)
                .hasMessageContaining("more than 50000");
    }

    @Test
    void aFeedMayBe25MegabytesButAnOfferFileOnlyTenAndAFileOverItsCapIsRefusedWholeBeforeReading() {
        byte[] twelveMb = new byte[12 * 1024 * 1024];
        byte[] twentySixMb = new byte[26 * 1024 * 1024];

        assertThatThrownBy(() -> reader.read(twelveMb, SpreadsheetFormat.CSV, 5_000, ImportSettings.byteCap(ImportMode.OFFER)))
                .isInstanceOf(SpreadsheetRejected.class).hasMessageContaining("10 MB");
        // A 12 MB file passes the size gate of a feed (it is then read, and refused for being no table, not for its size).
        assertThatThrownBy(() -> reader.read(twelveMb, SpreadsheetFormat.CSV, 50_000, ImportSettings.byteCap(ImportMode.PRICE_QTY)))
                .isInstanceOf(SpreadsheetRejected.class).hasMessageNotContaining("MB; split");
        assertThatThrownBy(() -> reader.read(twentySixMb, SpreadsheetFormat.CSV, 50_000,
                ImportSettings.byteCap(ImportMode.PRICE_QTY))).isInstanceOf(SpreadsheetRejected.class)
                .hasMessageContaining("25 MB");
        assertThat(ImportLimits.MAX_FEED_BYTES).isEqualTo(25L * 1024 * 1024);
    }
}
