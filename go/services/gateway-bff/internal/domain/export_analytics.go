package domain

import (
	"cmp"
	"fmt"
	"slices"
	"strings"
	"time"
)

// Exports carry a quick-analytics summary: key totals and up to four charts chosen from the column kinds — counts by
// status (pie, or bar past six values), an amount (or row count) by day or month (line), the top ten rows by amount
// (bar) and counts by a low-cardinality text column (bar). XLSX files draw them as native Excel charts on a Summary
// sheet; PDFs draw them on the first page. Cells reach the analyzer as exported: money in currency units.

// Export formats.
const (
	ExportCSV  = "csv"
	ExportXLSX = "xlsx"
	ExportPDF  = "pdf"
	// ExportPDFMaxRows caps the table pages of a PDF (the summary still covers every exported row).
	ExportPDFMaxRows = 10_000
	// ExportInlineMaxRows caps the rows a client may send for a client-side table.
	ExportInlineMaxRows = 5000
	// ExportMaxColumns caps the columns of an export.
	ExportMaxColumns = 40
)

// IsExportFormat reports whether f is csv, xlsx or pdf.
func IsExportFormat(f string) bool { return f == ExportCSV || f == ExportXLSX || f == ExportPDF }

// ExportContentType is the media type of a format.
func ExportContentType(format string) string {
	switch format {
	case ExportXLSX:
		return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
	case ExportPDF:
		return "application/pdf"
	default:
		return "text/csv; charset=utf-8"
	}
}

// ExportColumn is one exported column. Kind is a table column kind (text, strong, mono, money, number, percent, date,
// datetime, status).
type ExportColumn struct {
	Key   string `json:"key"`
	Label string `json:"label"`
	Kind  string `json:"kind"`
}

// ExportMeta describes the file being built.
type ExportMeta struct {
	Title   string
	Columns []ExportColumn
	// Scope says which rows are in the file ("Search “ada” · sorted by Total ↓", "12 selected rows").
	Scope       string
	GeneratedAt time.Time
}

// ExportTotal is one key figure of the summary.
type ExportTotal struct {
	Label string
	Value float64
	// Kind is money, number or count.
	Kind string
}

// ExportPoint is one category or period of a chart.
type ExportPoint struct {
	Label string
	Value float64
}

// Chart types.
const (
	ChartPie  = "pie"
	ChartBar  = "bar"
	ChartLine = "line"
)

// ExportChart is one summary chart.
type ExportChart struct {
	Title         string
	Type          string
	CategoryLabel string
	ValueLabel    string
	// ValueKind is money or count.
	ValueKind string
	Points    []ExportPoint
}

// ExportSummary is the analytics of an export.
type ExportSummary struct {
	Rows   int
	Totals []ExportTotal
	Charts []ExportChart
}

// ExportPalette is the chart palette (design tokens: green-600, gold-500, ink-700, green-300, gold-700, ink-400,
// green-800, gold-300) as hex for file formats that cannot read CSS variables.
var ExportPalette = []string{"067457", "FFD400", "44413B", "57B597", "B89900", "A29D92", "044836", "FFE240"}

// ExportAnalyzer accumulates the summary while rows stream into a file.
type ExportAnalyzer interface {
	Add(cells []any)
	Summary() ExportSummary
}

const (
	maxCategories   = 12
	pieMaxSlices    = 6
	topN            = 10
	dailyBucketDays = 62
)

type categoryCount struct {
	order  []string
	counts map[string]float64
	over   bool
}

func (c *categoryCount) add(v string) {
	if c.over {
		return
	}
	if _, ok := c.counts[v]; !ok {
		if len(c.order) >= maxCategories {
			c.over = true
			return
		}
		c.order = append(c.order, v)
	}
	c.counts[v]++
}

type exportAnalyzerImpl struct {
	cols   []ExportColumn
	rows   int
	label  int
	status int
	date   int
	money  []int
	nums   []int
	texts  []int
	sums   map[int]float64
	cats   map[int]*categoryCount
	byDay  map[string]float64
	cntDay map[string]float64
	top    []ExportPoint
}

var _ ExportAnalyzer = (*exportAnalyzerImpl)(nil)

