package upstreams

import (
	"context"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- search

type searchImpl struct{ c httpclient.Client }

var _ port.SearchClient = (*searchImpl)(nil)

// NewSearch returns the search-service client (GET /v1/search, /v1/suggest).
func NewSearch(c httpclient.Client) port.SearchClient { return &searchImpl{c: c} }

func (s *searchImpl) Search(ctx context.Context, p port.SearchParams) (port.SearchResult, error) {
	q := url.Values{}
	set := func(k, v string) {
		if v != "" {
			q.Set(k, v)
		}
	}
	set("q", p.Q)
	set("market", p.Market)
	set("department", p.Department)
	set("community", p.Community)
	set("fulfilment", p.Fulfilment)
	set("sellerKind", p.SellerKind)
	set("sort", p.Sort)
	if p.MinRating != nil {
		q.Set("minRating", strconv.FormatFloat(*p.MinRating, 'f', -1, 64))
	}
	if p.MinPrice > 0 {
		q.Set("minPrice", strconv.FormatInt(p.MinPrice, 10))
	}
	if p.MaxPrice > 0 {
		q.Set("maxPrice", strconv.FormatInt(p.MaxPrice, 10))
	}
	if p.MaxDays > 0 {
		q.Set("maxDays", strconv.Itoa(p.MaxDays))
	}
	if p.InStock {
		q.Set("inStock", "true")
	}
	q.Set("page", strconv.Itoa(p.Page))
	if p.Size > 0 {
		q.Set("size", strconv.Itoa(p.Size))
	}
	if p.Ads {
		q.Set("ads", "true")
	}
	if p.NoTrack {
		q.Set("track", "false")
	}
	var out port.SearchResult
	_, err := call(ctx, s.c, httpclient.Request{Path: "/v1/search", Query: q}, &out)
	return out, err
}

func (s *searchImpl) Suggest(ctx context.Context, q, market string) (port.SuggestResult, error) {
	v := url.Values{"q": {q}}
	if market != "" {
		v.Set("market", market)
	}
	var out port.SuggestResult
	_, err := call(ctx, s.c, httpclient.Request{Path: "/v1/suggest", Query: v}, &out)
	return out, err
}

// ---------------------------------------------------------------- catalog

type catalogImpl struct {
	c       httpclient.Client
	cache   port.Cache
	ceiling time.Duration
	refTTL  time.Duration
}

var _ port.CatalogClient = (*catalogImpl)(nil)

// NewCatalog returns the catalog public-read client. Products and variants are cached for the upstream's max-age
// (capped by ceiling); the category tree for refTTL. Only public, non-personal reads are cached.
func NewCatalog(c httpclient.Client, cache port.Cache, ceiling, refTTL time.Duration) port.CatalogClient {
	return &catalogImpl{c: c, cache: cache, ceiling: ceiling, refTTL: refTTL}
}

func (k *catalogImpl) ttl(h http.Header) time.Duration { return ttlFor(h, k.ceiling, k.ceiling) }

func (k *catalogImpl) Product(ctx context.Context, atin string) (port.CatalogProduct, error) {
	atin = strings.ToUpper(atin)
	return cached(k.cache, "catalog:product:"+atin, func() (port.CatalogProduct, http.Header, error) {
		var p port.CatalogProduct
		res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/public/products/" + url.PathEscape(atin)}, &p)
		if err != nil {
			return p, nil, err
		}
		return p, res.Header, nil
	}, k.ttl)
}

func (k *catalogImpl) Variant(ctx context.Context, atin string) (port.CatalogVariantView, error) {
	atin = strings.ToUpper(atin)
	return cached(k.cache, "catalog:variant:"+atin, func() (port.CatalogVariantView, http.Header, error) {
		var v port.CatalogVariantView
		res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/public/variants/" + url.PathEscape(atin)}, &v)
		if err != nil {
			return v, nil, err
		}
		return v, res.Header, nil
	}, k.ttl)
}

func (k *catalogImpl) Categories(ctx context.Context) ([]port.Category, error) {
	return cached(k.cache, "catalog:categories", func() ([]port.Category, http.Header, error) {
		var out []port.Category
		res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/categories"}, &out)
		if err != nil {
			return nil, nil, err
		}
		return out, res.Header, nil
	}, func(http.Header) time.Duration { return k.refTTL })
}

// ---------------------------------------------------------------- inventory

type inventoryImpl struct{ c httpclient.Client }

var _ port.InventoryClient = (*inventoryImpl)(nil)

// NewInventory returns the inventory availability client (GET /v1/availability?skus=, ≤100 per call).
func NewInventory(c httpclient.Client) port.InventoryClient { return &inventoryImpl{c: c} }

func (i *inventoryImpl) Available(ctx context.Context, skus []string) (map[string]int, error) {
	out := make(map[string]int, len(skus))
	for start := 0; start < len(skus); start += 100 {
		end := min(start+100, len(skus))
		var rows []struct {
			SKU       string `json:"sku"`
			Available int    `json:"available"`
		}
		q := url.Values{"skus": {strings.Join(skus[start:end], ",")}}
		if _, err := call(ctx, i.c, httpclient.Request{Path: "/v1/availability", Query: q}, &rows); err != nil {
			return nil, err
		}
		for _, r := range rows {
			out[r.SKU] = r.Available
		}
	}
	return out, nil
}
