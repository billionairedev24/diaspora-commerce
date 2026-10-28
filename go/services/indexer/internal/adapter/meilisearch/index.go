// Package meilisearch implements port.ProductIndex on Meilisearch. Meilisearch has no scripted or versioned updates, so
// each write reads the document, applies the same rules the Elasticsearch scripts do (version guard, availability
// totals, preserved fields) and writes the result back, waiting for the task so the next read sees it. Events for one
// product arrive in order on one partition, which is what makes the read-modify-write safe.
package meilisearch

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/meili"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/searchindex"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/indexer/internal/port"
)

type indexImpl struct {
	c   *meili.Client
	now func() time.Time
}

var _ port.ProductIndex = (*indexImpl)(nil)

// NewIndex returns a ProductIndex over a Meilisearch client.
func NewIndex(c *meili.Client) port.ProductIndex { return &indexImpl{c: c, now: time.Now} }

// managed fields are owned by the index (availability) and never overwritten by product upserts.
var managed = []string{"available_qty", "in_stock", "variant_qty", "variant_qty_version", "version", "updated_at"}

func (i *indexImpl) uid(market string) string { return searchindex.MeiliIndexUID(market) }

func (i *indexImpl) stamp(doc map[string]any) {
	doc["updated_at"] = i.now().UTC().Format(time.RFC3339)
}

func (i *indexImpl) UpsertProduct(ctx context.Context, market string, doc searchindex.ProductDocument, version int64,
	opts port.UpsertOptions,
) error {
	uid := i.uid(market)
	existing, err := i.c.Get(ctx, uid, doc.ATIN)
	if err != nil && !meili.IsNotFound(err) {
		return fmt.Errorf("read %s: %w", doc.ATIN, err)
	}
	if existing != nil && int64(searchindex.MeiliNum(existing["version"])) > version {
		return nil // a newer version is already indexed
	}
	next, err := searchindex.MeiliDocument(doc)
	if err != nil {
		return err
	}
	for _, k := range managed {
		delete(next, k)
	}
	if len(doc.Embedding) == 0 {
		delete(next, "has_embedding")
		if existing != nil && existing["has_embedding"] == true {
			delete(next, "_vectors") // an upsert without a vector keeps the one already stored
		}
	}
	if opts.PreserveSellerName && existing != nil && searchindex.MeiliStr(existing["seller_name"]) != "" {
		delete(next, "seller_name")
	}
	merged := map[string]any{}
	for k, v := range existing {
		merged[k] = v
	}
	for k, v := range next {
		merged[k] = v
	}
	merged["version"] = version
	recompute(merged)
	i.stamp(merged)
	searchindex.MeiliDerive(merged)
	// Only send what changed meaning: the merged document (Meilisearch merges it over the stored one).
	if err := i.c.Put(ctx, uid, []map[string]any{merged}); err != nil {
		return fmt.Errorf("upsert %s: %w", doc.ATIN, err)
	}
	return nil
}

// recompute sums per-variant×node quantities for variants the product still has. A variant with a buy box entry
// (key "<atin>|BUYBOX", from offers.buybox.changed) counts that entry only: it is the quantity the PDP sells.
func recompute(doc map[string]any) {
	qty, _ := doc["variant_qty"].(map[string]any)
	atins := stringsOf(doc["variant_atins"])
	have := map[string]bool{}
	for _, a := range atins {
		have[a] = true
	}
	var total int64
	if qty != nil && len(atins) > 0 {
		boxed := map[string]bool{}
		for k := range qty {
			if sku, node, ok := strings.Cut(k, "|"); ok && node == "BUYBOX" {
				boxed[sku] = true
			}
		}
		for k, v := range qty {
			sku, node, _ := strings.Cut(k, "|")
			if !have[sku] || (boxed[sku] && node != "BUYBOX") {
				continue
			}
			total += int64(searchindex.MeiliNum(v))
		}
	}
	doc["available_qty"] = total
	doc["in_stock"] = total > 0
}

