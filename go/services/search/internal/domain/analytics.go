package domain

import (
	"errors"
	"regexp"
	"strings"
	"time"
	"unicode"
)

// Event types published by search (contracts/asyncapi/fragments/search.yaml).
const (
	EventQueryPerformed = "search.query.performed"
	EventResultClicked  = "search.result.clicked"
	EventAdImpression   = "ads.impression.recorded"
	EventAdClick        = "ads.click.recorded"
)

// Event aggregates: topics at.search.events and at.ads.events.
const (
	AggregateSearch = "search"
	AggregateAds    = "ads"
)

// AnalyticsKey normalises a query for analytics and curation: lowercase, letters/digits/space/'-' only, single spaces.
func AnalyticsKey(q string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(q) {
		switch {
		case unicode.IsLetter(r), unicode.IsDigit(r), r == '-', r == '\'':
			b.WriteRune(r)
		default:
			b.WriteRune(' ')
		}
	}
	key := strings.Join(strings.Fields(b.String()), " ")
	if len(key) > 120 {
		key = key[:120]
	}
	return key
}

// QueryPerformed is the payload of search.query.performed.
type QueryPerformed struct {
	SearchID    string    `json:"searchId"`
	Market      string    `json:"market"`
	Query       string    `json:"query"`
	Normalized  string    `json:"normalizedQuery"`
	Department  string    `json:"department,omitempty"`
	Filters     []string  `json:"filters"`
	Sort        Sort      `json:"sort"`
	Page        int       `json:"page"`
	Total       int64     `json:"total"`
	ZeroResults bool      `json:"zeroResults"`
	Semantic    bool      `json:"semantic"`
	TookMs      int64     `json:"tookMs"`
	TopATINs    []string  `json:"topAtins"`
	Sponsored   []string  `json:"sponsoredAtins"`
	CustomerID  string    `json:"customerId,omitempty"`
	OccurredAt  time.Time `json:"occurredAt"`
}

// ResultClicked is the payload of search.result.clicked.
type ResultClicked struct {
	SearchID   string    `json:"searchId"`
	Market     string    `json:"market"`
	Query      string    `json:"query"`
	Normalized string    `json:"normalizedQuery"`
	ATIN       string    `json:"atin"`
	Position   int       `json:"position"`
	Sponsored  bool      `json:"sponsored"`
	CampaignID string    `json:"campaignId,omitempty"`
	CustomerID string    `json:"customerId,omitempty"`
	OccurredAt time.Time `json:"occurredAt"`
}

// Click is a result click reported by the BFF.
type Click struct {
	SearchID   string
	Market     string
	Query      string
	ATIN       string
	Position   int
	AdToken    string
	CustomerID string
}

// ClickResult tells the caller what was recorded.
type ClickResult struct {
	Recorded  bool   `json:"recorded"`
	Duplicate bool   `json:"duplicate"`
	Sponsored bool   `json:"sponsored"`
	AdStatus  string `json:"adStatus,omitempty"` // BILLABLE | DUPLICATE | EXPIRED (sponsored clicks only)
}

// ErrInvalidClick is returned for malformed click reports.
var ErrInvalidClick = errors.New("invalid click")

var (
	atinPattern     = regexp.MustCompile(`^AT[0-9A-Z]{8}$`)
	searchIDPattern = regexp.MustCompile(`^[A-Za-z0-9-]{8,64}$`)
)

// ValidATIN reports whether s looks like an ATIN.
func ValidATIN(s string) bool { return atinPattern.MatchString(s) }

// Normalize validates a click.
func (c Click) Normalize() (Click, error) {
	c.Market = strings.ToLower(strings.TrimSpace(c.Market))
	if c.Market == "" {
		c.Market = "us"
	}
	c.Query = strings.Join(strings.Fields(c.Query), " ")
	switch {
	case !searchIDPattern.MatchString(c.SearchID):
		return c, errors.Join(ErrInvalidClick, errors.New("searchId is required"))
	case !atinPattern.MatchString(c.ATIN):
		return c, errors.Join(ErrInvalidClick, errors.New("atin is not an ATIN"))
	case c.Position < 1 || c.Position > 1000:
		return c, errors.Join(ErrInvalidClick, errors.New("position must be 1–1000"))
	case len(c.Query) > 200:
		return c, errors.Join(ErrInvalidClick, errors.New("query longer than 200 characters"))
	case len(c.AdToken) > 4096:
		return c, errors.Join(ErrInvalidClick, errors.New("adToken too long"))
	}
	return c, nil
}

// AnalyticsDelta is an increment of the daily per-query aggregate.
type AnalyticsDelta struct {
	Market      string
	Day         string // YYYY-MM-DD (UTC)
	Query       string // AnalyticsKey
	Searches    int64
	ZeroResults int64
	Clicks      int64
}

// Day formats t as the aggregate's UTC day.
func Day(t time.Time) string { return t.UTC().Format(time.DateOnly) }

// AnalyticsRange selects a report window.
type AnalyticsRange struct {
	Market string
	From   time.Time // inclusive day
	To     time.Time // inclusive day
	Limit  int
}

// ErrInvalidRange is returned for bad report windows.
var ErrInvalidRange = errors.New("invalid range")

// MaxReportDays bounds report windows.
const MaxReportDays = 366

// Normalize fills defaults (last 7 days, 50 rows) and validates the window.
func (r AnalyticsRange) Normalize(now time.Time) (AnalyticsRange, error) {
	r.Market = strings.ToLower(strings.TrimSpace(r.Market))
	if r.Market == "" {
		r.Market = "us"
	}
	today := now.UTC().Truncate(24 * time.Hour)
	if r.To.IsZero() {
		r.To = today
	}
	if r.From.IsZero() {
		r.From = r.To.AddDate(0, 0, -6)
	}
	if r.To.Before(r.From) {
		return r, errors.Join(ErrInvalidRange, errors.New("from is after to"))
	}
	if r.To.Sub(r.From) > MaxReportDays*24*time.Hour {
		return r, errors.Join(ErrInvalidRange, errors.New("at most 366 days"))
	}
	if r.Limit <= 0 {
		r.Limit = 50
	}
	if r.Limit > 500 {
		r.Limit = 500
	}
	return r, nil
}

// QueryStat is one row of the top / zero-result query reports.
type QueryStat struct {
	Query       string  `json:"query"`
	Searches    int64   `json:"searches"`
	ZeroResults int64   `json:"zeroResults"`
	Clicks      int64   `json:"clicks"`
	CTR         float64 `json:"ctr"`
}

// DailyCTR is one day of the click-through report.
type DailyCTR struct {
	Day            string  `json:"day"`
	Searches       int64   `json:"searches"`
	Clicks         int64   `json:"clicks"`
	ZeroResults    int64   `json:"zeroResults"`
	CTR            float64 `json:"ctr"`
	ZeroResultRate float64 `json:"zeroResultRate"`
}

// Ratio returns n/d rounded to four decimals (0 when d is 0).
func Ratio(n, d int64) float64 {
	if d <= 0 {
		return 0
	}
	return float64(int64(float64(n)/float64(d)*10000+0.5)) / 10000
}

// QueryReport is a ranked list of queries for a window.
type QueryReport struct {
	Market string      `json:"market"`
	From   string      `json:"from"`
	To     string      `json:"to"`
	Items  []QueryStat `json:"items"`
}

// CTRReport is the click-through rate by day for a window.
type CTRReport struct {
	Market string     `json:"market"`
	From   string     `json:"from"`
	To     string     `json:"to"`
	Days   []DailyCTR `json:"days"`
	Total  DailyCTR   `json:"total"`
}
