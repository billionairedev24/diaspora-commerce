package meilisearch

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/meili"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// AnalyticsIndex is the per-market index of daily query aggregates (one document per day and normalised query).
func AnalyticsIndex(market string) string { return "search-analytics-" + strings.ToLower(market) }

// Meilisearch has no atomic increment or aggregation. Add merges a batch into the stored counters (read, add, write
// back: the recorder flushes one batch at a time from each replica, so two replicas flushing the same query in the same
// instant can lose a count) and the reports aggregate the days in range in memory (a market has one document per
// distinct query per day, bounded by the retention window).
type analyticsImpl struct {
	c   *meili.Client
	now func() time.Time
}

var _ port.AnalyticsStore = (*analyticsImpl)(nil)

// NewAnalyticsStore returns the Meilisearch analytics store.
func NewAnalyticsStore(c *meili.Client) port.AnalyticsStore {
	return &analyticsImpl{c: c, now: time.Now}
}

// EnsureAnalyticsIndex creates the market's analytics index when missing.
func EnsureAnalyticsIndex(ctx context.Context, c *meili.Client, market string) error {
	uid := AnalyticsIndex(market)
	err := c.Do(ctx, http.MethodGet, "/indexes/"+url.PathEscape(uid), nil, nil)
	if err == nil {
		return nil
	}
	if !meili.IsNotFound(err) {
		return err
	}
	if err := c.Task(ctx, http.MethodPost, "/indexes", object{"uid": uid, "primaryKey": "id"}); err != nil && !meili.IsCode(err, "index_already_exists") {
		return fmt.Errorf("create %s: %w", uid, err)
	}
	settings := object{"filterableAttributes": []string{"id", "day_ts", "zero_results"}, "searchableAttributes": []string{"query"},
		"displayedAttributes": []string{"*"}}
	if err := c.Task(ctx, http.MethodPatch, "/indexes/"+url.PathEscape(uid)+"/settings", settings); err != nil {
		return fmt.Errorf("settings %s: %w", uid, err)
	}
	return nil
}

func docID(day, query string) string {
	sum := sha256.Sum256([]byte(day + "\x1f" + query))
	return hex.EncodeToString(sum[:16])
}

func dayNumber(day string) int64 {
	t, err := time.Parse(time.DateOnly, day)
	if err != nil {
		return 0
	}
	return t.Unix() / 86400
}

func (s *analyticsImpl) Add(ctx context.Context, deltas []domain.AnalyticsDelta) error {
	byMarket := map[string][]domain.AnalyticsDelta{}
	for _, d := range deltas {
		byMarket[d.Market] = append(byMarket[d.Market], d)
	}
	for market, ds := range byMarket {
		if err := s.addMarket(ctx, market, ds); err != nil {
			return err
		}
	}
	return nil
}

func (s *analyticsImpl) addMarket(ctx context.Context, market string, deltas []domain.AnalyticsDelta) error {
	uid := AnalyticsIndex(market)
	merged := map[string]map[string]any{}
	ids := []string{}
	for _, d := range deltas {
		id := docID(d.Day, d.Query)
		doc, ok := merged[id]
		if !ok {
			doc = object{"id": id, "day": d.Day, "day_ts": dayNumber(d.Day), "query": d.Query, "searches": int64(0), "zero_results": int64(0), "clicks": int64(0)}
			merged[id] = doc
			ids = append(ids, id)
		}
		doc["searches"] = doc["searches"].(int64) + d.Searches
		doc["zero_results"] = doc["zero_results"].(int64) + d.ZeroResults
		doc["clicks"] = doc["clicks"].(int64) + d.Clicks
	}
	quoted := make([]string, len(ids))
	for i, id := range ids {
		quoted[i] = meili.Quote(id)
	}
	existing, err := s.c.Fetch(ctx, uid, meili.FetchRequest{Limit: len(ids), Filter: "id IN [" + strings.Join(quoted, ", ") + "]",
		Fields: []string{"id", "searches", "zero_results", "clicks"}})
	if err != nil {
		return fmt.Errorf("analytics read: %w", err)
	}
	for _, e := range existing {
		doc := merged[searchindex.MeiliStr(e["id"])]
		if doc == nil {
			continue
		}
		doc["searches"] = doc["searches"].(int64) + int64(searchindex.MeiliNum(e["searches"]))
		doc["zero_results"] = doc["zero_results"].(int64) + int64(searchindex.MeiliNum(e["zero_results"]))
		doc["clicks"] = doc["clicks"].(int64) + int64(searchindex.MeiliNum(e["clicks"]))
	}
	now := s.now().UTC().Format(time.RFC3339)
	docs := make([]map[string]any, 0, len(ids))
	for _, id := range ids {
		merged[id]["updated_at"] = now
		docs = append(docs, merged[id])
	}
	if err := s.c.Put(ctx, uid, docs); err != nil {
		return fmt.Errorf("analytics write: %w", err)
	}
	return nil
}

