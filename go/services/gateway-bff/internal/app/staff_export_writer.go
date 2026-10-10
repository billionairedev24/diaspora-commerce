package app

import (
	"archive/zip"
	"bufio"
	"encoding/csv"
	"fmt"
	"io"
	"strconv"
	"strings"
)

// exportWriter streams a table to CSV or XLSX without holding every row in memory.
type exportWriter interface {
	Header(cols []string) error
	Row(cells []any) error
	Close() error
}

// newExportWriter returns the writer of a format ("csv" or "xlsx") on w.
func newExportWriter(format string, w io.Writer) (exportWriter, error) {
	switch format {
	case "csv":
		return newCSVWriter(w)
	case "xlsx":
		return newXLSXWriter(w)
	default:
		return nil, fmt.Errorf("export: unknown format %q", format)
	}
}

// ---------------------------------------------------------------- CSV

type csvWriter struct{ w *csv.Writer }

func newCSVWriter(w io.Writer) (exportWriter, error) {
	// UTF-8 BOM so Excel opens accents correctly.
	if _, err := io.WriteString(w, "\ufeff"); err != nil {
		return nil, fmt.Errorf("export: write bom: %w", err)
	}
	cw := csv.NewWriter(w)
	cw.UseCRLF = true
	return &csvWriter{w: cw}, nil
}

// neutralise stops spreadsheet formula injection: cells starting with = + - @ are prefixed with an apostrophe.
func neutralise(s string) string {
	if s != "" && strings.ContainsRune("=+-@\t\r", rune(s[0])) {
		if _, err := strconv.ParseFloat(s, 64); err != nil {
			return "'" + s
		}
	}
	return s
}

func cellText(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	case int64:
		return strconv.FormatInt(x, 10)
	case int:
		return strconv.Itoa(x)
	default:
		return fmt.Sprint(x)
	}
}

func (c *csvWriter) Header(cols []string) error {
	return c.write(cols)
}

func (c *csvWriter) Row(cells []any) error {
	rec := make([]string, len(cells))
	for i, v := range cells {
		rec[i] = neutralise(cellText(v))
	}
	return c.write(rec)
}

func (c *csvWriter) write(rec []string) error {
	if err := c.w.Write(rec); err != nil {
		return fmt.Errorf("export: csv: %w", err)
	}
	return nil
}

func (c *csvWriter) Close() error {
	c.w.Flush()
	if err := c.w.Error(); err != nil {
		return fmt.Errorf("export: csv flush: %w", err)
	}
	return nil
}

// ---------------------------------------------------------------- XLSX (SpreadsheetML in a zip, streamed)

const xlsxHead = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`

var xlsxStatic = map[string]string{
	"[Content_Types].xml":        xlsxHead + `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`,
	"_rels/.rels":                xlsxHead + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`,
	"xl/workbook.xml":            xlsxHead + `<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Export" sheetId="1" r:id="rId1"/></sheets></workbook>`,
	"xl/_rels/workbook.xml.rels": xlsxHead + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`,
	"xl/styles.xml":              xlsxHead + `<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>`,
}

type xlsxWriter struct {
	z     *zip.Writer
	sheet *bufio.Writer
	row   int
}

func newXLSXWriter(w io.Writer) (exportWriter, error) {
	z := zip.NewWriter(w)
	for _, name := range []string{"[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml"} {
		f, err := z.Create(name)
		if err != nil {
			return nil, fmt.Errorf("export: xlsx part: %w", err)
		}
		if _, err := io.WriteString(f, xlsxStatic[name]); err != nil {
			return nil, fmt.Errorf("export: xlsx part: %w", err)
		}
	}
	// The worksheet is the last part, so it can be streamed row by row.
	f, err := z.Create("xl/worksheets/sheet1.xml")
	if err != nil {
		return nil, fmt.Errorf("export: xlsx sheet: %w", err)
	}
	x := &xlsxWriter{z: z, sheet: bufio.NewWriterSize(f, 64<<10)}
	if _, err := x.sheet.WriteString(xlsxHead + `<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>`); err != nil {
		return nil, fmt.Errorf("export: xlsx sheet: %w", err)
	}
	return x, nil
}

// xlsxCol is the spreadsheet column name of a 0-based index (A … Z, AA …).
func xlsxCol(i int) string {
	name := ""
	for i++; i > 0; i = (i - 1) / 26 {
		name = string(rune('A'+(i-1)%26)) + name
	}
	return name
}

var xmlEscaper = strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;")

// xmlText drops characters XML 1.0 cannot carry and escapes the rest.
func xmlText(s string) string {
	clean := strings.Map(func(r rune) rune {
		if r == '\t' || r == '\n' || r == '\r' || r >= 0x20 && r != 0xFFFE && r != 0xFFFF {
			return r
		}
		return -1
	}, s)
	return xmlEscaper.Replace(clean)
}

func (x *xlsxWriter) write(cells []any, style string) error {
	x.row++
	var b strings.Builder
	fmt.Fprintf(&b, `<row r="%d">`, x.row)
	for i, v := range cells {
		ref := xlsxCol(i) + strconv.Itoa(x.row)
		switch n := v.(type) {
		case nil:
			continue
		case float64:
			fmt.Fprintf(&b, `<c r="%s"%s><v>%s</v></c>`, ref, style, strconv.FormatFloat(n, 'f', -1, 64))
		default:
			fmt.Fprintf(&b, `<c r="%s" t="inlineStr"%s><is><t xml:space="preserve">%s</t></is></c>`, ref, style, xmlText(cellText(v)))
		}
	}
	b.WriteString(`</row>`)
	if _, err := x.sheet.WriteString(b.String()); err != nil {
		return fmt.Errorf("export: xlsx row: %w", err)
	}
	return nil
}

func (x *xlsxWriter) Header(cols []string) error {
	cells := make([]any, len(cols))
	for i, c := range cols {
		cells[i] = c
	}
	return x.write(cells, ` s="1"`)
}

func (x *xlsxWriter) Row(cells []any) error { return x.write(cells, "") }

func (x *xlsxWriter) Close() error {
	if _, err := x.sheet.WriteString(`</sheetData></worksheet>`); err != nil {
		return fmt.Errorf("export: xlsx close: %w", err)
	}
	if err := x.sheet.Flush(); err != nil {
		return fmt.Errorf("export: xlsx flush: %w", err)
	}
	if err := x.z.Close(); err != nil {
		return fmt.Errorf("export: xlsx zip: %w", err)
	}
	return nil
}
