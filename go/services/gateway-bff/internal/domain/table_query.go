package domain

import (
	"cmp"
	"fmt"
	"math"
	"slices"
	"strconv"
	"strings"
	"time"
)

// Server-driven tables share one query model: a single free-text search that matches every column the table shows
// (ids, names, SKUs, statuses, amounts and dates as text), a sort of up to MaxSortKeys columns written
// `sort=<column>,asc|desc` (the Spring Data convention, repeated for secondary keys) and page-number or cursor paging.

const (
	// MaxSortKeys caps the sort columns of one request.
	MaxSortKeys = 3
	// MaxSearchLength caps the search text.
	MaxSearchLength = 120
	// TableScanMaxRows caps how many upstream rows the BFF reads to search or sort a table whose upstream cannot do it
	// over every column; tables with more rows answer `partial: true`.
	TableScanMaxRows = 5000
	// MaxSelectedRows caps the selected rows an export may name.
	MaxSelectedRows = 1000
)

// SortKey is one column of a table sort.
type SortKey struct {
	Key  string
	Desc bool
}

// String is the wire form ("placedAt,desc").
func (k SortKey) String() string {
	if k.Desc {
		return k.Key + ",desc"
	}
	return k.Key + ",asc"
}

// ParseSort reads `sort` values ("field", "field,asc", "field,desc"); known says which fields the table can sort.
func ParseSort(raw []string, known func(key string) bool) ([]SortKey, *Problem) {
	out := []SortKey{}
	for _, r := range raw {
		for _, part := range strings.Split(r, ";") {
			part = strings.TrimSpace(part)
			if part == "" {
				continue
			}
			field, dir, _ := strings.Cut(part, ",")
			field = strings.TrimSpace(field)
			k := SortKey{Key: field}
			switch strings.ToLower(strings.TrimSpace(dir)) {
			case "", "asc":
			case "desc":
				k.Desc = true
			default:
				return nil, ValidationProblem(map[string]string{"sort": "Sort direction must be asc or desc"})
			}
			if field == "" || len(field) > 80 || (known != nil && !known(field)) {
				return nil, ValidationProblem(map[string]string{"sort": fmt.Sprintf("This table can't be sorted by %q", field)})
			}
			if slices.ContainsFunc(out, func(o SortKey) bool { return o.Key == k.Key }) {
				continue
			}
			out = append(out, k)
		}
	}
	if len(out) > MaxSortKeys {
		return nil, ValidationProblem(map[string]string{"sort": fmt.Sprintf("Sort by at most %d columns", MaxSortKeys)})
	}
	return out, nil
}

// SortStrings is the wire form of a sort.
func SortStrings(keys []SortKey) []string {
	out := make([]string, len(keys))
	for i, k := range keys {
		out[i] = k.String()
	}
	return out
}

// SearchTerms splits the search text into lower-case terms; a row matches when every term is found in some cell.
func SearchTerms(q string) []string {
	return strings.Fields(strings.ToLower(strings.TrimSpace(q)))
}

// CellTexts are the texts a cell can be found by: the raw value plus the forms the table and exports show (money in
// currency units with and without separators, dates as ISO and as "Oct 11, 2026", statuses humanised).
func CellTexts(kind string, v any) []string {
	switch x := v.(type) {
	case nil:
		return nil
	case float64:
		return numberTexts(kind, x)
	case int:
		return numberTexts(kind, float64(x))
	case int64:
		return numberTexts(kind, float64(x))
	case bool:
		if x {
			return []string{"yes", "true"}
		}
		return []string{"no", "false"}
	case string:
		return stringTexts(kind, x)
	default:
		return []string{strings.ToLower(fmt.Sprint(x))}
	}
}

func numberTexts(kind string, n float64) []string {
	switch kind {
	case "money":
		units := n / 100
		return []string{strconv.FormatFloat(units, 'f', 2, 64), groupThousands(units, 2), "$" + groupThousands(units, 2)}
	case "percent":
		return []string{strconv.FormatFloat(n*100, 'f', -1, 64) + "%", strconv.FormatFloat(n*100, 'f', -1, 64), strconv.FormatFloat(n, 'f', -1, 64)}
	default:
		return []string{strconv.FormatFloat(n, 'f', -1, 64), groupThousands(n, -1)}
	}
}

func stringTexts(kind, s string) []string {
	out := []string{strings.ToLower(s)}
	switch kind {
	case "status":
		out = append(out, strings.ToLower(Humanize(s)))
	case "date", "datetime":
		if t, ok := ParseCellTime(s); ok {
			out = append(out, strings.ToLower(t.Format("2006-01-02")), strings.ToLower(t.Format("Jan 2, 2006")),
				strings.ToLower(t.Format("2 Jan 2006")), t.Format("01/02/2006"), strings.ToLower(t.Format("January 2006")))
		}
	case "money":
		if f, err := strconv.ParseFloat(s, 64); err == nil {
			out = append(out, numberTexts("money", f)...)
		}
	}
	return out
}

