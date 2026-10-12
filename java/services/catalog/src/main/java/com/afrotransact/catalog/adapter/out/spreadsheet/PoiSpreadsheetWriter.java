package com.afrotransact.catalog.adapter.out.spreadsheet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.catalog.domain.imports.CellSafety;
import com.afrotransact.catalog.domain.imports.ColumnKind;
import com.afrotransact.catalog.domain.imports.ImportColumn;
import com.afrotransact.catalog.domain.imports.ImportLimits;
import com.afrotransact.catalog.domain.imports.ReportRow;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.TemplateSpec;
import com.afrotransact.catalog.port.SpreadsheetWriter;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Writes the upload templates and error reports with Apache POI. Templates: a "Products" sheet (header row, required
 * columns marked "*" and highlighted, a note with each column's description, frozen header, dropdowns for every list
 * column, number checks), an "Instructions" sheet, an "Example" sheet and a hidden "Lists" sheet that the dropdowns
 * read from. Every cell that comes from seller input is written through {@link CellSafety}.
 */
@Component
class PoiSpreadsheetWriter implements SpreadsheetWriter {

    private static final int VALIDATED_ROWS = ImportLimits.MAX_ROWS;
    private static final byte[] REQUIRED_FILL = {(byte) 0xFF, (byte) 0xF1, (byte) 0xC2};
    private static final byte[] OPTIONAL_FILL = {(byte) 0xF2, (byte) 0xF2, (byte) 0xF2};
    private static final byte[] ERROR_FILL = {(byte) 0xFD, (byte) 0xE2, (byte) 0xE1};
    private static final byte[] WARNING_FILL = {(byte) 0xFF, (byte) 0xF4, (byte) 0xCE};

