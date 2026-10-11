package domain

import (
	"slices"
	"testing"
)

func TestParseSort(t *testing.T) {
	known := func(k string) bool { return slices.Contains([]string{"total", "placedAt", "status", "id"}, k) }
	got, err := ParseSort([]string{"total,desc", "placedAt", "total,asc"}, known)
	if err != nil {
		t.Fatal(err)
	}
	if want := []SortKey{{Key: "total", Desc: true}, {Key: "placedAt"}}; !slices.Equal(got, want) {
		t.Fatalf("sort = %v, want %v", got, want)
	}
	if s := SortStrings(got); !slices.Equal(s, []string{"total,desc", "placedAt,asc"}) {
		t.Fatalf("wire = %v", s)
	}
	if got, _ := ParseSort([]string{"status,DESC;id"}, known); len(got) != 2 || !got[0].Desc {
		t.Fatalf("semicolon list = %v", got)
	}
	for _, bad := range [][]string{{"nope,asc"}, {"total,sideways"}, {"total", "placedAt", "status", "id"}} {
		if _, err := ParseSort(bad, known); err == nil {
			t.Errorf("ParseSort(%v) accepted", bad)
		}
	}
	if got, err := ParseSort(nil, known); err != nil || len(got) != 0 {
		t.Fatalf("empty = %v, %v", got, err)
	}
}

func TestMatchesSearchCoversEveryColumnAsShown(t *testing.T) {
	cols := []Column{{Key: "number", Kind: "mono"}, {Key: "customer", Kind: "strong"}, {Key: "totalMinor", Kind: "money"},
		{Key: "placedAt", Kind: "datetime"}, {Key: "status", Kind: "status"}, {Key: "qty", Kind: "number"}, {Key: "rate", Kind: "percent"}}
	cells := map[string]any{"number": "AT-000123", "customer": "Ada Obi", "totalMinor": float64(123456), "placedAt": "2026-10-11T14:05:00Z",
		"status": "AWAITING_FINAL_APPROVAL", "qty": float64(1500), "rate": 0.0825}
	for _, q := range []string{"at-000123", "ada", "obi ada", "1,234.56", "1234.56", "$1,234", "oct 11, 2026", "2026-10-11", "10/11/2026",
		"october 2026", "awaiting final", "AWAITING_FINAL", "1,500", "8.25%", "lot-9"} {
		got := MatchesSearch(cols, "lot-9", cells, SearchTerms(q))
		if !got {
			t.Errorf("%q did not match", q)
		}
	}
	for _, q := range []string{"zed", "ada zed", "1,234.57"} {
		if MatchesSearch(cols, "lot-9", cells, SearchTerms(q)) {
			t.Errorf("%q matched", q)
		}
	}
	if !MatchesSearch(cols, "x", cells, nil) {
		t.Error("an empty search matches everything")
	}
}

func TestSortRowsByKind(t *testing.T) {
	cols := []Column{{Key: "n", Kind: "mono"}, {Key: "amt", Kind: "money"}, {Key: "at", Kind: "date"}, {Key: "st", Kind: "status"}}
	rows := []Row{
		{ID: "a", Cells: map[string]any{"n": "AT-10", "amt": float64(500), "at": "2026-01-05", "st": "PAID"}},
		{ID: "b", Cells: map[string]any{"n": "AT-9", "amt": nil, "at": "2025-12-31", "st": "AWAITING_PICKUP"}},
		{ID: "c", Cells: map[string]any{"n": "AT-100", "amt": float64(20), "at": nil, "st": "CANCELLED"}},
	}
	ids := func() []string {
		out := []string{}
		for _, r := range rows {
			out = append(out, r.ID)
		}
		return out
	}
	SortRows(rows, cols, []SortKey{{Key: "n"}})
	if got := ids(); !slices.Equal(got, []string{"b", "a", "c"}) {
		t.Fatalf("natural order = %v", got)
	}
	SortRows(rows, cols, []SortKey{{Key: "amt", Desc: true}})
	if got := ids(); !slices.Equal(got, []string{"a", "c", "b"}) {
		t.Fatalf("money desc, empty last = %v", got)
	}
	SortRows(rows, cols, []SortKey{{Key: "amt"}})
	if got := ids(); !slices.Equal(got, []string{"c", "a", "b"}) {
		t.Fatalf("money asc, empty last = %v", got)
	}
	SortRows(rows, cols, []SortKey{{Key: "at", Desc: true}})
	if got := ids(); !slices.Equal(got, []string{"a", "b", "c"}) {
		t.Fatalf("dates desc = %v", got)
	}
	SortRows(rows, cols, []SortKey{{Key: "st"}})
	if got := ids(); !slices.Equal(got, []string{"b", "c", "a"}) {
		t.Fatalf("status by label = %v", got)
	}
}

