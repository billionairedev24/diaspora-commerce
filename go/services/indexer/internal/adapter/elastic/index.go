// Package elastic implements port.ProductIndex with version-guarded Painless scripts.
package elastic

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"time"

	"github.com/elastic/go-elasticsearch/v9"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

// recompute sums per-variant×node quantities for variants the product still has. A variant with a buy box entry
// (key "<atin>|BUYBOX", from offers.buybox.changed) counts that entry only: it is the quantity the PDP sells.
const recompute = `
long total = 0;
if (ctx._source.variant_qty != null && ctx._source.variant_atins != null) {
  Set boxed = new HashSet();
  for (def e : ctx._source.variant_qty.entrySet()) {
    String[] k = e.getKey().splitOnToken('|');
    if (k.length > 1 && k[1] == 'BUYBOX') { boxed.add(k[0]); }
  }
  for (def e : ctx._source.variant_qty.entrySet()) {
    String[] k = e.getKey().splitOnToken('|');
    String sku = k[0];
    boolean box = k.length > 1 && k[1] == 'BUYBOX';
    if (!ctx._source.variant_atins.contains(sku) || (boxed.contains(sku) && !box)) { continue; }
    total += Long.parseLong(e.getValue().toString());
  }
}
ctx._source.available_qty = total;
ctx._source.in_stock = total > 0;
ctx._source.updated_at = params.now;`

const upsertScript = `
if (ctx._source.version != null && ((Number) ctx._source.version).longValue() > params.version) { ctx.op = 'noop'; return; }
for (def e : params.doc.entrySet()) {
  if (params.preserve.contains(e.getKey()) && ctx._source[e.getKey()] != null) { continue; }
  ctx._source[e.getKey()] = e.getValue();
}
ctx._source.version = params.version;` + recompute

const availabilityScript = `
if (ctx._source.variant_qty == null) { ctx._source.variant_qty = new HashMap(); }
if (ctx._source.variant_qty_version == null) { ctx._source.variant_qty_version = new HashMap(); }
def cur = ctx._source.variant_qty_version[params.key];
if (cur != null && Long.parseLong(cur.toString()) > params.version) { ctx.op = 'noop'; return; }
ctx._source.variant_qty[params.key] = params.qty;
ctx._source.variant_qty_version[params.key] = params.version;` + recompute

type indexImpl struct {
	es      *elasticsearch.Client
	refresh bool
}

var _ port.ProductIndex = (*indexImpl)(nil)

// NewIndex returns a ProductIndex. refresh=true makes writes visible immediately (tests and local only).
func NewIndex(es *elasticsearch.Client, refresh bool) port.ProductIndex {
	return &indexImpl{es: es, refresh: refresh}
}

// managed fields are owned by the index (availability) and never overwritten by product upserts.
var managed = []string{"available_qty", "in_stock", "variant_qty", "variant_qty_version", "version", "updated_at"}

const renameScript = `ctx._source.seller_name = params.name; ctx._source.updated_at = params.now;`

func (i *indexImpl) UpsertProduct(ctx context.Context, market string, doc searchindex.ProductDocument, version int64,
	opts port.UpsertOptions,
) error {
	raw, err := json.Marshal(doc)
	if err != nil {
		return fmt.Errorf("encode doc: %w", err)
	}
	fields := map[string]any{}
	if err := json.Unmarshal(raw, &fields); err != nil {
		return fmt.Errorf("decode doc: %w", err)
	}
	for _, k := range managed {
		delete(fields, k)
	}
	preserve := []string{}
	if opts.PreserveSellerName {
		preserve = append(preserve, "seller_name")
	}
	body := map[string]any{
		"scripted_upsert": true,
		"upsert":          map[string]any{},
		"script": map[string]any{
			"lang":   "painless",
			"source": upsertScript,
			"params": map[string]any{
				"doc": fields, "version": version, "preserve": preserve,
				"now": time.Now().UTC().Format(time.RFC3339),
			},
		},
	}
	buf, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("encode update: %w", err)
	}
	res, err := i.es.Update(searchindex.Alias(market), doc.ATIN, bytes.NewReader(buf),
		i.es.Update.WithContext(ctx), i.es.Update.WithRetryOnConflict(5), i.es.Update.WithRefresh(i.refreshParam()))
	if err != nil {
		return fmt.Errorf("update %s: %w", doc.ATIN, err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		return fmt.Errorf("update %s: %s: %s", doc.ATIN, res.Status(), msg)
	}
	return nil
}

