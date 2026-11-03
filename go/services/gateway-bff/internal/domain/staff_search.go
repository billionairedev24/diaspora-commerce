package domain

import (
	"cmp"
	"slices"
	"strings"
)

// Search analytics (contracts/openapi/gateway-bff-staff.yaml, tag Search analytics): what shoppers search for, which
// searches return nothing, which results get clicked, and the synonym rules that fix zero-result queries.

// Search permissions (search service; identity migration V14).
const (
	PermSearchAnalyticsView = "search:analytics.view"
	PermSearchSynonymsEdit  = "search:synonyms.edit"
)

// Limits of the search service's reports.
const (
	// SearchReportMaxRows is the most queries one search report holds (the service caps `limit` at 500).
	SearchReportMaxRows = 500
	// SearchMaxDays is the longest window the service answers.
	SearchMaxDays = 366
)

// StaffSearchMarket is a market whose searches are reported, with the name staff see.
type StaffSearchMarket struct {
	Code string `json:"code"`
	Name string `json:"name"`
}

// StaffSearchMarkets are the markets staff can report on.
type StaffSearchMarkets struct {
	Items   []StaffSearchMarket `json:"items"`
	Default string              `json:"default"`
	// CanEditSynonyms is whether the staff member may add and delete synonym rules.
	CanEditSynonyms bool `json:"canEditSynonyms"`
}

// searchMarketNames are the display names of the markets search can report on.
var searchMarketNames = map[string]string{
	"us": "United States", "ca": "Canada", "gb": "United Kingdom", "ng": "Nigeria", "gh": "Ghana", "fr": "France",
}

// SearchMarketName is the name of a search market code ("us" → United States); unknown codes show upper-cased.
func SearchMarketName(code string) string {
	code = strings.ToLower(strings.TrimSpace(code))
	if n, ok := searchMarketNames[code]; ok {
		return n
	}
	return strings.ToUpper(code)
}

// StaffQueryStat is one normalised query in a report.
type StaffQueryStat struct {
	Query       string  `json:"query"`
	Searches    int64   `json:"searches"`
	ZeroResults int64   `json:"zeroResults"`
	Clicks      int64   `json:"clicks"`
	CTR         float64 `json:"ctr"`
	// ZeroResultRate is zeroResults ÷ searches (0 without searches).
	ZeroResultRate float64 `json:"zeroResultRate"`
}

// StaffSearchWindow is the market and dates a report covers.
type StaffSearchWindow struct {
	Market     string `json:"market"`
	MarketName string `json:"marketName"`
	From       string `json:"from"`
	To         string `json:"to"`
}

// StaffQueryTotals sums a report.
type StaffQueryTotals struct {
	Searches       int64   `json:"searches"`
	ZeroResults    int64   `json:"zeroResults"`
	Clicks         int64   `json:"clicks"`
	CTR            float64 `json:"ctr"`
	ZeroResultRate float64 `json:"zeroResultRate"`
}

// StaffQueryReport is one page of a per-query report.
type StaffQueryReport struct {
	StaffSearchWindow
	Items []StaffQueryStat `json:"items"`
	Total int              `json:"total"`
	Page  int              `json:"page"`
	Size  int              `json:"size"`
	// Truncated says the service's cap (500 queries) cut the report: queries beyond it are not listed.
	Truncated bool `json:"truncated"`
	// Totals are the sums over every listed query (not only this page).
	Totals StaffQueryTotals `json:"totals"`
}

// StaffDay is one day of the click-through report.
type StaffDay struct {
	Day            string  `json:"day"`
	Searches       int64   `json:"searches"`
	Clicks         int64   `json:"clicks"`
	ZeroResults    int64   `json:"zeroResults"`
	CTR            float64 `json:"ctr"`
	ZeroResultRate float64 `json:"zeroResultRate"`
}

// StaffClickThrough is the click-through report: one page of days, the whole window's trend and its totals.
type StaffClickThrough struct {
	StaffSearchWindow
	Items []StaffDay `json:"items"`
	Total int        `json:"total"`
	Page  int        `json:"page"`
	Size  int        `json:"size"`
	// Trend is every day of the window, oldest first (for the sparkline).
	Trend  []StaffDay `json:"trend"`
	Totals StaffDay   `json:"totals"`
}

// StaffSynonymRule is a rule of the synonym set: the words search treats as the same thing.
type StaffSynonymRule struct {
	ID    string   `json:"id"`
	Terms []string `json:"terms"`
}

// StaffSynonymList is the rule set.
type StaffSynonymList struct {
	Items   []StaffSynonymRule `json:"items"`
	CanEdit bool               `json:"canEdit"`
}

// QuerySort is a parsed `<column>,asc|desc`.
type QuerySort struct {
	Column string
	Desc   bool
}

// ParseQuerySort reads the first `sort` value ("searches,desc"); an empty or malformed one gives the default.
func ParseQuerySort(vals []string, def QuerySort) QuerySort {
	if len(vals) == 0 || vals[0] == "" {
		return def
	}
	col, dir, _ := strings.Cut(vals[0], ",")
	if col == "" {
		return def
	}
	return QuerySort{Column: col, Desc: !strings.EqualFold(dir, "asc")}
}

// SortQueryStats orders a report by a column (query, searches, zeroResults, zeroResultRate, clicks, ctr); ties and
// unknown columns fall back to searches then query so pages are stable.
func SortQueryStats(items []StaffQueryStat, s QuerySort) {
	num := func(i StaffQueryStat) float64 {
		switch s.Column {
		case "zeroResults":
			return float64(i.ZeroResults)
		case "zeroResultRate":
			return i.ZeroResultRate
		case "clicks":
			return float64(i.Clicks)
		case "ctr":
			return i.CTR
		default:
			return float64(i.Searches)
		}
	}
	slices.SortStableFunc(items, func(a, b StaffQueryStat) int {
		var c int
		if s.Column == "query" {
			c = cmp.Compare(a.Query, b.Query)
		} else {
			c = cmp.Compare(num(a), num(b))
		}
		if s.Desc {
			c = -c
		}
		return cmp.Or(c, cmp.Compare(b.Searches, a.Searches), cmp.Compare(a.Query, b.Query))
	})
}

// SortDays orders days by a column (day, searches, zeroResults, zeroResultRate, clicks, ctr); ties fall back to the day.
func SortDays(items []StaffDay, s QuerySort) {
	num := func(i StaffDay) float64 {
		switch s.Column {
		case "searches":
			return float64(i.Searches)
		case "zeroResults":
			return float64(i.ZeroResults)
		case "zeroResultRate":
			return i.ZeroResultRate
		case "clicks":
			return float64(i.Clicks)
		case "ctr":
			return i.CTR
		default:
			return 0
		}
	}
	slices.SortStableFunc(items, func(a, b StaffDay) int {
		c := cmp.Compare(num(a), num(b))
		if s.Desc {
			c = -c
		}
		d := cmp.Compare(a.Day, b.Day)
		if s.Desc {
			d = -d
		}
		return cmp.Or(c, d)
	})
}

// Ratio is a ÷ b, 0 without b.
func Ratio(a, b int64) float64 {
	if b <= 0 {
		return 0
	}
	return float64(a) / float64(b)
}
