package exportfiles

import (
	"fmt"
	"io"
	"math"
	"strconv"
	"strings"

	"codeberg.org/go-pdf/fpdf"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// PDF exports: US Letter landscape. Page one carries the title, scope, key totals and the summary charts (drawn as
// vector graphics, so they stay sharp when printed or zoomed); the table follows with its header repeated on every
// page. Rows are buffered (capped at domain.ExportPDFMaxRows) because the summary page comes first.

const (
	pdfMargin   = 12.0
	pdfRowH     = 6.0
	pdfHeadH    = 7.5
	pdfFontSize = 8.0
)

type rgb struct{ r, g, b int }

func hexRGB(h string) rgb {
	n, _ := strconv.ParseUint(strings.TrimPrefix(h, "#"), 16, 32)
	return rgb{int(n >> 16 & 0xFF), int(n >> 8 & 0xFF), int(n & 0xFF)}
}

// Token colours (ink-900 text, ink-600 muted, ink-200 borders, ink-50 header and zebra fill).
var (
	pdfText   = hexRGB("191816")
	pdfMuted  = hexRGB("5D5951")
	pdfBorder = hexRGB("E0DCD2")
	pdfFill   = hexRGB("F6F4EF")
)

type pdfWriter struct {
	out      io.Writer
	meta     domain.ExportMeta
	analyzer domain.ExportAnalyzer
	rows     [][]string
	total    int
}

func newPDFWriter(meta domain.ExportMeta, out io.Writer) port.ExportFileWriter {
	return &pdfWriter{out: out, meta: meta, analyzer: domain.NewExportAnalyzer(meta.Columns)}
}

func (p *pdfWriter) Row(cells []any) error {
	p.analyzer.Add(cells)
	p.total++
	if len(p.rows) >= domain.ExportPDFMaxRows {
		return nil
	}
	texts := make([]string, len(p.meta.Columns))
	for i, c := range p.meta.Columns {
		var v any
		if i < len(cells) {
			v = cells[i]
		}
		texts[i] = displayText(c.Kind, v)
	}
	p.rows = append(p.rows, texts)
	return nil
}

type pdfDoc struct {
	*fpdf.Fpdf
	tr     func(string) string
	w, h   float64
	inner  float64
	widths []float64
	cols   []domain.ExportColumn
}

func (d *pdfDoc) color(c rgb, fill bool) {
	if fill {
		d.SetFillColor(c.r, c.g, c.b)
	} else {
		d.SetTextColor(c.r, c.g, c.b)
	}
}

func (d *pdfDoc) font(style string, size float64) { d.SetFont("Helvetica", style, size) }

// fit truncates s with an ellipsis to width w at the current font.
func (d *pdfDoc) fit(s string, w float64) string {
	s = d.tr(s)
	if d.GetStringWidth(s) <= w {
		return s
	}
	ell := d.tr("…")
	r := []rune(s)
	for len(r) > 0 && d.GetStringWidth(string(r)+ell) > w {
		r = r[:len(r)-1]
	}
	return string(r) + ell
}

func (p *pdfWriter) Close() error {
	f := fpdf.New("L", "mm", "Letter", "")
	f.SetMargins(pdfMargin, pdfMargin, pdfMargin)
	f.SetAutoPageBreak(false, pdfMargin)
	f.SetTitle(p.meta.Title, true)
	f.SetCreator("AfroTransact", true)
	f.AliasNbPages("{nb}")
	w, h := f.GetPageSize()
	d := &pdfDoc{Fpdf: f, tr: f.UnicodeTranslatorFromDescriptor(""), w: w, h: h, inner: w - 2*pdfMargin, cols: p.meta.Columns}
	f.SetFooterFunc(func() {
		d.SetY(h - pdfMargin + 3)
		d.font("", 7)
		d.color(pdfMuted, false)
		d.CellFormat(d.inner/2, 4, d.fit(p.meta.Title, d.inner/2), "", 0, "L", false, 0, "")
		d.CellFormat(d.inner/2, 4, d.tr(fmt.Sprintf("Page %d of {nb}", d.PageNo())), "", 0, "R", false, 0, "")
	})
	p.summaryPage(d, p.analyzer.Summary())
	p.table(d)
	if err := f.Error(); err != nil {
		return fmt.Errorf("export: pdf: %w", err)
	}
	if err := f.Output(p.out); err != nil {
		return fmt.Errorf("export: pdf write: %w", err)
	}
	return nil
}

func (p *pdfWriter) summaryPage(d *pdfDoc, s domain.ExportSummary) {
	d.AddPage()
	d.color(pdfText, false)
	d.font("B", 18)
	d.CellFormat(d.inner, 9, d.fit(p.meta.Title, d.inner), "", 1, "L", false, 0, "")
	d.font("", 9)
	d.color(pdfMuted, false)
	sub := "Generated " + p.meta.GeneratedAt.UTC().Format("Jan 2, 2006 15:04 UTC")
	if p.meta.Scope != "" {
		sub += " · " + p.meta.Scope
	}
	if p.total > len(p.rows) {
		sub += fmt.Sprintf(" · the table lists the first %s of %s rows; the summary covers all of them", numberText(float64(len(p.rows))), numberText(float64(p.total)))
	}
	d.CellFormat(d.inner, 5, d.fit(sub, d.inner), "", 1, "L", false, 0, "")
	// Key figures.
	y := d.GetY() + 4
	n := len(s.Totals)
	gap := 4.0
	bw := (d.inner - gap*float64(n-1)) / float64(n)
	for i, t := range s.Totals {
		x := pdfMargin + float64(i)*(bw+gap)
		d.color(pdfFill, true)
		d.RoundedRect(x, y, bw, 18, 2, "1234", "F")
		d.SetXY(x+3, y+3)
		d.font("", 8)
		d.color(pdfMuted, false)
		d.CellFormat(bw-6, 4, d.fit(t.Label, bw-6), "", 0, "L", false, 0, "")
		d.SetXY(x+3, y+8)
		d.font("B", 14)
		d.color(pdfText, false)
		d.CellFormat(bw-6, 7, d.fit(totalText(t), bw-6), "", 0, "L", false, 0, "")
	}
	y += 24
	if len(s.Charts) == 0 {
		d.SetXY(pdfMargin, y)
		d.font("", 10)
		d.color(pdfMuted, false)
		d.CellFormat(d.inner, 6, d.tr("No charts: the export has no rows or no status, date or amount columns."), "", 1, "L", false, 0, "")
		return
	}
	// Up to four charts in a 2×2 grid.
	cw := (d.inner - 8) / 2
	ch := (d.h - pdfMargin - 6 - y - 6) / 2
	for i, c := range s.Charts {
		x := pdfMargin + float64(i%2)*(cw+8)
		cy := y + float64(i/2)*(ch+6)
		drawChart(d, c, x, cy, cw, ch, i)
	}
}

func drawChart(d *pdfDoc, c domain.ExportChart, x, y, w, h float64, index int) {
	d.SetDrawColor(pdfBorder.r, pdfBorder.g, pdfBorder.b)
	d.SetLineWidth(0.2)
	d.RoundedRect(x, y, w, h, 2, "1234", "D")
	keyW := 0.0
	if c.Type != domain.ChartPie && c.ValueLabel != "" {
		keyW = drawKey(d, c, x+w-4, y+3, w*0.4, index)
	}
	d.SetXY(x+4, y+3)
	d.font("B", 10)
	d.color(pdfText, false)
	d.CellFormat(w-8-keyW, 5, d.fit(c.Title, w-8-keyW), "", 0, "L", false, 0, "")
	area := struct{ x, y, w, h float64 }{x + 4, y + 11, w - 8, h - 15}
	switch c.Type {
	case domain.ChartPie:
		drawPie(d, c, area.x, area.y, area.w, area.h)
	case domain.ChartLine:
		drawLine(d, c, area.x, area.y, area.w, area.h, index)
	default:
		drawBars(d, c, area.x, area.y, area.w, area.h, index)
	}
}

// drawKey is the legend of a bar or line chart: a swatch in the series colour and its name, right-aligned at (right, y).
// It returns the width it took so the title can leave room (S196).
func drawKey(d *pdfDoc, c domain.ExportChart, right, y, maxW float64, index int) float64 {
	d.font("", 7.5)
	label := d.fit(c.ValueLabel, maxW-6)
	w := d.GetStringWidth(d.tr(label)) + 6
	x := right - w
	d.color(hexRGB(domain.ExportPalette[index%len(domain.ExportPalette)]), true)
	d.Rect(x, y+1.2, 3.2, 3.2, "F")
	d.color(pdfMuted, false)
	d.SetXY(x+4.4, y)
	d.CellFormat(w-4.4, 5, d.tr(label), "", 0, "L", false, 0, "")
	return w + 2
}

func valueText(kind string, v float64) string {
	if kind == "money" {
		return moneyText(v)
	}
	return numberText(v)
}

func drawBars(d *pdfDoc, c domain.ExportChart, x, y, w, h float64, index int) {
	n := len(c.Points)
	if n == 0 {
		return
	}
	maxV := 0.0
	for _, p := range c.Points {
		maxV = math.Max(maxV, p.Value)
	}
	if maxV <= 0 {
		maxV = 1
	}
	labelW := w * 0.32
	valueW := 22.0
	barArea := w - labelW - valueW
	slot := h / float64(n)
	barH := math.Min(slot*0.7, 6)
	col := hexRGB(domain.ExportPalette[index%len(domain.ExportPalette)])
	d.font("", 7)
	for i, p := range c.Points {
		by := y + float64(i)*slot + (slot-barH)/2
		d.color(pdfMuted, false)
		d.SetXY(x, by)
		d.CellFormat(labelW-2, barH, d.fit(p.Label, labelW-2), "", 0, "R", false, 0, "")
		bw := math.Max(barArea*p.Value/maxV, 0.4)
		d.color(col, true)
		d.Rect(x+labelW, by, bw, barH, "F")
		d.color(pdfText, false)
		d.SetXY(x+labelW+bw+1, by)
		d.CellFormat(valueW, barH, d.tr(valueText(c.ValueKind, p.Value)), "", 0, "L", false, 0, "")
	}
}

func drawPie(d *pdfDoc, c domain.ExportChart, x, y, w, h float64) {
	total := 0.0
	for _, p := range c.Points {
		total += p.Value
	}
	if total <= 0 {
		return
	}
	r := math.Min(h/2, w*0.22)
	cx, cy := x+r+2, y+h/2
	start := -math.Pi / 2
	for i, p := range c.Points {
		sweep := 2 * math.Pi * p.Value / total
		pts := []fpdf.PointType{{X: cx, Y: cy}}
		steps := max(int(sweep/(math.Pi/90)), 2)
		for s := 0; s <= steps; s++ {
			a := start + sweep*float64(s)/float64(steps)
			pts = append(pts, fpdf.PointType{X: cx + r*math.Cos(a), Y: cy + r*math.Sin(a)})
		}
		d.color(hexRGB(domain.ExportPalette[i%len(domain.ExportPalette)]), true)
		d.Polygon(pts, "F")
		start += sweep
	}
	// Legend.
	lx := cx + r + 8
	ly := y + math.Max(0, h/2-float64(len(c.Points))*3)
	d.font("", 7.5)
	for i, p := range c.Points {
		d.color(hexRGB(domain.ExportPalette[i%len(domain.ExportPalette)]), true)
		d.Rect(lx, ly+float64(i)*6+1, 3.5, 3.5, "F")
		d.color(pdfText, false)
		d.SetXY(lx+5, ly+float64(i)*6)
		pct := 100 * p.Value / total
		d.CellFormat(x+w-lx-5, 5.5, d.fit(fmt.Sprintf("%s  %s (%.0f%%)", p.Label, numberText(p.Value), pct), x+w-lx-5), "", 0, "L", false, 0, "")
	}
}

func drawLine(d *pdfDoc, c domain.ExportChart, x, y, w, h float64, index int) {
	n := len(c.Points)
	if n == 0 {
		return
	}
	maxV := 0.0
	for _, p := range c.Points {
		maxV = math.Max(maxV, p.Value)
	}
	if maxV <= 0 {
		maxV = 1
	}
	maxV = niceCeil(maxV)
	axisW := 20.0
	px, pw, ph := x+axisW, w-axisW-2, h-7
	d.font("", 6.5)
	d.SetDrawColor(pdfBorder.r, pdfBorder.g, pdfBorder.b)
	d.SetLineWidth(0.15)
	for g := 0; g <= 4; g++ {
		gy := y + ph - ph*float64(g)/4
		d.Line(px, gy, px+pw, gy)
		d.color(pdfMuted, false)
		d.SetXY(x, gy-2)
		d.CellFormat(axisW-2, 4, d.tr(valueText(c.ValueKind, maxV*float64(g)/4)), "", 0, "R", false, 0, "")
	}
	pt := func(i int) (float64, float64) {
		fx := px + pw/2
		if n > 1 {
			fx = px + pw*float64(i)/float64(n-1)
		}
		return fx, y + ph - ph*c.Points[i].Value/maxV
	}
	col := hexRGB(domain.ExportPalette[index%len(domain.ExportPalette)])
	d.SetDrawColor(col.r, col.g, col.b)
	d.SetLineWidth(0.6)
	for i := 1; i < n; i++ {
		x1, y1 := pt(i - 1)
		x2, y2 := pt(i)
		d.Line(x1, y1, x2, y2)
	}
	d.color(col, true)
	step := max(1, n/6)
	for i := 0; i < n; i++ {
		fx, fy := pt(i)
		if n <= 40 {
			d.Circle(fx, fy, 0.7, "F")
		}
		if i%step == 0 || i == n-1 {
			d.color(pdfMuted, false)
			d.SetXY(fx-10, y+ph+1.5)
			d.CellFormat(20, 4, d.tr(c.Points[i].Label), "", 0, "C", false, 0, "")
		}
	}
}

// table writes the rows with the header repeated on every page.
func (p *pdfWriter) table(d *pdfDoc) {
	if len(p.meta.Columns) == 0 {
		return
	}
	weights := make([]float64, len(p.meta.Columns))
	sum := 0.0
	for i, c := range p.meta.Columns {
		switch c.Kind {
		case "money", "number", "percent":
			weights[i] = 1
		case "date":
			weights[i] = 1.1
		case "datetime", "status", "mono":
			weights[i] = 1.4
		case "strong":
			weights[i] = 2.2
		default:
			weights[i] = 1.8
		}
		sum += weights[i]
	}
	d.widths = make([]float64, len(weights))
	for i, wt := range weights {
		d.widths[i] = d.inner * wt / sum
	}
	header := func() {
		d.AddPage()
		d.font("B", pdfFontSize)
		d.color(pdfFill, true)
		d.color(pdfText, false)
		d.SetDrawColor(pdfBorder.r, pdfBorder.g, pdfBorder.b)
		for i, c := range p.meta.Columns {
			d.CellFormat(d.widths[i], pdfHeadH, d.fit(c.Label, d.widths[i]-2), "B", 0, alignOf(c.Kind), true, 0, "")
		}
		d.Ln(pdfHeadH)
		d.font("", pdfFontSize)
	}
	header()
	if len(p.rows) == 0 {
		d.color(pdfMuted, false)
		d.CellFormat(d.inner, pdfRowH*2, d.tr("No rows match this export."), "", 1, "C", false, 0, "")
		return
	}
	bottom := d.h - pdfMargin - 2
	for r, row := range p.rows {
		if d.GetY()+pdfRowH > bottom {
			header()
		}
		zebra := r%2 == 1
		d.color(pdfFill, true)
		d.color(pdfText, false)
		for i, c := range p.meta.Columns {
			d.CellFormat(d.widths[i], pdfRowH, d.fit(row[i], d.widths[i]-2), "B", 0, alignOf(c.Kind), zebra, 0, "")
		}
		d.Ln(pdfRowH)
	}
}

func alignOf(kind string) string {
	switch kind {
	case "money", "number", "percent":
		return "R"
	default:
		return "L"
	}
}

// niceCeil rounds an axis maximum up to 1, 2, 2.5 or 5 × 10^k so gridline labels are round numbers.
func niceCeil(v float64) float64 {
	if v <= 0 {
		return 1
	}
	exp := math.Pow(10, math.Floor(math.Log10(v)))
	for _, m := range []float64{1, 2, 2.5, 5, 10} {
		if v <= m*exp {
			return m * exp
		}
	}
	return 10 * exp
}
