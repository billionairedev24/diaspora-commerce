package com.afrotransact.catalog.adapter.out.spreadsheet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import com.afrotransact.catalog.domain.imports.ImageColumns;
import com.afrotransact.catalog.domain.imports.ImportLimits;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.port.SpreadsheetReader;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Reads uploads with Apache POI's streaming (SAX) .xlsx reader, so a 5,000-row sheet never becomes an in-memory
 * workbook, and with {@link CsvCodec} for CSV. Zip bombs are refused by POI's ZipSecureFile limits (minimum inflate
 * ratio, maximum entry size); XML is parsed without external entities. The "Products" sheet is read (else the first
 * sheet). Rows before the header, blank rows and rows whose first cell starts with "#" are skipped.
 */
@Component
class PoiSpreadsheetReader implements SpreadsheetReader {

    static final String PRODUCTS_SHEET = "Products";
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0};

    PoiSpreadsheetReader() {
        ZipSecureFile.setMinInflateRatio(ImportLimits.MIN_INFLATE_RATIO);
        ZipSecureFile.setMaxEntrySize(ImportLimits.MAX_UNZIPPED_BYTES);
    }

    @Override
    public ParsedSheet read(byte[] content, SpreadsheetFormat format) {
        if (content.length == 0) {
            throw new SpreadsheetRejected("The file is empty");
        }
        if (content.length > ImportLimits.MAX_FILE_BYTES) {
            throw new SpreadsheetRejected("The file is larger than 10 MB; split it into several files");
        }
        List<SheetRow> raw = format == SpreadsheetFormat.XLSX ? readXlsx(content) : readCsv(content);
        // The template's image columns (main, other 1-8, swatch) become the one image list the import reads.
        return ImageColumns.fold(toSheet(raw));
    }

    private static List<SheetRow> readCsv(byte[] content) {
        List<List<String>> records = CsvCodec.parse(content, ImportLimits.MAX_ROWS + 50);
        List<SheetRow> rows = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            rows.add(new SheetRow(i + 1, records.get(i)));
        }
        return rows;
    }

    private static List<SheetRow> readXlsx(byte[] content) {
        if (startsWith(content, OLE2)) {
            throw new SpreadsheetRejected("Excel 97–2003 (.xls) files and password-protected workbooks are not "
                    + "supported: save it as .xlsx");
        }
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
            throw new SpreadsheetRejected("Not an .xlsx workbook");
        }
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(content))) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            byte[] chosen = null;
            byte[] first = null;
            while (sheets.hasNext()) {
                try (InputStream in = sheets.next()) {
                    byte[] xml = in.readAllBytes();
                    if (first == null) {
                        first = xml;
                    }
                    if (PRODUCTS_SHEET.equalsIgnoreCase(sheets.getSheetName())) {
                        chosen = xml;
                    }
                }
            }
            byte[] sheet = chosen != null ? chosen : first;
            if (sheet == null) {
                throw new SpreadsheetRejected("The workbook has no sheets");
            }
            Collector collector = new Collector(ImportLimits.MAX_ROWS + 50);
            XMLReader parser = XMLHelper.newXMLReader();
            parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, collector, new PlainNumbers(), false));
            try {
                parser.parse(new InputSource(new ByteArrayInputStream(sheet)));
            } catch (TooManyRows ex) {
                throw new SpreadsheetRejected("The sheet has more than " + ImportLimits.MAX_ROWS
                        + " rows; split it into several files");
            }
            return collector.rows;
        } catch (SpreadsheetRejected ex) {
            throw ex;
        } catch (InvalidFormatException ex) {
            throw new SpreadsheetRejected("Not a valid .xlsx workbook", ex);
        } catch (IOException | OpenXML4JException | SAXException | ParserConfigurationException | RuntimeException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("Zip bomb") || msg.contains("ratio") || msg.contains("exceeds")) {
                throw new SpreadsheetRejected("The workbook expands to an unsafe size; re-save it in Excel and try again",
                        ex);
            }
            throw new SpreadsheetRejected("The workbook could not be read; save it again as .xlsx", ex);
        }
    }

    /** Header = first row that is not blank and not a "#" comment; data = the following non-blank, non-comment rows. */
    private static ParsedSheet toSheet(List<SheetRow> raw) {
        Iterator<SheetRow> it = raw.iterator();
        List<String> header = null;
        List<SheetRow> rows = new ArrayList<>();
        while (it.hasNext()) {
            SheetRow r = it.next();
            if (r.blank() || r.cell(0).strip().startsWith("#")) {
                continue;
            }
            if (header == null) {
                header = trimTrailing(r.cells());
            } else {
                rows.add(r);
                if (rows.size() > ImportLimits.MAX_ROWS) {
                    throw new SpreadsheetRejected("The file has more than " + ImportLimits.MAX_ROWS
                            + " product rows; split it into several files");
                }
            }
        }
        if (header == null) {
            throw new SpreadsheetRejected("The file is empty: it needs the template's header row and your products");
        }
        return new ParsedSheet(header, rows);
    }

    private static List<String> trimTrailing(List<String> cells) {
        int end = cells.size();
        while (end > 0 && (cells.get(end - 1) == null || cells.get(end - 1).isBlank())) {
            end--;
        }
        return cells.subList(0, end).stream().map(c -> c == null ? "" : c.strip()).toList();
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Numbers in General or Text ("@", built-in format 49) cells as plain digits: a barcode typed as a number is not
     * read as 4.00638E+12.
     */
    private static final class PlainNumbers extends DataFormatter {
        private static final int TEXT_FORMAT = 49;

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString) {
            return formatRawCellContents(value, formatIndex, formatString, false);
        }

        @Override
        public String formatRawCellContents(double value, int formatIndex, String formatString, boolean use1904) {
            if (formatIndex == 0 || formatIndex == TEXT_FORMAT || "General".equalsIgnoreCase(formatString)
                    || "@".equals(formatString)) {
                return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
            }
            return super.formatRawCellContents(value, formatIndex, formatString, use1904);
        }
    }

    private static final class TooManyRows extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Collector implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final int max;
        private final List<SheetRow> rows = new ArrayList<>();
        private List<String> current;
        private int rowNumber;

        Collector(int max) {
            this.max = max;
        }

        @Override
        public void startRow(int rowNum) {
            current = new ArrayList<>();
            rowNumber = rowNum + 1;
        }

        @Override
        public void endRow(int rowNum) {
            rows.add(new SheetRow(rowNumber, current));
            if (rows.size() > max) {
                throw new TooManyRows();
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, Comment comment) {
            int col = cellReference == null ? current.size() : new CellReference(cellReference).getCol();
            if (col >= ImportLimits.MAX_COLUMNS + 50) {
                return;
            }
            while (current.size() < col) {
                current.add("");
            }
            current.add(formattedValue == null ? "" : formattedValue);
        }
    }
}
