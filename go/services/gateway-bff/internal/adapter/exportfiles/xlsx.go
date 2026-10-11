package exportfiles

import (
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/xuri/excelize/v2"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// XLSX exports: a "Summary" sheet (first) with the key totals and native Excel charts over small aggregate tables, and
// a "Data" sheet streamed row by row (typed cells, frozen header, an Excel table with filter buttons).

const (
	summarySheet = "Summary"
	dataSheet    = "Data"
	// chartRows is how many sheet rows one chart block takes on the Summary sheet.
	chartRows = 18
)

type xlsxWriter struct {
	out      io.Writer
	meta     domain.ExportMeta
	f        *excelize.File
	sw       *excelize.StreamWriter
	analyzer domain.ExportAnalyzer
	row      int
	styles   map[string]int
}

func newXLSXWriter(meta domain.ExportMeta, out io.Writer) (port.ExportFileWriter, error) {
	f := excelize.NewFile()
	x := &xlsxWriter{out: out, meta: meta, f: f, analyzer: domain.NewExportAnalyzer(meta.Columns), styles: map[string]int{}}
	if err := f.SetSheetName("Sheet1", summarySheet); err != nil {
		return nil, fmt.Errorf("export: xlsx summary sheet: %w", err)
	}
	if _, err := f.NewSheet(dataSheet); err != nil {
		return nil, fmt.Errorf("export: xlsx data sheet: %w", err)
	}
	if err := x.makeStyles(); err != nil {
		return nil, err
	}
	sw, err := f.NewStreamWriter(dataSheet)
	if err != nil {
		return nil, fmt.Errorf("export: xlsx stream: %w", err)
	}
	x.sw = sw
	for i, c := range meta.Columns {
		if err := sw.SetColWidth(i+1, i+1, columnWidth(c)); err != nil {
			return nil, fmt.Errorf("export: xlsx width: %w", err)
		}
	}
	if err := sw.SetPanes(&excelize.Panes{Freeze: true, YSplit: 1, TopLeftCell: "A2", ActivePane: "bottomLeft"}); err != nil {
		return nil, fmt.Errorf("export: xlsx panes: %w", err)
	}
	header := make([]any, len(meta.Columns))
	seen := map[string]int{}
	for i, c := range meta.Columns {
		label := c.Label
		if label == "" {
			label = c.Key
		}
		// Excel table headers must be unique.
		if n := seen[label]; n > 0 {
			label += " " + strconv.Itoa(n+1)
		}
		seen[c.Label]++
		header[i] = excelize.Cell{StyleID: x.styles["header"], Value: label}
	}
	if err := x.setRow(header); err != nil {
		return nil, err
	}
	return x, nil
}

func columnWidth(c domain.ExportColumn) float64 {
	switch c.Kind {
	case "money", "number", "percent":
		return 14
	case "date":
		return 13
	case "datetime":
		return 18
	case "status":
		return 18
	case "mono":
		return 18
	default:
		return float64(min(max(len(c.Label)+6, 16), 40))
	}
}

func (x *xlsxWriter) makeStyles() error {
	bold := &excelize.Font{Bold: true, Color: "FFFFFF"}
	defs := map[string]*excelize.Style{
		"header":   {Font: bold, Fill: excelize.Fill{Type: "pattern", Pattern: 1, Color: []string{domain.ExportPalette[2]}}, Alignment: &excelize.Alignment{Vertical: "center"}},
		"money":    {CustomNumFmt: strPtr(`"$"#,##0.00`)},
		"number":   {CustomNumFmt: strPtr(`#,##0.##`)},
		"percent":  {CustomNumFmt: strPtr(`0.###%`)},
		"date":     {CustomNumFmt: strPtr(`mmm d, yyyy`)},
		"datetime": {CustomNumFmt: strPtr(`mmm d, yyyy hh:mm`)},
		"title":    {Font: &excelize.Font{Bold: true, Size: 16}},
		"muted":    {Font: &excelize.Font{Color: domain.ExportPalette[5], Size: 10}},
		"label":    {Font: &excelize.Font{Bold: true}},
		"kpi":      {Font: &excelize.Font{Bold: true, Size: 14}, CustomNumFmt: strPtr(`#,##0.##`)},
		"kpiMoney": {Font: &excelize.Font{Bold: true, Size: 14}, CustomNumFmt: strPtr(`"$"#,##0.00`)},
		"th":       {Font: &excelize.Font{Bold: true}, Fill: excelize.Fill{Type: "pattern", Pattern: 1, Color: []string{"EEEBE4"}}},
	}
	for k, st := range defs {
		id, err := x.f.NewStyle(st)
		if err != nil {
			return fmt.Errorf("export: xlsx style %s: %w", k, err)
		}
		x.styles[k] = id
	}
	return nil
}

func strPtr(s string) *string { return &s }

func (x *xlsxWriter) setRow(cells []any) error {
	x.row++
	cell, err := excelize.CoordinatesToCellName(1, x.row)
	if err != nil {
		return fmt.Errorf("export: xlsx cell: %w", err)
	}
	if err := x.sw.SetRow(cell, cells); err != nil {
		return fmt.Errorf("export: xlsx row: %w", err)
	}
	return nil
}

// xmlSafe drops characters XML 1.0 cannot carry.
func xmlSafe(s string) string {
	return strings.Map(func(r rune) rune {
		if r == '\t' || r == '\n' || r == '\r' || r >= 0x20 && r != 0xFFFE && r != 0xFFFF {
			return r
		}
		return -1
	}, s)
}

func (x *xlsxWriter) Row(cells []any) error {
	x.analyzer.Add(cells)
	out := make([]any, len(cells))
	for i, v := range cells {
		kind := ""
		if i < len(x.meta.Columns) {
			kind = x.meta.Columns[i].Kind
		}
		out[i] = x.typed(kind, v)
	}
	return x.setRow(out)
}

// typed writes numbers and dates as real spreadsheet values (sums and date filters work) and text as text.
func (x *xlsxWriter) typed(kind string, v any) any {
	switch n := v.(type) {
	case nil:
		return nil
	case float64:
		switch kind {
		case "money", "number", "percent":
			return excelize.Cell{StyleID: x.styles[kind], Value: n}
		default:
			return n
		}
	case int, int64:
		return n
	case bool:
		return cellText(n)
	case string:
		if kind == "date" || kind == "datetime" {
			if t, ok := domain.ParseCellTime(n); ok {
				return excelize.Cell{StyleID: x.styles[kind], Value: t.UTC()}
			}
		}
		return xmlSafe(n)
	default:
		return xmlSafe(cellText(v))
	}
}

func (x *xlsxWriter) Close() error {
	defer func() { _ = x.f.Close() }()
	if len(x.meta.Columns) > 0 {
		last, err := excelize.CoordinatesToCellName(len(x.meta.Columns), max(x.row, 2))
		if err != nil {
			return fmt.Errorf("export: xlsx range: %w", err)
		}
		if err := x.sw.AddTable(&excelize.Table{Range: "A1:" + last, Name: "ExportData", StyleName: "TableStyleLight9"}); err != nil {
			return fmt.Errorf("export: xlsx table: %w", err)
		}
	}
	if err := x.sw.Flush(); err != nil {
		return fmt.Errorf("export: xlsx flush: %w", err)
	}
	if err := x.writeSummary(x.analyzer.Summary()); err != nil {
		return err
	}
	x.f.SetActiveSheet(0)
	if _, err := x.f.WriteTo(x.out); err != nil {
		return fmt.Errorf("export: xlsx write: %w", err)
	}
	return nil
}

// writeSummary lays out the title, scope, key totals and one block per chart: its aggregate table on the left and the
// native chart (referencing that table) on the right.
func (x *xlsxWriter) writeSummary(s domain.ExportSummary) error {
	f, sh := x.f, summarySheet
	set := func(cell string, v any, style string) error {
		if err := f.SetCellValue(sh, cell, v); err != nil {
			return fmt.Errorf("export: xlsx summary: %w", err)
		}
		if style != "" {
			if err := f.SetCellStyle(sh, cell, cell, x.styles[style]); err != nil {
				return fmt.Errorf("export: xlsx summary style: %w", err)
			}
		}
		return nil
	}
	// Print (and PDF conversion) fits the Summary's width on one landscape page.
	landscape, one, auto := "landscape", 1, 0
	if err := f.SetPageLayout(sh, &excelize.PageLayoutOptions{Orientation: &landscape, FitToWidth: &one, FitToHeight: &auto}); err != nil {
		return fmt.Errorf("export: xlsx summary layout: %w", err)
	}
	if err := f.SetSheetProps(sh, &excelize.SheetPropsOptions{FitToPage: boolPtr(true)}); err != nil {
		return fmt.Errorf("export: xlsx summary layout: %w", err)
	}
	if err := f.SetColWidth(sh, "A", "A", 34); err != nil {
		return fmt.Errorf("export: xlsx summary width: %w", err)
	}
	if err := f.SetColWidth(sh, "B", "B", 16); err != nil {
		return fmt.Errorf("export: xlsx summary width: %w", err)
	}
	if err := set("A1", xmlSafe(x.meta.Title), "title"); err != nil {
		return err
	}
	sub := "Generated " + x.meta.GeneratedAt.UTC().Format("Jan 2, 2006 15:04 UTC")
	if x.meta.Scope != "" {
		sub += " · " + x.meta.Scope
	}
	if err := set("A2", xmlSafe(sub), "muted"); err != nil {
		return err
	}
	row := 4
	if err := set("A"+strconv.Itoa(row), "Key figures", "th"); err != nil {
		return err
	}
	if err := set("B"+strconv.Itoa(row), "", "th"); err != nil {
		return err
	}
	for _, t := range s.Totals {
		row++
		style := "kpi"
		if t.Kind == "money" {
			style = "kpiMoney"
		}
		if err := set("A"+strconv.Itoa(row), t.Label, "label"); err != nil {
			return err
		}
		if err := set("B"+strconv.Itoa(row), t.Value, style); err != nil {
			return err
		}
	}
	row += 2
	if len(s.Charts) == 0 {
		return set("A"+strconv.Itoa(row), "No charts: the export has no rows or no status, date or amount columns.", "muted")
	}
	for i, c := range s.Charts {
		if err := x.chartBlock(row, i, c, set); err != nil {
			return err
		}
		row += max(chartRows, len(c.Points)+3)
	}
	return nil
}

func (x *xlsxWriter) chartBlock(row, index int, c domain.ExportChart, set func(string, any, string) error) error {
	r := strconv.Itoa
	if err := set("A"+r(row), xmlSafe(c.Title), "label"); err != nil {
		return err
	}
	head := row + 1
	if err := set("A"+r(head), xmlSafe(c.CategoryLabel), "th"); err != nil {
		return err
	}
	if err := set("B"+r(head), xmlSafe(c.ValueLabel), "th"); err != nil {
		return err
	}
	style := "number"
	if c.ValueKind == "money" {
		style = "money"
	}
	for i, p := range c.Points {
		if err := set("A"+r(head+1+i), xmlSafe(p.Label), ""); err != nil {
			return err
		}
		if err := set("B"+r(head+1+i), p.Value, style); err != nil {
			return err
		}
	}
	first, last := head+1, head+len(c.Points)
	series := excelize.ChartSeries{
		Name:       fmt.Sprintf("%s!$B$%d", summarySheet, head),
		Categories: fmt.Sprintf("%s!$A$%d:$A$%d", summarySheet, first, last),
		Values:     fmt.Sprintf("%s!$B$%d:$B$%d", summarySheet, first, last),
		Fill:       excelize.Fill{Type: "pattern", Pattern: 1, Color: []string{domain.ExportPalette[index%len(domain.ExportPalette)]}},
	}
	chart := &excelize.Chart{
		Series:    []excelize.ChartSeries{series},
		Title:     excelize.ChartTitle{Paragraph: []excelize.RichTextRun{{Text: xmlSafe(c.Title)}}},
		Dimension: excelize.ChartDimension{Width: 560, Height: 300},
		Legend:    excelize.ChartLegend{Position: "none"},
		Format:    excelize.GraphicOptions{AltText: xmlSafe(c.Title), PrintObject: boolPtr(true)},
		PlotArea:  excelize.ChartPlotArea{ShowVal: len(c.Points) <= 12},
	}
	numFmt := excelize.ChartNumFmt{CustomNumFmt: "#,##0"}
	if c.ValueKind == "money" {
		numFmt = excelize.ChartNumFmt{CustomNumFmt: `"$"#,##0`}
	}
	chart.YAxis = excelize.ChartAxis{MajorGridLines: true, NumFmt: numFmt}
	chart.PlotArea.NumFmt = numFmt
	switch c.Type {
	case domain.ChartPie:
		chart.Type = excelize.Pie
		chart.Legend = excelize.ChartLegend{Position: "right"}
		chart.PlotArea = excelize.ChartPlotArea{ShowPercent: true}
		chart.VaryColors = boolPtr(true)
		chart.Series[0].Fill = excelize.Fill{}
		for i := range c.Points {
			chart.Series[0].DataPoint = append(chart.Series[0].DataPoint, excelize.ChartDataPoint{Index: i,
				Fill: excelize.Fill{Type: "pattern", Pattern: 1, Color: []string{domain.ExportPalette[i%len(domain.ExportPalette)]}}})
		}
	case domain.ChartLine:
		chart.Type = excelize.Line
		chart.Series[0].Line = excelize.LineOptions{Width: 2.25, Fill: series.Fill}
		chart.Series[0].Marker = excelize.ChartMarker{Symbol: "circle", Size: 5}
		chart.PlotArea.ShowVal = false
	default:
		// Horizontal bars read best for long category labels (seller names, product titles).
		chart.Type = excelize.Bar
		chart.XAxis = excelize.ChartAxis{ReverseOrder: true}
	}
	if err := x.f.AddChart(summarySheet, "D"+r(row), chart); err != nil {
		return fmt.Errorf("export: xlsx chart %q: %w", c.Title, err)
	}
	return nil
}

func boolPtr(b bool) *bool { return &b }
