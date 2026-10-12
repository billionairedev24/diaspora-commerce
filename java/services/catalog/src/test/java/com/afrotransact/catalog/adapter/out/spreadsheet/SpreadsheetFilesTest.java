package com.afrotransact.catalog.adapter.out.spreadsheet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.AttributeSection;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.imports.ImportCategory;
import com.afrotransact.catalog.domain.imports.ImportContext;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportTemplates;
import com.afrotransact.catalog.domain.imports.ImportValidator;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.ReportRow;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.domain.imports.TemplateSpec;
import com.afrotransact.catalog.domain.imports.ValidationResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Templates, the streaming reader and error reports (Apache POI and CSV). */
class SpreadsheetFilesTest {

    private static final ProductType GROCERY = new ProductType("grocery", "Packaged food", List.of(
            new AttributeDefinition("brand", "Brand", List.of(), true, false),
            new AttributeDefinition("allergens", "Allergens", List.of("None", "Contains peanuts"), true, false, null,
                    null, AttributeSection.FOOD, null),
            new AttributeDefinition("shelf", "Shelf life", List.of(), true, false, AttributeKind.NUMBER,
                    List.of("days", "months"), AttributeSection.FOOD, "From the date of manufacture")),
            List.of(new VariationTheme("Net weight", List.of(new VariationDimension("Net weight",
                    List.of("8 oz", "1 lb", "2 lb"))))),
            IdentifierRule.GTIN, new ProductTypeTraits(true, false, false, true));
    private static final List<ImportCategory> CATEGORIES = List.of(
            new ImportCategory(UUID.randomUUID(), "Groceries › Pantry › Spices & seasonings", "grocery"),
            new ImportCategory(UUID.randomUUID(), "Groceries › Pantry › Snacks", "grocery"));

    private final PoiSpreadsheetWriter writer = new PoiSpreadsheetWriter();
    private final PoiSpreadsheetReader reader = new PoiSpreadsheetReader();

    @Test
    void theExcelTemplateHasMarkedHeadersNotesDropdownsAndHelperSheets() throws IOException {
        TemplateSpec spec = ImportTemplates.forType(GROCERY, CATEGORIES);
        byte[] file = writer.template(spec, SpreadsheetFormat.XLSX);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(List.of(wb.getSheetName(0), wb.getSheetName(1), wb.getSheetName(2), wb.getSheetName(3)))
                    .containsExactly("Products", "Instructions", "Example", "Lists");
            assertThat(wb.getSheetVisibility(3)).isEqualTo(SheetVisibility.HIDDEN);
            XSSFSheet products = wb.getSheet("Products");
            List<String> header = new ArrayList<>();
            products.getRow(0).forEach(c -> header.add(c.getStringCellValue()));
            assertThat(header).startsWith("Parent SKU", "SKU *", "Category *", "Title *", "Brand *")
                    .contains("Price (USD) *", "Quantity *", "Net weight", "Allergens *", "Shelf life *",
                            "Shelf life unit", "Pre-order eligible", "Image URLs *");
            Cell sku = products.getRow(0).getCell(1);
            assertThat(sku.getCellComment().getString().getString()).contains("Your unique code", "Required.");
            assertThat(products.getPaneInformation().isFreezePane()).isTrue();
            // Dropdowns for every list column (category, condition, variation, net weight, allergens, unit, no
            // barcode, fulfilment, pre-order) and number checks for price, quantity, package, shelf life ...
            assertThat(products.getDataValidations()).hasSizeGreaterThanOrEqualTo(14);
            assertThat(wb.getName("list_0").getRefersToFormula()).startsWith("Lists!$A$2");
            Sheet example = wb.getSheet("Example");
            assertThat(example.getRow(1).getCell(header.indexOf("SKU *")).getStringCellValue())
                    .isEqualTo("EXAMPLE-GROCERY-1");
            assertThat(example.getRow(2).getCell(header.indexOf("Net weight")).getStringCellValue()).isEqualTo("1 lb");
            assertThat(wb.getSheet("Instructions").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("AfroTransact bulk upload: Packaged food");
        }
    }

