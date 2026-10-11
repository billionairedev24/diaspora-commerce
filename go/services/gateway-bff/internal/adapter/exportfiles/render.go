package exportfiles

import (
	"encoding/csv"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

type rendererImpl struct{}

var _ port.ExportRenderer = (*rendererImpl)(nil)

// NewRenderer returns the export file writer (CSV, XLSX with a Summary sheet of native charts, PDF with a summary page).
func NewRenderer() port.ExportRenderer { return &rendererImpl{} }

func (rendererImpl) Start(format string, meta domain.ExportMeta, out io.Writer) (port.ExportFileWriter, error) {
	switch format {
	case domain.ExportCSV:
		return newCSVWriter(meta, out)
	case domain.ExportXLSX:
		return newXLSXWriter(meta, out)
	case domain.ExportPDF:
		return newPDFWriter(meta, out), nil
	default:
		return nil, fmt.Errorf("export: unknown format %q", format)
	}
}

// ---------------------------------------------------------------- CSV (data only: CSV cannot carry charts)

type csvWriter struct{ w *csv.Writer }

func newCSVWriter(meta domain.ExportMeta, w io.Writer) (port.ExportFileWriter, error) {
	// UTF-8 BOM so Excel opens accents correctly.
	if _, err := io.WriteString(w, "\ufeff"); err != nil {
		return nil, fmt.Errorf("export: write bom: %w", err)
	}
	cw := csv.NewWriter(w)
	cw.UseCRLF = true
	c := &csvWriter{w: cw}
	header := make([]string, len(meta.Columns))
	for i, col := range meta.Columns {
		header[i] = neutralise(col.Label)
	}
	if err := c.write(header); err != nil {
		return nil, err
	}
	return c, nil
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
	case bool:
		if x {
			return "Yes"
		}
		return "No"
	default:
		return fmt.Sprint(x)
	}
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

// ---------------------------------------------------------------- shared formatting

// moneyText formats currency units ("$1,234.50").
func moneyText(v float64) string {
	sign := ""
	if v < 0 {
		sign, v = "-", -v
	}
	return sign + "$" + grouped(strconv.FormatFloat(v, 'f', 2, 64))
}

// numberText formats a count or quantity ("12,345", "3.5").
func numberText(v float64) string {
	sign := ""
	if v < 0 {
		sign, v = "-", -v
	}
	return sign + grouped(strconv.FormatFloat(v, 'f', -1, 64))
}

func grouped(s string) string {
	whole, frac, hasFrac := strings.Cut(s, ".")
	var b strings.Builder
	for i, r := range whole {
		if i > 0 && (len(whole)-i)%3 == 0 {
			b.WriteByte(',')
		}
		b.WriteRune(r)
	}
	if hasFrac {
		b.WriteString("." + frac)
	}
	return b.String()
}

func totalText(t domain.ExportTotal) string {
	if t.Kind == "money" {
		return moneyText(t.Value)
	}
	return numberText(t.Value)
}

// displayText is a cell as a table shows it (PDF): money formatted, dates short, statuses humanised.
func displayText(kind string, v any) string {
	if v == nil || v == "" {
		return "—"
	}
	switch kind {
	case "money":
		if n, ok := v.(float64); ok {
			return moneyText(n)
		}
	case "number":
		if n, ok := v.(float64); ok {
			return numberText(n)
		}
	case "percent":
		if n, ok := v.(float64); ok {
			return strconv.FormatFloat(n*100, 'f', -1, 64) + " %"
		}
	case "date":
		if t, ok := domain.ParseCellTime(cellText(v)); ok {
			return t.Format("Jan 2, 2006")
		}
	case "datetime":
		if t, ok := domain.ParseCellTime(cellText(v)); ok {
			return t.Format("Jan 2, 2006 15:04")
		}
	case "status":
		return domain.Humanize(cellText(v))
	}
	return cellText(v)
}