// NewExportAnalyzer returns an analyzer for the columns.
func NewExportAnalyzer(cols []ExportColumn) ExportAnalyzer {
	a := &exportAnalyzerImpl{cols: cols, label: -1, status: -1, date: -1, sums: map[int]float64{}, cats: map[int]*categoryCount{},
		byDay: map[string]float64{}, cntDay: map[string]float64{}}
	for i, c := range cols {
		switch c.Kind {
		case "status":
			if a.status < 0 {
				a.status = i
				a.cats[i] = &categoryCount{counts: map[string]float64{}}
			}
		case "date", "datetime":
			if a.date < 0 {
				a.date = i
			}
		case "money":
			a.money = append(a.money, i)
		case "number":
			if summable(c) {
				a.nums = append(a.nums, i)
			}
		case "text":
			a.texts = append(a.texts, i)
			a.cats[i] = &categoryCount{counts: map[string]float64{}}
		}
	}
	for _, kind := range []string{"strong", "mono", "text"} {
		if i := slices.IndexFunc(cols, func(c ExportColumn) bool { return c.Kind == kind }); i >= 0 {
			a.label = i
			break
		}
	}
	if a.label < 0 && len(cols) > 0 {
		a.label = 0
	}
	return a
}

// summable leaves out counters that make no sense added up (versions, sequence numbers).
func summable(c ExportColumn) bool {
	k := strings.ToLower(c.Key + " " + c.Label)
	return c.Label != "#" && !strings.Contains(k, "version") && !strings.Contains(k, "revision") && !strings.Contains(k, "seq")
}

func numberOf(v any) (float64, bool) {
	switch x := v.(type) {
	case float64:
		return x, true
	case int:
		return float64(x), true
	case int64:
		return float64(x), true
	default:
		return 0, false
	}
}

func textOf(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	default:
		return fmt.Sprint(x)
	}
}

func (a *exportAnalyzerImpl) Add(cells []any) {
	a.rows++
	cell := func(i int) any {
		if i < 0 || i >= len(cells) {
			return nil
		}
		return cells[i]
	}
	for _, i := range append(slices.Clone(a.money), a.nums...) {
		if n, ok := numberOf(cell(i)); ok {
			a.sums[i] += n
		}
	}
	for i, c := range a.cats {
		v := textOf(cell(i))
		if v == "" {
			v = "—"
		}
		if i == a.status {
			v = Humanize(v)
		}
		c.add(v)
	}
	amount, hasAmount := 0.0, false
	if len(a.money) > 0 {
		amount, hasAmount = numberOf(cell(a.money[0]))
	}
	if a.date >= 0 {
		if t, ok := ParseCellTime(textOf(cell(a.date))); ok {
			day := t.UTC().Format("2006-01-02")
			a.cntDay[day]++
			if hasAmount {
				a.byDay[day] += amount
			}
		}
	}
	if hasAmount {
		a.addTop(ExportPoint{Label: rowLabel(textOf(cell(a.label)), a.rows), Value: amount})
	}
}

func rowLabel(s string, n int) string {
	if s == "" {
		return fmt.Sprintf("Row %d", n)
	}
	if r := []rune(s); len(r) > 40 {
		return string(r[:39]) + "…"
	}
	return s
}

func (a *exportAnalyzerImpl) addTop(p ExportPoint) {
	if len(a.top) == topN && p.Value <= a.top[len(a.top)-1].Value {
		return
	}
	i, _ := slices.BinarySearchFunc(a.top, p, func(x, y ExportPoint) int { return cmp.Compare(y.Value, x.Value) })
	a.top = slices.Insert(a.top, i, p)
	if len(a.top) > topN {
		a.top = a.top[:topN]
	}
}