func TestFilterAndPageRows(t *testing.T) {
	cols := []Column{{Key: "name", Kind: "text"}}
	rows := []Row{}
	for _, n := range []string{"alpha", "beta", "alps", "gamma", "alto"} {
		rows = append(rows, Row{ID: n, Cells: map[string]any{"name": n}})
	}
	al := FilterRows(rows, cols, SearchTerms("al"), nil)
	if len(al) != 3 {
		t.Fatalf("al = %v", al)
	}
	if sel := FilterRows(rows, cols, nil, []string{"gamma", "beta"}); len(sel) != 2 || sel[0].ID != "beta" {
		t.Fatalf("selected = %v", sel)
	}
	if both := FilterRows(rows, cols, SearchTerms("al"), []string{"gamma", "alto"}); len(both) != 1 || both[0].ID != "alto" {
		t.Fatalf("search + selection = %v", both)
	}
	p, more := PageRows(al, 1, 2)
	if len(p) != 1 || more || p[0].ID != "alto" {
		t.Fatalf("page 2 = %v %v", p, more)
	}
	if p, more := PageRows(al, 5, 2); len(p) != 0 || more {
		t.Fatalf("past the end = %v %v", p, more)
	}
}

func TestExportAnalyzerPicksChartsFromColumnKinds(t *testing.T) {
	cols := []ExportColumn{{Key: "number", Label: "Order", Kind: "mono"}, {Key: "customer", Label: "Customer", Kind: "strong"},
		{Key: "channel", Label: "Channel", Kind: "text"}, {Key: "total", Label: "Total", Kind: "money"}, {Key: "qty", Label: "Units", Kind: "number"},
		{Key: "version", Label: "Version", Kind: "number"}, {Key: "placedAt", Label: "Placed", Kind: "datetime"}, {Key: "status", Label: "Status", Kind: "status"}}
	a := NewExportAnalyzer(cols)
	statuses := []string{"PAID", "PAID", "SHIPPED", "CANCELLED", "PAID", "SHIPPED", "PAID", "PAID", "PAID", "PAID", "PAID", "PAID"}
	for i, st := range statuses {
		a.Add([]any{"AT-" + string(rune('A'+i)), "Customer", []string{"web", "app"}[i%2], float64(10 * (i + 1)), float64(2), float64(7),
			"2026-10-0" + string(rune('1'+i%9)) + "T10:00:00Z", st})
	}
	s := a.Summary()
	if s.Rows != 12 {
		t.Fatalf("rows = %d", s.Rows)
	}
	totals := map[string]float64{}
	for _, tt := range s.Totals {
		totals[tt.Label] = tt.Value
	}
	if totals["Rows"] != 12 || totals["Total"] != 780 || totals["Total units"] != 24 {
		t.Fatalf("totals = %v", s.Totals)
	}
	if _, ok := totals["Total version"]; ok {
		t.Fatal("version numbers are not summed")
	}
	if len(s.Charts) != 4 {
		t.Fatalf("charts = %+v", s.Charts)
	}
	byStatus, byDay, top, byChannel := s.Charts[0], s.Charts[1], s.Charts[2], s.Charts[3]
	if byStatus.Type != ChartPie || byStatus.Points[0] != (ExportPoint{Label: "Paid", Value: 9}) {
		t.Fatalf("status chart = %+v", byStatus)
	}
	if byDay.Type != ChartLine || byDay.ValueKind != "money" || len(byDay.Points) != 9 || byDay.Points[0].Label != "Oct 1" || byDay.Points[0].Value != 10+100 {
		t.Fatalf("date chart = %+v", byDay)
	}
	if top.Type != ChartBar || len(top.Points) != 10 || top.Points[0].Value != 120 || top.Points[9].Value != 30 {
		t.Fatalf("top chart = %+v", top)
	}
	if byChannel.Title != "Rows by channel" || len(byChannel.Points) != 2 {
		t.Fatalf("category chart = %+v", byChannel)
	}
	if empty := NewExportAnalyzer(cols).Summary(); len(empty.Charts) != 0 || empty.Rows != 0 {
		t.Fatalf("empty = %+v", empty)
	}
}
