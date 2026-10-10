package elastic

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/elastic/go-elasticsearch/v9"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/port"
)

// AnalyticsIndex is the per-market index of daily query aggregates.
func AnalyticsIndex(market string) string { return "search-analytics-" + strings.ToLower(market) }

// analyticsMapping: one document per (day, normalised query) with counters, so storage grows with distinct queries
// per day rather than with traffic, and every report is a single aggregation.
const analyticsMapping = `{
  "settings": {"number_of_shards": 1, "number_of_replicas": 0},
  "mappings": {"dynamic": "strict", "properties": {
    "day":          {"type": "date", "format": "strict_date"},
    "query":        {"type": "keyword"},
    "searches":     {"type": "long"},
    "zero_results": {"type": "long"},
    "clicks":       {"type": "long"},
    "updated_at":   {"type": "date"}
  }}
}`

const incrementScript = `ctx._source.searches += params.s; ctx._source.zero_results += params.z;
ctx._source.clicks += params.c; ctx._source.updated_at = params.now;`

type analyticsImpl struct {
	admin *adminImpl
	now   func() time.Time
}

var _ port.AnalyticsStore = (*analyticsImpl)(nil)

// NewAnalyticsStore returns the Elasticsearch analytics store.
func NewAnalyticsStore(es *elasticsearch.Client) port.AnalyticsStore {
	return &analyticsImpl{admin: &adminImpl{es: es}, now: time.Now}
}

// EnsureAnalyticsIndex creates the market's analytics index when missing.
func EnsureAnalyticsIndex(ctx context.Context, es *elasticsearch.Client, market string) error {
	a := &adminImpl{es: es}
	name := AnalyticsIndex(market)
	status, _, err := a.call(ctx, http.MethodHead, "/"+name, nil)
	if err != nil {
		return err
	}
	if status == http.StatusOK {
		return nil
	}
	status, body, err := a.callRaw(ctx, http.MethodPut, "/"+name, []byte(analyticsMapping))
	if err != nil {
		return err
	}
	if status >= 300 && !strings.Contains(string(body), "resource_already_exists_exception") {
		return fmt.Errorf("create %s: status %d: %s", name, status, body)
	}
	return nil
}

func docID(day, query string) string {
	sum := sha256.Sum256([]byte(day + "\x1f" + query))
	return hex.EncodeToString(sum[:16])
}

func (s *analyticsImpl) Add(ctx context.Context, deltas []domain.AnalyticsDelta) error {
	if len(deltas) == 0 {
		return nil
	}
	now := s.now().UTC().Format(time.RFC3339)
	var buf strings.Builder
	enc := json.NewEncoder(&buf)
	for _, d := range deltas {
		meta := object{"update": object{"_index": AnalyticsIndex(d.Market), "_id": docID(d.Day, d.Query),
			"retry_on_conflict": 5}}
		body := object{
			"script": object{"source": incrementScript, "lang": "painless",
				"params": object{"s": d.Searches, "z": d.ZeroResults, "c": d.Clicks, "now": now}},
			"upsert": object{"day": d.Day, "query": d.Query, "searches": d.Searches, "zero_results": d.ZeroResults,
				"clicks": d.Clicks, "updated_at": now},
		}
		if err := enc.Encode(meta); err != nil {
			return fmt.Errorf("encode bulk: %w", err)
		}
		if err := enc.Encode(body); err != nil {
			return fmt.Errorf("encode bulk: %w", err)
		}
	}
	status, resp, err := s.admin.callRaw(ctx, http.MethodPost, "/_bulk", []byte(buf.String()))
	if err != nil {
		return err
	}
	if status >= 300 {
		return fmt.Errorf("analytics bulk: status %d: %s", status, resp)
	}
	var out struct {
		Errors bool `json:"errors"`
		Items  []map[string]struct {
			Status int `json:"status"`
			Error  any `json:"error"`
		} `json:"items"`
	}
	if err := json.Unmarshal(resp, &out); err != nil {
		return fmt.Errorf("decode bulk: %w", err)
	}
	if out.Errors {
		for _, item := range out.Items {
			for _, r := range item {
				if r.Error != nil {
					return fmt.Errorf("analytics bulk item: status %d: %v", r.Status, r.Error)
				}
			}
		}
	}
	return nil
}

func rangeFilter(r domain.AnalyticsRange) object {
	return object{"range": object{"day": object{"gte": domain.Day(r.From), "lte": domain.Day(r.To)}}}
}