// days returns every aggregate of the window, paged.
func (s *analyticsImpl) days(ctx context.Context, r domain.AnalyticsRange, extra string) ([]map[string]any, error) {
	filter := "day_ts >= " + strconv.FormatInt(dayNumber(domain.Day(r.From)), 10) + " AND day_ts <= " + strconv.FormatInt(dayNumber(domain.Day(r.To)), 10)
	if extra != "" {
		filter += " AND " + extra
	}
	const page = 1000
	var all []map[string]any
	for offset := 0; ; offset += page {
		got, err := s.c.Fetch(ctx, AnalyticsIndex(r.Market), meili.FetchRequest{Offset: offset, Limit: page, Filter: filter})
		if err != nil {
			if meili.IsNotFound(err) {
				return all, nil
			}
			return nil, fmt.Errorf("analytics read: %w", err)
		}
		all = append(all, got...)
		if len(got) < page {
			return all, nil
		}
	}
}

func (s *analyticsImpl) queries(ctx context.Context, r domain.AnalyticsRange, extra string, by func(domain.QueryStat) int64) ([]domain.QueryStat, error) {
	docs, err := s.days(ctx, r, extra)
	if err != nil {
		return nil, err
	}
	byQuery := map[string]*domain.QueryStat{}
	for _, d := range docs {
		q := searchindex.MeiliStr(d["query"])
		st := byQuery[q]
		if st == nil {
			st = &domain.QueryStat{Query: q}
			byQuery[q] = st
		}
		st.Searches += int64(searchindex.MeiliNum(d["searches"]))
		st.ZeroResults += int64(searchindex.MeiliNum(d["zero_results"]))
		st.Clicks += int64(searchindex.MeiliNum(d["clicks"]))
	}
	out := make([]domain.QueryStat, 0, len(byQuery))
	for _, st := range byQuery {
		out = append(out, *st)
	}
	slices.SortFunc(out, func(a, b domain.QueryStat) int {
		if x, y := by(a), by(b); x != y {
			return int(y - x)
		}
		return strings.Compare(a.Query, b.Query)
	})
	if r.Limit > 0 && len(out) > r.Limit {
		out = out[:r.Limit]
	}
	return out, nil
}

func (s *analyticsImpl) TopQueries(ctx context.Context, r domain.AnalyticsRange) ([]domain.QueryStat, error) {
	return s.queries(ctx, r, "", func(q domain.QueryStat) int64 { return q.Searches })
}

func (s *analyticsImpl) ZeroResultQueries(ctx context.Context, r domain.AnalyticsRange) ([]domain.QueryStat, error) {
	return s.queries(ctx, r, "zero_results > 0", func(q domain.QueryStat) int64 { return q.ZeroResults })
}

func (s *analyticsImpl) DailyCTR(ctx context.Context, r domain.AnalyticsRange) ([]domain.DailyCTR, error) {
	docs, err := s.days(ctx, r, "")
	if err != nil {
		return nil, err
	}
	byDay := map[string]*domain.DailyCTR{}
	for _, d := range docs {
		day := searchindex.MeiliStr(d["day"])
		row := byDay[day]
		if row == nil {
			row = &domain.DailyCTR{Day: day}
			byDay[day] = row
		}
		row.Searches += int64(searchindex.MeiliNum(d["searches"]))
		row.Clicks += int64(searchindex.MeiliNum(d["clicks"]))
		row.ZeroResults += int64(searchindex.MeiliNum(d["zero_results"]))
	}
	// Every day of the window appears, empty days as zeros (the Elasticsearch date histogram's extended bounds).
	var out []domain.DailyCTR
	for t := r.From.UTC().Truncate(24 * time.Hour); !t.After(r.To.UTC()); t = t.Add(24 * time.Hour) {
		day := domain.Day(t)
		if row := byDay[day]; row != nil {
			out = append(out, *row)
		} else {
			out = append(out, domain.DailyCTR{Day: day})
		}
	}
	return out, nil
}

func (s *analyticsImpl) Purge(ctx context.Context, market string, before time.Time) error {
	filter := "day_ts < " + strconv.FormatInt(dayNumber(domain.Day(before)), 10)
	err := s.c.Task(ctx, http.MethodPost, "/indexes/"+url.PathEscape(AnalyticsIndex(market))+"/documents/delete", object{"filter": filter})
	if err != nil && !meili.IsNotFound(err) {
		return fmt.Errorf("purge %s: %w", AnalyticsIndex(market), err)
	}
	return nil
}
