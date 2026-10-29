package com.afrotransact.catalog.adapter.out.spreadsheet;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;

/** RFC 4180 CSV reading (comma or semicolon, UTF-8 with or without BOM, Windows-1252 fallback) and writing. */
final class CsvCodec {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private CsvCodec() {
    }

    /** The records of a CSV file; at most {@code maxRecords} (more throws). */
    static List<List<String>> parse(byte[] content, int maxRecords) {
        String text = decode(content);
        char delimiter = delimiter(text);
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    cell.append(c);
                }
                i++;
                continue;
            }
            if (c == '"' && cell.isEmpty() && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
            } else if (c == delimiter) {
                record.add(cell.toString());
                cell.setLength(0);
                wasQuoted = false;
            } else if (c == '\r' || c == '\n') {
                record.add(cell.toString());
                cell.setLength(0);
                wasQuoted = false;
                records.add(record);
                record = new ArrayList<>();
                if (records.size() > maxRecords) {
                    throw new SpreadsheetRejected("The file has more than " + maxRecords + " lines");
                }
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                cell.append(c);
            }
            i++;
        }
        if (quoted) {
            throw new SpreadsheetRejected("The file ends inside a quoted cell; check the quotes on its last rows");
        }
        if (!cell.isEmpty() || !record.isEmpty()) {
            record.add(cell.toString());
            records.add(record);
        }
        return records;
    }

    private static String decode(byte[] content) {
        if (content.length >= 2 && content[0] == 'P' && content[1] == 'K') {
            throw new SpreadsheetRejected("This is an Excel workbook: save it with the .xlsx extension or as CSV");
        }
        for (byte b : content) {
            if (b == 0) {
                throw new SpreadsheetRejected("Not a CSV text file");
            }
        }
        int start = content.length >= 3 && (content[0] & 0xff) == 0xEF && (content[1] & 0xff) == 0xBB
                && (content[2] & 0xff) == 0xBF ? 3 : 0;
        ByteBuffer bytes = ByteBuffer.wrap(content, start, content.length - start);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
        } catch (CharacterCodingException ex) {
            return new String(content, start, content.length - start, WINDOWS_1252);
        }
    }

    /** Semicolon when the first data line has more semicolons than commas (European Excel), else comma. */
    private static char delimiter(String text) {
        for (String line : text.split("\\R", 20)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            long commas = line.chars().filter(ch -> ch == ',').count();
            long semis = line.chars().filter(ch -> ch == ';').count();
            return semis > commas ? ';' : ',';
        }
        return ',';
    }

    /** CSV text (CRLF line ends, quotes where needed) with a UTF-8 BOM so Excel reads accents correctly. */
    static byte[] write(List<List<String>> records) {
        return write(List.of(), records);
    }

    /** As {@link #write(List)}, after "#" comment lines written as they are (readers skip them). */
    static byte[] write(List<String> comments, List<List<String>> records) {
        StringBuilder out = new StringBuilder("\uFEFF");
        for (String c : comments) {
            out.append("# ").append(c.replace('\r', ' ').replace('\n', ' ')).append("\r\n");
        }
        for (List<String> record : records) {
            for (int i = 0; i < record.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                String v = record.get(i) == null ? "" : record.get(i);
                if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                        || v.indexOf(';') >= 0) {
                    out.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else {
                    out.append(v);
                }
            }
            out.append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