func sums() object {
	return object{
		"searches":     object{"sum": object{"field": "searches"}},
		"zero_results": object{"sum": object{"field": "zero_results"}},
		"clicks":       object{"sum": object{"field": "clicks"}},
	}
}

type sumAgg struct {
	Value float64 `json:"value"`
}

type statBucket struct {
	Key         any    `json:"key"`
	KeyAsString string `json:"key_as_string"`
	Searches    sumAgg `json:"searches"`
	ZeroResults sumAgg `json:"zero_results"`
	Clicks      sumAgg `json:"clicks"`
}

func (s *analyticsImpl) queries(ctx context.Context, r domain.AnalyticsRange, filters []any, orderBy string) ([]domain.QueryStat, error) {
	body := object{
		"size":  0,
		"query": object{"bool": object{"filter": append([]any{rangeFilter(r)}, filters...)}},
		"aggs": object{"queries": object{
			"terms": object{"field": "query", "size": r.Limit, "shard_size": r.Limit * 4,
				"order": []any{object{orderBy: "desc"}, object{"_key": "asc"}}},
			"aggs": sums(),
		}},
	}
	var out struct {
		Aggregations struct {
			Queries struct {
				Buckets []statBucket `json:"buckets"`
			} `json:"queries"`
		} `json:"aggregations"`
	}
	if err := s.search(ctx, r.Market, body, &out); err != nil {
		return nil, err
	}
	items := make([]domain.QueryStat, 0, len(out.Aggregations.Queries.Buckets))
	for _, b := range out.Aggregations.Queries.Buckets {
		items = append(items, domain.QueryStat{Query: fmt.Sprint(b.Key), Searches: int64(b.Searches.Value),
			ZeroResults: int64(b.ZeroResults.Value), Clicks: int64(b.Clicks.Value)})
	}
	return items, nil
}

func (s *analyticsImpl) TopQueries(ctx context.Context, r domain.AnalyticsRange) ([]domain.QueryStat, error) {
	return s.queries(ctx, r, nil, "searches")
}

func (s *analyticsImpl) ZeroResultQueries(ctx context.Context, r domain.AnalyticsRange) ([]domain.QueryStat, error) {
	return s.queries(ctx, r, []any{object{"range": object{"zero_results": object{"gt": 0}}}}, "zero_results")
}

func (s *analyticsImpl) DailyCTR(ctx context.Context, r domain.AnalyticsRange) ([]domain.DailyCTR, error) {
	body := object{
		"size":  0,
		"query": object{"bool": object{"filter": []any{rangeFilter(r)}}},
		"aggs": object{"days": object{
			"date_histogram": object{"field": "day", "calendar_interval": "1d", "format": "yyyy-MM-dd",
				"min_doc_count": 0, "extended_bounds": object{"min": domain.Day(r.From), "max": domain.Day(r.To)}},
			"aggs": sums(),
		}},
	}
	var out struct {
		Aggregations struct {
			Days struct {
				Buckets []statBucket `json:"buckets"`
			} `json:"days"`
		} `json:"aggregations"`
	}
	if err := s.search(ctx, r.Market, body, &out); err != nil {
		return nil, err
	}
	days := make([]domain.DailyCTR, 0, len(out.Aggregations.Days.Buckets))
	for _, b := range out.Aggregations.Days.Buckets {
		days = append(days, domain.DailyCTR{Day: b.KeyAsString, Searches: int64(b.Searches.Value),
			Clicks: int64(b.Clicks.Value), ZeroResults: int64(b.ZeroResults.Value)})
	}
	return days, nil
}

func (s *analyticsImpl) Purge(ctx context.Context, market string, before time.Time) error {
	body := object{"query": object{"range": object{"day": object{"lt": domain.Day(before)}}}}
	status, resp, err := s.admin.call(ctx, http.MethodPost,
		"/"+AnalyticsIndex(market)+"/_delete_by_query?conflicts=proceed&ignore_unavailable=true", body)
	if err != nil {
		return err
	}
	if status >= 300 {
		return fmt.Errorf("purge %s: status %d: %s", AnalyticsIndex(market), status, resp)
	}
	return nil
}

func (s *analyticsImpl) search(ctx context.Context, market string, body object, out any) error {
	status, resp, err := s.admin.call(ctx, http.MethodPost,
		"/"+AnalyticsIndex(market)+"/_search?ignore_unavailable=true", body)
	if err != nil {
		return err
	}
	if status >= 300 {
		return fmt.Errorf("analytics search: status %d: %s", status, resp)
	}
	if err := json.Unmarshal(resp, out); err != nil {
		return fmt.Errorf("decode analytics: %w", err)
	}
	return nil
}