func (i *indexImpl) ApplyAvailability(ctx context.Context, market, variantATIN, node string, qty int, version int64) (bool, error) {
	body := map[string]any{
		"query": map[string]any{"term": map[string]any{"variant_atins": variantATIN}},
		"script": map[string]any{
			"lang":   "painless",
			"source": availabilityScript,
			"params": map[string]any{
				"key": variantATIN + "|" + node, "qty": qty, "version": version,
				"now": time.Now().UTC().Format(time.RFC3339),
			},
		},
	}
	buf, err := json.Marshal(body)
	if err != nil {
		return false, fmt.Errorf("encode: %w", err)
	}
	res, err := i.es.UpdateByQuery([]string{searchindex.Alias(market)},
		i.es.UpdateByQuery.WithContext(ctx),
		i.es.UpdateByQuery.WithBody(bytes.NewReader(buf)),
		i.es.UpdateByQuery.WithRefresh(i.refresh),
		i.es.UpdateByQuery.WithConflicts("abort"))
	if err != nil {
		return false, fmt.Errorf("update_by_query: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		return false, fmt.Errorf("update_by_query: %s: %s", res.Status(), msg)
	}
	var out struct {
		Total            int `json:"total"`
		VersionConflicts int `json:"version_conflicts"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return false, fmt.Errorf("decode: %w", err)
	}
	if out.VersionConflicts > 0 {
		return true, fmt.Errorf("version conflict updating %s; will retry", variantATIN)
	}
	return out.Total > 0, nil
}

func (i *indexImpl) RenameSeller(ctx context.Context, market, sellerID, name string) (int, error) {
	body := map[string]any{
		"query": map[string]any{"bool": map[string]any{
			"filter":   []any{map[string]any{"term": map[string]any{"seller_id": sellerID}}},
			"must_not": []any{map[string]any{"term": map[string]any{"seller_name": name}}},
		}},
		"script": map[string]any{
			"lang":   "painless",
			"source": renameScript,
			"params": map[string]any{"name": name, "now": time.Now().UTC().Format(time.RFC3339)},
		},
	}
	buf, err := json.Marshal(body)
	if err != nil {
		return 0, fmt.Errorf("encode: %w", err)
	}
	res, err := i.es.UpdateByQuery([]string{searchindex.Alias(market)},
		i.es.UpdateByQuery.WithContext(ctx),
		i.es.UpdateByQuery.WithBody(bytes.NewReader(buf)),
		i.es.UpdateByQuery.WithRefresh(i.refresh),
		i.es.UpdateByQuery.WithConflicts("proceed"))
	if err != nil {
		return 0, fmt.Errorf("update_by_query: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		return 0, fmt.Errorf("update_by_query: %s: %s", res.Status(), msg)
	}
	var out struct {
		Updated          int `json:"updated"`
		VersionConflicts int `json:"version_conflicts"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return 0, fmt.Errorf("decode: %w", err)
	}
	if out.VersionConflicts > 0 {
		return out.Updated, fmt.Errorf("version conflict renaming seller %s; will retry", sellerID)
	}
	return out.Updated, nil
}

// MissingEmbeddings pages through LIVE documents without an embedding, by ATIN.
func (i *indexImpl) MissingEmbeddings(ctx context.Context, market, afterATIN string, limit int) ([]domain.EmbeddingCandidate, error) {
	body := map[string]any{
		"size": limit,
		"query": map[string]any{"bool": map[string]any{
			"filter":   []any{map[string]any{"term": map[string]any{"status": searchindex.StatusLive}}},
			"must_not": []any{map[string]any{"exists": map[string]any{"field": "embedding"}}},
		}},
		"sort":    []any{map[string]any{"atin": "asc"}},
		"_source": []string{"atin", "title", "brand", "category_path", "version"},
	}
	if afterATIN != "" {
		body["search_after"] = []any{afterATIN}
	}
	buf, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("encode: %w", err)
	}
	res, err := i.es.Search(i.es.Search.WithContext(ctx), i.es.Search.WithIndex(searchindex.Alias(market)),
		i.es.Search.WithBody(bytes.NewReader(buf)))
	if err != nil {
		return nil, fmt.Errorf("search: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		return nil, fmt.Errorf("search: %s: %s", res.Status(), msg)
	}
	var out struct {
		Hits struct {
			Hits []struct {
				Source struct {
					ATIN         string `json:"atin"`
					Title        string `json:"title"`
					Brand        string `json:"brand"`
					CategoryPath string `json:"category_path"`
					Version      int64  `json:"version"`
				} `json:"_source"`
			} `json:"hits"`
		} `json:"hits"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("decode: %w", err)
	}
	cands := make([]domain.EmbeddingCandidate, 0, len(out.Hits.Hits))
	for _, h := range out.Hits.Hits {
		s := h.Source
		cands = append(cands, domain.EmbeddingCandidate{ATIN: s.ATIN, Title: s.Title, Brand: s.Brand,
			CategoryPath: s.CategoryPath, Version: s.Version})
	}
	return cands, nil
}

const setEmbeddingScript = `
if (ctx._source.version == null || ((Number) ctx._source.version).longValue() != params.version) { ctx.op = 'noop'; return; }
ctx._source.embedding = params.vec;`

// SetEmbedding stores a backfilled embedding unless the product changed since it was read.
func (i *indexImpl) SetEmbedding(ctx context.Context, market, atin string, vec []float32, version int64) (bool, error) {
	buf, err := json.Marshal(map[string]any{"script": map[string]any{
		"lang": "painless", "source": setEmbeddingScript, "params": map[string]any{"vec": vec, "version": version},
	}})
	if err != nil {
		return false, fmt.Errorf("encode: %w", err)
	}
	res, err := i.es.Update(searchindex.Alias(market), atin, bytes.NewReader(buf), i.es.Update.WithContext(ctx),
		i.es.Update.WithRetryOnConflict(3), i.es.Update.WithRefresh(i.refreshParam()))
	if err != nil {
		return false, fmt.Errorf("update %s: %w", atin, err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		msg, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
		return false, fmt.Errorf("update %s: %s: %s", atin, res.Status(), msg)
	}
	var out struct {
		Result string `json:"result"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return false, fmt.Errorf("decode: %w", err)
	}
	return out.Result == "updated", nil
}

func (i *indexImpl) Ping(ctx context.Context) error {
	res, err := i.es.Ping(i.es.Ping.WithContext(ctx))
	if err != nil {
		return fmt.Errorf("ping: %w", err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.IsError() {
		return fmt.Errorf("ping: %s", res.Status())
	}
	return nil
}

func (i *indexImpl) refreshParam() string {
	if i.refresh {
		return "true"
	}
	return "false"
}
