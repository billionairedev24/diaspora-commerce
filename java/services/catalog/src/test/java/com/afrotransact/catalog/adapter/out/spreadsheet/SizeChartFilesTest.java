package com.afrotransact.catalog.adapter.out.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ChartImportColumns;
import com.afrotransact.catalog.domain.imports.ChartImportContext;
import com.afrotransact.catalog.domain.imports.ChartImportResult;
import com.afrotransact.catalog.domain.imports.ChartImportValidator;
import com.afrotransact.catalog.domain.imports.ImportCategory;
import com.afrotransact.catalog.domain.imports.ImportTemplates;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.TemplateSpec;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** The size chart template and upload as files, and the product template's Size and Color dropdowns. */
class SizeChartFilesTest {

    private final PoiSpreadsheetWriter writer = new PoiSpreadsheetWriter();
    private final PoiSpreadsheetReader reader = new PoiSpreadsheetReader();

    @Test
    void theChartTemplateRoundTripsThroughXlsxAndValidates() throws IOException {
        TemplateSpec spec = ChartImportColumns.template(SizingFixtures.DRESS);
        byte[] file = writer.template(spec, SpreadsheetFormat.XLSX);
        byte[] filled;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertThat(wb.getSheetName(0)).isEqualTo("Size charts");
            assertThat(wb.getSheetName(1)).isEqualTo("Instructions");
            Sheet sheet = wb.getSheet("Size charts");
            List<String> header = new ArrayList<>();
            sheet.getRow(0).forEach(c -> header.add(c.getStringCellValue()));
            assertThat(header).containsExactly("Size chart name *", "Unit", "Size *", "Bust *", "Waist *", "Hip *", "Fit",
                    "How to measure");
            for (int r = 0; r < spec.examples().size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < spec.examples().get(r).size(); c++) {
                    row.createCell(c).setCellValue(spec.examples().get(r).get(c));
                }
            }
            wb.write(out);
            filled = out.toByteArray();
        }

        ParsedSheet read = reader.read(filled, SpreadsheetFormat.XLSX);
        ChartImportResult result = ChartImportValidator.validate(read, new ChartImportContext("dress",
                MeasurementUnit.CM));

        assertThat(result.rows()).hasSize(3).allSatisfy(r -> assertThat(r.status()).isEqualTo(RowStatus.VALID));
        assertThat(result.charts()).singleElement().satisfies(c -> {
            assertThat(c.content().rows()).hasSize(3);
            assertThat(c.content().columns()).hasSize(3);
        });
    }

    @Test
    void theCsvChartTemplateIsCommentedAndReadBack() {
        byte[] file = writer.template(ChartImportColumns.template(SizingFixtures.SHOES), SpreadsheetFormat.CSV);
        String text = new String(file, StandardCharsets.UTF_8);

        assertThat(text).contains("# One row per size").contains("Foot length *");
        String withRow = text + "Shoe sizes,cm,EU 38,24-24.5,,\r\nShoe sizes,,EU 39,25,,\r\n";
        ParsedSheet sheet = reader.read(withRow.getBytes(StandardCharsets.UTF_8), SpreadsheetFormat.CSV);
        ChartImportResult result = ChartImportValidator.validate(sheet, new ChartImportContext("shoes",
                MeasurementUnit.CM));

        assertThat(result.charts()).singleElement().satisfies(c -> assertThat(c.content().rows()).hasSize(2));
    }

    @Test
    void sizeAndColorDropdownsOfASizedTypeLetTheSellerTypeTheirOwnValue() throws IOException {
        ImportCategory dresses = new ImportCategory(UUID.randomUUID(), "Fashion › Women › Dresses", "dress");
        byte[] file = writer.template(ImportTemplates.forType(SizingFixtures.DRESS, List.of(dresses)),
                SpreadsheetFormat.XLSX);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet products = wb.getSheet("Products");
            List<String> header = new ArrayList<>();
            products.getRow(0).forEach(c -> header.add(c.getStringCellValue()));
            int size = header.indexOf("Size");
            int color = header.indexOf("Color");
            int chart = header.indexOf("Size chart");
            assertThat(List.of(size, color, chart)).doesNotContain(-1);
            DataValidation sizeList = validationOn(products, size);
            assertThat(sizeList.getShowErrorBox()).isFalse();
            assertThat(validationOn(products, color).getShowErrorBox()).isFalse();
            DataValidation fit = validationOn(products, header.indexOf("Fit"));
            assertThat(fit.getShowErrorBox()).isTrue();
            assertThat(wb.getSheet("Instructions").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("AfroTransact bulk upload: Women's dresses");
            List<String> lines = new ArrayList<>();
            wb.getSheet("Instructions").forEach(r -> lines.add(r.getCell(0).getStringCellValue()));
            assertThat(lines).anyMatch(l -> l.contains("Sizes and colours")).anyMatch(l -> l.contains("Size charts"));
        }
    }

    private static DataValidation validationOn(Sheet sheet, int column) {
        return sheet.getDataValidations().stream()
                .filter(v -> v.getRegions().getCellRangeAddress(0).getFirstColumn() == column).findFirst().orElseThrow();
    }
}