    @Test
    void theExampleRowsOfATemplateValidateWhenUploaded() throws IOException {
        TemplateSpec spec = ImportTemplates.forType(GROCERY, CATEGORIES);
        byte[] file = writer.template(spec, SpreadsheetFormat.XLSX);
        byte[] filled;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet products = wb.getSheet("Products");
            for (int r = 0; r < spec.examples().size(); r++) {
                Row row = products.createRow(r + 1);
                List<String> cells = new ArrayList<>(spec.examples().get(r));
                for (int c = 0; c < cells.size(); c++) {
                    String col = spec.columns().get(c).key();
                    String v = switch (col) {
                        case "attr:allergens" -> r == 0 ? "None" : "";
                        case "brand" -> r == 0 ? "Mama Kano" : "";
                        case "attr:shelf" -> r == 0 ? "12" : "";
                        default -> cells.get(c);
                    };
                    if ("gtin".equals(col) && r == 0) {
                        row.createCell(c).setCellValue(4006381333931d); // a barcode typed as a number
                    } else {
                        row.createCell(c).setCellValue(v);
                    }
                }
            }
            wb.write(out);
            filled = out.toByteArray();
        }
        ParsedSheet sheet = reader.read(filled, SpreadsheetFormat.XLSX);
        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().getFirst().cell(spec.columns().stream().map(c -> c.key()).toList().indexOf("gtin")))
                .isEqualTo("4006381333931");
        ValidationResult result = ImportValidator.validate(sheet, new ImportContext(ImportMode.CREATE, GROCERY,
                Map.of("grocery", GROCERY), CATEGORIES, Set.of(), Set.of(),
                Map.of()));
        assertThat(result.rows()).allSatisfy(r -> assertThat(r.status()).isNotEqualTo(RowStatus.ERROR));
        assertThat(result.products()).singleElement().satisfies(p -> {
            assertThat(p.variants()).hasSize(2);
            assertThat(p.attributes()).containsEntry("shelf", "12 days");
        });
    }

    @Test
    void theCsvTemplateIsCommentedAndReadBack() {
        byte[] file = writer.template(ImportTemplates.forType(GROCERY, CATEGORIES), SpreadsheetFormat.CSV);
        String text = new String(file, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF# One row per item you sell");
        String withRow = text + "SUYA-1,,\"Groceries › Pantry › Snacks\",\"Suya, the spice\"\r\n";
        ParsedSheet sheet = reader.read(withRow.getBytes(StandardCharsets.UTF_8), SpreadsheetFormat.CSV);
        assertThat(sheet.header()).startsWith("Parent SKU", "SKU *");
        assertThat(sheet.rows()).singleElement().satisfies(r -> {
            assertThat(r.cell(0)).isEqualTo("SUYA-1");
            assertThat(r.cell(3)).isEqualTo("Suya, the spice");
            assertThat(r.rowNumber()).isEqualTo(7);
        });
    }

    @Test
    void csvReadsSemicolonsQuotedNewlinesAndWindowsEncodings() {
        byte[] latin = "SKU;Title\r\nA-1;\"Café\r\nline 2\"\r\n\r\n# note\r\nA-2;x\r\n"
                .getBytes(Charset.forName("windows-1252"));
        ParsedSheet sheet = reader.read(latin, SpreadsheetFormat.CSV);
        assertThat(sheet.header()).containsExactly("SKU", "Title");
        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().getFirst().cell(1)).isEqualTo("Café\r\nline 2");
    }

    @Test
    void refusesLegacyEmptyOversizedAndTooLongFiles() {
        byte[] xls = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0};
        assertThatThrownBy(() -> reader.read(xls, SpreadsheetFormat.XLSX)).isInstanceOf(SpreadsheetRejected.class)
                .hasMessageContaining(".xls");
        assertThatThrownBy(() -> reader.read(new byte[0], SpreadsheetFormat.CSV)).hasMessageContaining("empty");
        assertThatThrownBy(() -> reader.read(new byte[11 * 1024 * 1024], SpreadsheetFormat.CSV))
                .hasMessageContaining("10 MB");
        StringBuilder csv = new StringBuilder("SKU,Title\n");
        for (int i = 0; i < 5_100; i++) {
            csv.append("S-").append(i).append(",t\n");
        }
        assertThatThrownBy(() -> reader.read(csv.toString().getBytes(StandardCharsets.UTF_8), SpreadsheetFormat.CSV))
                .isInstanceOf(SpreadsheetRejected.class).hasMessageContaining("more than");
        assertThatThrownBy(() -> reader.read("PK\u0003\u0004".getBytes(StandardCharsets.ISO_8859_1),
                SpreadsheetFormat.CSV)).hasMessageContaining("Excel workbook");
    }

    @Test
    void refusesZipBombs() throws IOException {
        byte[] template = writer.template(ImportTemplates.forType(GROCERY, CATEGORIES), SpreadsheetFormat.XLSX);
        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(template));
             ZipOutputStream out = new ZipOutputStream(bomb)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] data = in.readAllBytes();
                out.putNextEntry(new ZipEntry(e.getName()));
                if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    String xml = new String(data, StandardCharsets.UTF_8);
                    int at = xml.indexOf("<sheetData");
                    // 40 MB of spaces compress to a few kilobytes: far below the minimum inflate ratio.
                    data = (xml.substring(0, at) + " ".repeat(40 * 1024 * 1024) + xml.substring(at))
                            .getBytes(StandardCharsets.UTF_8);
                }
                out.write(data);
                out.closeEntry();
            }
        }
        assertThatThrownBy(() -> reader.read(bomb.toByteArray(), SpreadsheetFormat.XLSX))
                .isInstanceOf(SpreadsheetRejected.class).hasMessageContaining("unsafe size");
    }

    @Test
    void theErrorReportNeutralisesFormulasAndExplainsEachRow() throws IOException {
        List<String> header = List.of("SKU", "Title", "=cmd|' /C calc'!A0");
        List<ReportRow> rows = List.of(
                new ReportRow(2, List.of("A-1", "=HYPERLINK(\"http://evil\",\"x\")", "+1"), RowStatus.ERROR, "",
                        "Title: At least 10 characters"),
                new ReportRow(3, List.of("A-2", "@SUM(1)", "-2"), RowStatus.VALID, "Created", ""));
        String csv = new String(writer.report(header, rows, SpreadsheetFormat.CSV), StandardCharsets.UTF_8);
        assertThat(csv).contains("'=cmd|' /C calc'!A0", "\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"", "'+1",
                "'@SUM(1)", "'-2", "Row status", "Errors", "Error,,Title: At least 10 characters", "Valid,Created");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(writer.report(header, rows,
                SpreadsheetFormat.XLSX)))) {
            Row first = wb.getSheet("Products").getRow(1);
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("'=HYPERLINK(\"http://evil\",\"x\")");
            assertThat(first.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(first.getCell(6).getStringCellValue()).isEqualTo("Title: At least 10 characters");
        }
    }
}