// groupThousands formats n with comma separators ("12,345.60"); decimals -1 keeps the shortest form.
func groupThousands(n float64, decimals int) string {
	s := strconv.FormatFloat(math.Abs(n), 'f', decimals, 64)
	whole, frac, hasFrac := strings.Cut(s, ".")
	var b strings.Builder
	if n < 0 {
		b.WriteByte('-')
	}
	for i, r := range whole {
		if i > 0 && (len(whole)-i)%3 == 0 {
			b.WriteByte(',')
		}
		b.WriteRune(r)
	}
	if hasFrac {
		b.WriteByte('.')
		b.WriteString(frac)
	}
	return b.String()
}

// ParseCellTime reads an ISO date ("2026-10-11") or date-time cell.
func ParseCellTime(s string) (time.Time, bool) {
	for _, layout := range []string{time.RFC3339Nano, time.RFC3339, "2006-01-02T15:04:05", "2006-01-02"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t, true
		}
	}
	return time.Time{}, false
}

// MatchesSearch reports whether every search term is found in one of the row's cells (as the table shows them) or
// in its id.
func MatchesSearch(cols []Column, id string, cells map[string]any, terms []string) bool {
	return MatchesSearchIn(cols, Row{ID: id, Cells: cells}, terms)
}

// MatchesSearchIn is MatchesSearch for a row that also hides text from the table (Row.Search: the ids behind names).
func MatchesSearchIn(cols []Column, row Row, terms []string) bool {
	if len(terms) == 0 {
		return true
	}
	id, cells := row.ID, row.Cells
	texts := []string{strings.ToLower(id)}
	for _, h := range row.Search {
		texts = append(texts, strings.ToLower(h))
	}
	for _, c := range cols {
		texts = append(texts, CellTexts(c.Kind, cells[c.Key])...)
	}
	for _, term := range terms {
		if !slices.ContainsFunc(texts, func(t string) bool { return strings.Contains(t, term) }) {
			return false
		}
	}
	return true
}

// CompareCells orders two cells of a column: numbers numerically, dates chronologically, text case-insensitively;
// empty cells sort last in both directions (the caller applies the direction to non-empty pairs only).
func CompareCells(kind string, a, b any) int {
	an, bn := a == nil || a == "", b == nil || b == ""
	switch {
	case an && bn:
		return 0
	case an:
		return 1
	case bn:
		return -1
	}
	if fa, ok := asFloat(a); ok {
		if fb, ok := asFloat(b); ok {
			return cmp.Compare(fa, fb)
		}
	}
	if kind == "date" || kind == "datetime" {
		ta, oka := ParseCellTime(fmt.Sprint(a))
		tb, okb := ParseCellTime(fmt.Sprint(b))
		if oka && okb {
			return ta.Compare(tb)
		}
	}
	sa, sb := strings.ToLower(fmt.Sprint(a)), strings.ToLower(fmt.Sprint(b))
	if kind == "status" {
		sa, sb = strings.ToLower(Humanize(sa)), strings.ToLower(Humanize(sb))
	}
	return naturalCompare(sa, sb)
}

func asFloat(v any) (float64, bool) {
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

// naturalCompare orders "AT-9" before "AT-10".
func naturalCompare(a, b string) int {
	for a != "" && b != "" {
		da, db := digitPrefix(a), digitPrefix(b)
		if da != "" && db != "" {
			na, _ := strconv.ParseFloat(da, 64)
			nb, _ := strconv.ParseFloat(db, 64)
			if c := cmp.Compare(na, nb); c != 0 {
				return c
			}
			a, b = a[len(da):], b[len(db):]
			continue
		}
		if a[0] != b[0] {
			return cmp.Compare(a[0], b[0])
		}
		a, b = a[1:], b[1:]
	}
	return cmp.Compare(len(a), len(b))
}

func digitPrefix(s string) string {
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	return s[:i]
}

// SortRows sorts rows by the keys (stable: rows keep the upstream order among equals).
func SortRows(rows []Row, cols []Column, keys []SortKey) {
	if len(keys) == 0 {
		return
	}
	kinds := map[string]string{}
	for _, c := range cols {
		kinds[c.Key] = c.Kind
	}
	slices.SortStableFunc(rows, func(a, b Row) int {
		for _, k := range keys {
			va, vb := a.Cells[k.Key], b.Cells[k.Key]
			c := CompareCells(kinds[k.Key], va, vb)
			if c == 0 {
				continue
			}
			emptyA, emptyB := va == nil || va == "", vb == nil || vb == ""
			if k.Desc && !emptyA && !emptyB {
				c = -c
			}
			return c
		}
		return 0
	})
}

// FilterRows keeps the rows that match the search terms and, when ids is not empty, are among the ids.
func FilterRows(rows []Row, cols []Column, terms []string, ids []string) []Row {
	var want map[string]bool
	if len(ids) > 0 {
		want = make(map[string]bool, len(ids))
		for _, id := range ids {
			want[id] = true
		}
	}
	out := rows[:0:0]
	for _, r := range rows {
		if want != nil && !want[r.ID] {
			continue
		}
		if MatchesSearchIn(cols, r, terms) {
			out = append(out, r)
		}
	}
	return out
}

// PageRows returns one page of rows and whether more follow.
func PageRows(rows []Row, page, size int) ([]Row, bool) {
	from := min(max(page, 0)*size, len(rows))
	to := min(from+size, len(rows))
	return rows[from:to], to < len(rows)
}