func (a *exportAnalyzerImpl) Summary() ExportSummary {
	s := ExportSummary{Rows: a.rows, Totals: []ExportTotal{{Label: "Rows", Value: float64(a.rows), Kind: "count"}}, Charts: []ExportChart{}}
	for _, i := range a.money[:min(len(a.money), 3)] {
		s.Totals = append(s.Totals, ExportTotal{Label: totalLabel(a.cols[i].Label), Value: a.sums[i], Kind: "money"})
	}
	for _, i := range a.nums[:min(len(a.nums), 3)] {
		s.Totals = append(s.Totals, ExportTotal{Label: totalLabel(a.cols[i].Label), Value: a.sums[i], Kind: "number"})
	}
	if a.rows == 0 {
		return s
	}
	if a.status >= 0 {
		c := a.cats[a.status]
		if !c.over && len(c.order) > 0 {
			typ := ChartPie
			if len(c.order) > pieMaxSlices {
				typ = ChartBar
			}
			s.Charts = append(s.Charts, ExportChart{Title: "Rows by " + strings.ToLower(a.cols[a.status].Label), Type: typ,
				CategoryLabel: a.cols[a.status].Label, ValueLabel: "Rows", ValueKind: "count", Points: c.points()})
		}
	}
	if a.date >= 0 && len(a.cntDay) > 0 {
		s.Charts = append(s.Charts, a.dateChart())
	}
	if len(a.top) > 1 {
		m := a.cols[a.money[0]]
		s.Charts = append(s.Charts, ExportChart{Title: "Top " + fmt.Sprint(len(a.top)) + " by " + strings.ToLower(m.Label), Type: ChartBar,
			CategoryLabel: a.cols[a.label].Label, ValueLabel: m.Label, ValueKind: "money", Points: slices.Clone(a.top)})
	}
	for _, i := range a.texts {
		if len(s.Charts) >= 4 {
			break
		}
		c := a.cats[i]
		if i == a.label || c.over || len(c.order) < 2 || len(c.order) >= a.rows {
			continue
		}
		s.Charts = append(s.Charts, ExportChart{Title: "Rows by " + strings.ToLower(a.cols[i].Label), Type: ChartBar,
			CategoryLabel: a.cols[i].Label, ValueLabel: "Rows", ValueKind: "count", Points: c.points()})
		break
	}
	return s
}

// totalLabel is "Total amount" for "Amount", and "Total" for "Total".
func totalLabel(col string) string {
	if strings.HasPrefix(strings.ToLower(col), "total") {
		return col
	}
	return "Total " + strings.ToLower(col)
}

func (c *categoryCount) points() []ExportPoint {
	out := make([]ExportPoint, 0, len(c.order))
	for _, k := range c.order {
		out = append(out, ExportPoint{Label: k, Value: c.counts[k]})
	}
	slices.SortStableFunc(out, func(x, y ExportPoint) int { return cmp.Compare(y.Value, x.Value) })
	return out
}

// dateChart sums the first amount (or counts rows) per day, or per month when the rows span more than two months.
func (a *exportAnalyzerImpl) dateChart() ExportChart {
	days := make([]string, 0, len(a.cntDay))
	for d := range a.cntDay {
		days = append(days, d)
	}
	slices.Sort(days)
	first, _ := time.Parse("2006-01-02", days[0])
	last, _ := time.Parse("2006-01-02", days[len(days)-1])
	monthly := last.Sub(first) > dailyBucketDays*24*time.Hour
	src, kind, valueLabel := a.cntDay, "count", "Rows"
	if len(a.money) > 0 && len(a.byDay) > 0 {
		src, kind, valueLabel = a.byDay, "money", a.cols[a.money[0]].Label
	}
	buckets := map[string]float64{}
	order := []string{}
	for _, d := range days {
		key := d
		if monthly {
			key = d[:7]
		}
		if _, ok := buckets[key]; !ok {
			order = append(order, key)
		}
		buckets[key] += src[d]
	}
	period, layout, in := "day", "Jan 2", "2006-01-02"
	if monthly {
		period, layout, in = "month", "Jan 2006", "2006-01"
	}
	points := make([]ExportPoint, 0, len(order))
	for _, k := range order {
		t, _ := time.Parse(in, k)
		points = append(points, ExportPoint{Label: t.Format(layout), Value: buckets[k]})
	}
	title := valueLabel + " by " + period
	if kind == "count" {
		title = "Rows by " + period
	}
	return ExportChart{Title: title, Type: ChartLine, CategoryLabel: a.cols[a.date].Label, ValueLabel: valueLabel, ValueKind: kind, Points: points}
}