    @Override
    public byte[] template(TemplateSpec spec, SpreadsheetFormat format) {
        if (format == SpreadsheetFormat.CSV) {
            List<String> header = spec.columns().stream().map(ImportColumn::header).toList();
            List<List<String>> records = List.of(header);
            return CsvCodec.write(spec.instructions(), records);
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet products = wb.createSheet("Products");
            Sheet instructions = wb.createSheet("Instructions");
            Sheet example = wb.createSheet("Example");
            Sheet lists = wb.createSheet("Lists");
            writeHeader(products, spec.columns(), st, true);
            products.createFreezePane(2, 1);
            for (int c = 0; c < spec.columns().size(); c++) {
                ImportColumn col = spec.columns().get(c);
                products.setColumnWidth(c, width(col));
                if (col.kind() == ColumnKind.TEXT || col.kind() == ColumnKind.GTIN || col.kind() == ColumnKind.URLS
                        || col.kind() == ColumnKind.LONG_TEXT) {
                    products.setDefaultColumnStyle(c, st.text);
                }
            }
            validations(wb, products, lists, spec.columns());
            writeInstructions(instructions, spec, st);
            writeHeader(example, spec.columns(), st, false);
            for (int r = 0; r < spec.examples().size(); r++) {
                Row row = example.createRow(r + 1);
                List<String> cells = spec.examples().get(r);
                for (int c = 0; c < cells.size(); c++) {
                    row.createCell(c).setCellValue(CellSafety.safe(cells.get(c)));
                }
            }
            for (int c = 0; c < spec.columns().size(); c++) {
                example.setColumnWidth(c, width(spec.columns().get(c)));
            }
            wb.setSheetVisibility(wb.getSheetIndex(lists), SheetVisibility.HIDDEN);
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public byte[] report(List<String> header, List<ReportRow> rows, SpreadsheetFormat format) {
        List<String> fullHeader = new ArrayList<>(header.stream().map(CellSafety::safe).toList());
        fullHeader.add("Row");
        fullHeader.add("Row status");
        fullHeader.add("Result");
        fullHeader.add("Errors");
        if (format == SpreadsheetFormat.CSV) {
            List<List<String>> records = new ArrayList<>();
            records.add(fullHeader);
            for (ReportRow r : rows) {
                records.add(reportCells(header.size(), r));
            }
            return CsvCodec.write(records);
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet sheet = wb.createSheet("Products");
            Row h = sheet.createRow(0);
            for (int c = 0; c < fullHeader.size(); c++) {
                h.createCell(c).setCellValue(fullHeader.get(c));
                h.getCell(c).setCellStyle(c >= header.size() ? st.requiredHeader : st.optionalHeader);
            }
            sheet.createFreezePane(0, 1);
            for (int i = 0; i < rows.size(); i++) {
                ReportRow r = rows.get(i);
                Row row = sheet.createRow(i + 1);
                List<String> cells = reportCells(header.size(), r);
                CellStyle style = r.status() == RowStatus.ERROR ? st.errorRow
                        : r.status() == RowStatus.WARNING ? st.warningRow : st.text;
                for (int c = 0; c < cells.size(); c++) {
                    row.createCell(c).setCellValue(cells.get(c));
                    if (c >= header.size()) {
                        row.getCell(c).setCellStyle(style);
                    }
                }
            }
            for (int c = 0; c < fullHeader.size(); c++) {
                sheet.setColumnWidth(c, c == fullHeader.size() - 1 ? 80 * 256 : 18 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static List<String> reportCells(int width, ReportRow r) {
        List<String> cells = new ArrayList<>(width + 4);
        for (int c = 0; c < width; c++) {
            cells.add(CellSafety.safe(c < r.cells().size() ? r.cells().get(c) : ""));
        }
        cells.add(Integer.toString(r.rowNumber()));
        cells.add(switch (r.status()) {
            case VALID -> "Valid";
            case WARNING -> "Warning";
            case ERROR -> "Error";
        });
        cells.add(CellSafety.safe(r.result() == null ? "" : r.result()));
        cells.add(CellSafety.safe(r.errors() == null ? "" : r.errors()));
        return cells;
    }

    private static void writeHeader(Sheet sheet, List<ImportColumn> columns, Styles st, boolean notes) {
        Row h = sheet.createRow(0);
        h.setHeightInPoints(30);
        Drawing<?> drawing = notes ? sheet.createDrawingPatriarch() : null;
        for (int c = 0; c < columns.size(); c++) {
            ImportColumn col = columns.get(c);
            h.createCell(c).setCellValue(col.header());
            h.getCell(c).setCellStyle(col.required() ? st.requiredHeader : st.optionalHeader);
            if (drawing != null) {
                ClientAnchor anchor = sheet.getWorkbook().getCreationHelper().createClientAnchor();
                anchor.setCol1(c);
                anchor.setCol2(c + 4);
                anchor.setRow1(0);
                anchor.setRow2(6);
                Comment note = drawing.createCellComment(anchor);
                StringBuilder text = new StringBuilder(col.description());
                if (!col.options().isEmpty() && col.options().size() <= 12) {
                    text.append("\nAllowed: ").append(String.join(", ", col.options()));
                }
                text.append(col.required() ? "\nRequired." : "\nOptional.");
                note.setString(new XSSFRichTextString(text.toString()));
                note.setAuthor("AfroTransact");
                h.getCell(c).setCellComment(note);
            }
        }
    }

    private static void validations(XSSFWorkbook wb, Sheet products, Sheet lists, List<ImportColumn> columns) {
        DataValidationHelper helper = products.getDataValidationHelper();
        Row listHeader = lists.createRow(0);
        int listCol = 0;
        for (int c = 0; c < columns.size(); c++) {
            ImportColumn col = columns.get(c);
            CellRangeAddressList range = new CellRangeAddressList(1, VALIDATED_ROWS, c, c);
            DataValidationConstraint constraint = null;
            String error = null;
            switch (col.kind()) {
                case ENUM, BOOLEAN -> {
                    if (col.options().isEmpty()) {
                        break;
                    }
                    listHeader.createCell(listCol).setCellValue(col.label());
                    for (int i = 0; i < col.options().size(); i++) {
                        Row r = lists.getRow(i + 1) == null ? lists.createRow(i + 1) : lists.getRow(i + 1);
                        r.createCell(listCol).setCellValue(col.options().get(i));
                    }
                    String letter = CellReference.convertNumToColString(listCol);
                    Name name = wb.createName();
                    name.setNameName("list_" + listCol);
                    name.setRefersToFormula("Lists!$" + letter + "$2:$" + letter + "$" + (col.options().size() + 1));
                    constraint = helper.createFormulaListConstraint("list_" + listCol);
                    error = "Choose a value from the list.";
                    listCol++;
                }
                case INTEGER -> {
                    constraint = helper.createIntegerConstraint(DataValidationConstraint.OperatorType.BETWEEN, "0",
                            "100000");
                    error = "Enter a whole number.";
                }
                case MONEY, DECIMAL -> {
                    constraint = helper.createDecimalConstraint(DataValidationConstraint.OperatorType.BETWEEN, "0",
                            "1000000");
                    error = "Enter a number (no currency sign needed).";
                }
                default -> {
                }
            }
            if (constraint == null) {
                continue;
            }
            DataValidation v = helper.createValidation(constraint, range);
            v.setShowErrorBox(true);
            v.setErrorStyle(DataValidation.ErrorStyle.STOP);
            v.createErrorBox(col.label(), error);
            v.setShowPromptBox(false);
            v.setSuppressDropDownArrow(true);
            products.addValidationData(v);
        }
        for (int c = 0; c < listCol; c++) {
            lists.setColumnWidth(c, 40 * 256);
        }
    }

    private static void writeInstructions(Sheet sheet, TemplateSpec spec, Styles st) {
        int r = 0;
        Row title = sheet.createRow(r++);
        title.createCell(0).setCellValue("AfroTransact bulk upload: " + spec.title());
        title.getCell(0).setCellStyle(st.title);
        for (String line : spec.instructions()) {
            sheet.createRow(r++).createCell(0).setCellValue(line);
        }
        r++;
        Row h = sheet.createRow(r++);
        String[] heads = {"Column", "Required", "What to enter", "Allowed values", "Example"};
        for (int c = 0; c < heads.length; c++) {
            h.createCell(c).setCellValue(heads[c]);
            h.getCell(c).setCellStyle(st.optionalHeader);
        }
        for (ImportColumn col : spec.columns()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(col.label());
            row.createCell(1).setCellValue(col.required() ? "Yes" : "No");
            row.createCell(2).setCellValue(col.description());
            row.createCell(3).setCellValue(col.options().isEmpty() ? kindHint(col.kind())
                    : truncate(String.join(", ", col.options())));
            row.createCell(4).setCellValue(CellSafety.safe(col.example()));
            for (int c = 0; c < 5; c++) {
                row.getCell(c).setCellStyle(st.wrap);
            }
        }
        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 10 * 256);
        sheet.setColumnWidth(2, 70 * 256);
        sheet.setColumnWidth(3, 50 * 256);
        sheet.setColumnWidth(4, 30 * 256);
    }

    private static String truncate(String s) {
        int max = SpreadsheetVersion.EXCEL2007.getMaxTextLength();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static String kindHint(ColumnKind kind) {
        return switch (kind) {
            case MONEY -> "Amount in US dollars, e.g. 24.99";
            case INTEGER -> "Whole number";
            case DECIMAL -> "Number";
            case BOOLEAN -> "Yes or No";
            case GTIN -> "8, 12, 13 or 14 digits";
            case URLS -> "https:// links separated by spaces or |";
            default -> "Text";
        };
    }

    private static int width(ImportColumn col) {
        return switch (col.kind()) {
            case LONG_TEXT, URLS -> 40 * 256;
            case TEXT -> (col.key().equals("title") ? 36 : 20) * 256;
            case ENUM -> 24 * 256;
            default -> 16 * 256;
        };
    }

    /** The cell styles of a workbook. */
    private static final class Styles {
        private final XSSFCellStyle requiredHeader;
        private final XSSFCellStyle optionalHeader;
        private final XSSFCellStyle text;
        private final XSSFCellStyle wrap;
        private final XSSFCellStyle title;
        private final XSSFCellStyle errorRow;
        private final XSSFCellStyle warningRow;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            requiredHeader = header(wb, bold, REQUIRED_FILL);
            optionalHeader = header(wb, bold, OPTIONAL_FILL);
            text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            title = wb.createCellStyle();
            title.setFont(big);
            errorRow = fill(wb, ERROR_FILL);
            warningRow = fill(wb, WARNING_FILL);
        }

        private static XSSFCellStyle header(XSSFWorkbook wb, Font font, byte[] rgb) {
            XSSFCellStyle s = fill(wb, rgb);
            s.setFont(font);
            s.setWrapText(true);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBottomBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
            return s;
        }

        private static XSSFCellStyle fill(XSSFWorkbook wb, byte[] rgb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFillForegroundColor(new XSSFColor(rgb, null));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            s.setDataFormat(wb.createDataFormat().getFormat("@"));
            s.setWrapText(true);
            return s;
        }
    }
}
