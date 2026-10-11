package exportfiles_test

import (
	"archive/zip"
	"bytes"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/xuri/excelize/v2"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/exportfiles"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

var testColumns = []domain.ExportColumn{
	{Key: "number", Label: "Order", Kind: "mono"}, {Key: "customer", Label: "Customer", Kind: "strong"},
	{Key: "total", Label: "Total", Kind: "money"}, {Key: "placedAt", Label: "Placed", Kind: "datetime"},
	{Key: "status", Label: "Status", Kind: "status"},
}

func render(t *testing.T, format string, rows int) []byte {
	t.Helper()
	var buf bytes.Buffer
	w, err := exportfiles.NewRenderer().Start(format, domain.ExportMeta{Title: "Orders", Columns: testColumns, Scope: "Search “ada”",
		GeneratedAt: time.Date(2026, 10, 11, 9, 0, 0, 0, time.UTC)}, &buf)
	if err != nil {
		t.Fatal(err)
	}
	statuses := []string{"PAID", "SHIPPED", "CANCELLED"}
	for i := range rows {
		cells := []any{fmt.Sprintf("AT-%06d", i+1), "Ada & <Obi>\x01", float64(i%50) + 0.5, fmt.Sprintf("2026-%02d-%02dT10:00:00Z", 1+i%9, 1+i%27), statuses[i%3]}
		if err := w.Row(cells); err != nil {
			t.Fatal(err)
		}
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestCSVIsDataOnlyAndNeutralisesFormulas(t *testing.T) {
	var buf bytes.Buffer
	w, err := exportfiles.NewRenderer().Start("csv", domain.ExportMeta{Columns: []domain.ExportColumn{{Label: "Name", Kind: "text"}, {Label: "Amount", Kind: "money"}}}, &buf)
	if err != nil {
		t.Fatal(err)
	}
	_ = w.Row([]any{"=HYPERLINK(\"x\")", 12.5})
	_ = w.Row([]any{"-42", nil})
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	got := buf.String()
	if !strings.HasPrefix(got, "\ufeffName,Amount\r\n") || !strings.Contains(got, `"'=HYPERLINK(""x"")",12.5`) || !strings.Contains(got, "-42,\r\n") {
		t.Fatalf("csv = %q", got)
	}
	if _, err := exportfiles.NewRenderer().Start("docx", domain.ExportMeta{}, &buf); err == nil {
		t.Fatal("unknown format accepted")
	}
}

// TestXLSXHasDataAndSummaryWithNativeCharts opens the workbook with excelize and checks the sheets, typed cells and
// the chart parts (DrawingML charts anchored on the Summary sheet).
func TestXLSXHasDataAndSummaryWithNativeCharts(t *testing.T) {
	raw := render(t, "xlsx", 120)
	f, err := excelize.OpenReader(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	if got := f.GetSheetList(); !slices.Equal(got, []string{"Summary", "Data"}) {
		t.Fatalf("sheets = %v", got)
	}
	if f.GetActiveSheetIndex() != 0 {
		t.Fatal("the Summary sheet opens first")
	}
	head, _ := f.GetCellValue("Data", "A1")
	cust, _ := f.GetCellValue("Data", "B2")
	if head != "Order" || cust != "Ada & <Obi>" {
		t.Fatalf("data = %q %q", head, cust)
	}
	if typ, _ := f.GetCellType("Data", "C2"); typ != excelize.CellTypeNumber && typ != excelize.CellTypeUnset {
		t.Fatalf("money cell type = %v", typ)
	}
	rows, _ := f.GetRows("Data")
	if len(rows) != 121 {
		t.Fatalf("data rows = %d", len(rows))
	}
	title, _ := f.GetCellValue("Summary", "A1")
	label, _ := f.GetCellValue("Summary", "A5")
	count, _ := f.GetCellValue("Summary", "B5")
	if title != "Orders" || label != "Rows" || count != "120" {
		t.Fatalf("summary = %q %q %q", title, label, count)
	}
	zr, err := zip.NewReader(bytes.NewReader(raw), int64(len(raw)))
	if err != nil {
		t.Fatal(err)
	}
	charts, drawings, types := []string{}, 0, ""
	for _, zf := range zr.File {
		switch {
		case regexp.MustCompile(`^xl/charts/chart\d+\.xml$`).MatchString(zf.Name):
			rc, _ := zf.Open()
			var b bytes.Buffer
			_, _ = b.ReadFrom(rc)
			_ = rc.Close()
			charts = append(charts, b.String())
		case strings.HasPrefix(zf.Name, "xl/drawings/drawing"):
			drawings++
		case zf.Name == "[Content_Types].xml":
			rc, _ := zf.Open()
			var b bytes.Buffer
			_, _ = b.ReadFrom(rc)
			_ = rc.Close()
			types = b.String()
		}
	}
	// Status breakdown (pie), total by month (line), top 10 by amount (bar).
	if len(charts) != 3 || drawings == 0 || !strings.Contains(types, "drawingml.chart+xml") {
		t.Fatalf("charts = %d, drawings = %d", len(charts), drawings)
	}
	all := strings.Join(charts, "\n")
	for _, want := range []string{"<pieChart>", "<lineChart>", "<barChart>", "Summary!$A$", "Rows by status", "Total by month", "Top 10 by total"} {
		if !strings.Contains(all, want) {
			t.Errorf("charts lack %q", want)
		}
	}
}

func TestXLSXWithoutRowsHasNoCharts(t *testing.T) {
	f, err := excelize.OpenReader(bytes.NewReader(render(t, "xlsx", 0)))
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = f.Close() }()
	note, _ := f.GetCellValue("Summary", "A8")
	if !strings.HasPrefix(note, "No charts") {
		t.Fatalf("note = %q", note)
	}
}

var pageObject = regexp.MustCompile(`/Type /Page\b[^s]`)

// TestPDFHasSummaryPageAndPagedTable checks the PDF is well-formed and has the summary page plus as many table pages
// as the rows need.
func TestPDFHasSummaryPageAndPagedTable(t *testing.T) {
	small := render(t, "pdf", 10)
	if !bytes.HasPrefix(small, []byte("%PDF-")) || !bytes.Contains(small, []byte("%%EOF")) || len(small) < 2000 {
		t.Fatalf("pdf = %d bytes", len(small))
	}
	if n := len(pageObject.FindAll(small, -1)); n != 2 {
		t.Fatalf("small pdf pages = %d, want summary + 1 table page", n)
	}
	big := render(t, "pdf", 200)
	pages := len(pageObject.FindAll(big, -1))
	// 200 rows at ~30 rows per landscape Letter page: 7 table pages plus the summary.
	if pages < 7 || pages > 10 {
		t.Fatalf("big pdf pages = %d", pages)
	}
}