func stringsOf(v any) []string {
	raw, _ := v.([]any)
	out := make([]string, 0, len(raw))
	for _, x := range raw {
		if s, ok := x.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

func (i *indexImpl) ApplyAvailability(ctx context.Context, market, variantATIN, node string, qty int, version int64) (bool, error) {
	uid := i.uid(market)
	docs, err := i.fetchAll(ctx, uid, meili.FetchRequest{Filter: "variant_atins = " + meili.Quote(variantATIN),
		Fields: []string{"id", "variant_atins", "variant_qty", "variant_qty_version", "fulfilment", "rating", "review_count", "delivery_days"}})
	if err != nil {
		return false, fmt.Errorf("find %s: %w", variantATIN, err)
	}
	if len(docs) == 0 {
		return false, nil
	}
	key := variantATIN + "|" + node
	updates := make([]map[string]any, 0, len(docs))
	for _, d := range docs {
		quantities, _ := d["variant_qty"].(map[string]any)
		versions, _ := d["variant_qty_version"].(map[string]any)
		if quantities == nil {
			quantities = map[string]any{}
		}
		if versions == nil {
			versions = map[string]any{}
		}
		if cur, ok := versions[key]; ok && int64(searchindex.MeiliNum(cur)) > version {
			continue // a newer quantity is already applied
		}
		quantities[key], versions[key] = qty, version
		d["variant_qty"], d["variant_qty_version"] = quantities, versions
		recompute(d)
		i.stamp(d)
		searchindex.MeiliDerive(d)
		updates = append(updates, map[string]any{"id": d["id"], "variant_qty": quantities, "variant_qty_version": versions,
			"available_qty": d["available_qty"], "in_stock": d["in_stock"], "in_stock_rank": d["in_stock_rank"],
			"rank_boost": d["rank_boost"], "updated_at": d["updated_at"], "updated_ts": d["updated_ts"]})
	}
	if err := i.c.Put(ctx, uid, updates); err != nil {
		return true, fmt.Errorf("availability %s: %w", variantATIN, err)
	}
	return true, nil
}

func (i *indexImpl) RenameSeller(ctx context.Context, market, sellerID, name string) (int, error) {
	uid := i.uid(market)
	docs, err := i.fetchAll(ctx, uid, meili.FetchRequest{Filter: "seller_id = " + meili.Quote(sellerID) + " AND seller_name != " + meili.Quote(name),
		Fields: []string{"id"}})
	if err != nil {
		return 0, fmt.Errorf("find seller %s: %w", sellerID, err)
	}
	updates := make([]map[string]any, 0, len(docs))
	now := i.now().UTC()
	for _, d := range docs {
		updates = append(updates, map[string]any{"id": d["id"], "seller_name": name, "updated_at": now.Format(time.RFC3339), "updated_ts": now.Unix()})
	}
	if err := i.c.Put(ctx, uid, updates); err != nil {
		return 0, fmt.Errorf("rename seller %s: %w", sellerID, err)
	}
	return len(updates), nil
}

// MissingEmbeddings pages through LIVE documents without an embedding, by ATIN.
func (i *indexImpl) MissingEmbeddings(ctx context.Context, market, afterATIN string, limit int) ([]domain.EmbeddingCandidate, error) {
	filter := "status = " + meili.Quote(searchindex.StatusLive) + " AND has_embedding = false"
	// Meilisearch filters cannot compare strings, so "after this ATIN" is applied here: results come sorted by ATIN, in
	// pages, and everything up to the cursor is skipped. (Documents that got their vector leave the result themselves.)
	const page = 200
	var docs []map[string]any
	for offset := 0; len(docs) < limit; offset += page {
		var res struct {
			Hits []map[string]any `json:"hits"`
		}
		err := i.c.Do(ctx, http.MethodPost, "/indexes/"+url.PathEscape(i.uid(market))+"/search", map[string]any{"q": "", "filter": filter,
			"sort": []string{"atin:asc"}, "offset": offset, "limit": page,
			"attributesToRetrieve": []string{"atin", "title", "brand", "category_path", "version"}}, &res)
		if err != nil {
			return nil, fmt.Errorf("missing embeddings: %w", err)
		}
		for _, h := range res.Hits {
			if searchindex.MeiliStr(h["atin"]) > afterATIN && len(docs) < limit {
				docs = append(docs, h)
			}
		}
		if len(res.Hits) < page {
			break
		}
	}
	out := make([]domain.EmbeddingCandidate, 0, len(docs))
	for _, d := range docs {
		out = append(out, domain.EmbeddingCandidate{ATIN: searchindex.MeiliStr(d["atin"]), Title: searchindex.MeiliStr(d["title"]),
			Brand: searchindex.MeiliStr(d["brand"]), CategoryPath: searchindex.MeiliStr(d["category_path"]),
			Version: int64(searchindex.MeiliNum(d["version"]))})
	}
	return out, nil
}

// SetEmbedding stores a backfilled embedding unless the product changed since it was read.
func (i *indexImpl) SetEmbedding(ctx context.Context, market, atin string, vec []float32, version int64) (bool, error) {
	uid := i.uid(market)
	cur, err := i.c.Get(ctx, uid, atin, "id", "version")
	if err != nil {
		return false, fmt.Errorf("read %s: %w", atin, err)
	}
	if cur == nil || int64(searchindex.MeiliNum(cur["version"])) != version {
		return false, nil
	}
	err = i.c.Put(ctx, uid, []map[string]any{{"id": atin, "has_embedding": true,
		"_vectors": map[string]any{searchindex.MeiliEmbedder: map[string]any{"embeddings": [][]float32{vec}, "regenerate": false}}}})
	if err != nil {
		return false, fmt.Errorf("set embedding %s: %w", atin, err)
	}
	return true, nil
}

func (i *indexImpl) Ping(ctx context.Context) error { return i.c.Health(ctx) }

// fetchAll pages through every document matching the request.
func (i *indexImpl) fetchAll(ctx context.Context, uid string, req meili.FetchRequest) ([]map[string]any, error) {
	const page = 500
	var all []map[string]any
	for offset := 0; ; offset += page {
		req.Offset, req.Limit = offset, page
		got, err := i.c.Fetch(ctx, uid, req)
		if err != nil {
			return nil, err
		}
		all = append(all, got...)
		if len(got) < page {
			return all, nil
		}
	}
}

// Admin creates the market's index.
type Admin interface {
	EnsureIndex(ctx context.Context, market string) error
}

type adminImpl struct{ c *meili.Client }

// NewIndexAdmin returns the index admin. The search service owns the synonyms and applies them to the index; the
// indexer only makes sure the index exists with the product settings.
func NewIndexAdmin(c *meili.Client) Admin { return &adminImpl{c: c} }

func (a *adminImpl) EnsureIndex(ctx context.Context, market string) error {
	return searchindex.MeiliEnsureIndex(ctx, a.c, market, nil)
}
