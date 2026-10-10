package com.afrotransact.deliveryruns.application;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * A CSV export ready to stream: a header row and data rows (RFC 4180 quoting, formula injection guarded).
 *
 * @param filename e.g. {@code LUB-7-orders.csv}
 * @param rows     header first
 */
public record CsvExport(String filename, List<List<String>> rows) {

    public CsvExport {
        rows = List.copyOf(rows);
    }

    /** Data rows (without the header). */
    public int size() {
        return Math.max(0, rows.size() - 1);
    }

    /** Writes the rows, CRLF-separated. */
    public void writeTo(Writer out) throws IOException {
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    out.write(',');
                }
                out.write(cell(row.get(i)));
            }
            out.write("\r\n");
        }
        out.flush();
    }

    /** One cell: quoted when it holds a comma, quote or line break; spreadsheet formulas are neutralised. */
    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && (v.charAt(0) == '=' || v.charAt(0) == '@' || v.charAt(0) == '\t' || v.charAt(0) == '\r'
                || ((v.charAt(0) == '+' || v.charAt(0) == '-') && !v.matches("^[+-][0-9 ().-]*$")))) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
