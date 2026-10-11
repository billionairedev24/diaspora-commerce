// Package promos reads what email advertorials promote from public, unauthenticated read APIs:
//
//   - search-service GET /v1/search?q=&inStock=true&track=false (related products, "Complete your pantry");
//   - delivery-runs GET /v1/public/preorders/open (an open pre-order in the customer's city);
//   - content GET /v1/placements/resolve?market=&slot=hero_carousel (house campaigns).
//
// Responses are cached briefly (pre-orders and campaigns per market for a minute, products per seed for five), so a
// burst of order emails does not fan out into the same lookups. Callers bound every call with their own deadline.
package promos

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// Config holds the services' base URLs (empty = that source is off).
type Config struct {
	SearchURL       string
	DeliveryRunsURL string
	ContentURL      string
	// CampaignSlot is the placement slot campaigns come from (default hero_carousel).
	CampaignSlot string
}

type cached[T any] struct {
	at  time.Time
	val T
}

type cache[T any] struct {
	mu  sync.Mutex
	ttl time.Duration
	m   map[string]cached[T]
}

func newCache[T any](ttl time.Duration) *cache[T] {
	return &cache[T]{ttl: ttl, m: map[string]cached[T]{}}
}

func (c *cache[T]) get(k string) (T, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[k]
	if !ok || time.Since(e.at) > c.ttl {
		var zero T
		return zero, false
	}
	return e.val, true
}

func (c *cache[T]) put(k string, v T) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.m) > 500 {
		clear(c.m)
	}
	c.m[k] = cached[T]{at: time.Now(), val: v}
}

func getJSON(ctx context.Context, client *http.Client, u string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return fmt.Errorf("request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	res, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("get %s: %w", u, err)
	}
	defer func() { _ = res.Body.Close() }()
	if res.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 4096))
		return fmt.Errorf("get %s: status %d", u, res.StatusCode)
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 2<<20)).Decode(out); err != nil {
		return fmt.Errorf("decode %s: %w", u, err)
	}
	return nil
}

// ---- products (search) ----

type productsImpl struct {
	base   string
	client *http.Client
	cache  *cache[[]domain.PromoProduct]
}

var _ port.ProductCatalog = (*productsImpl)(nil)

// NewProducts returns the ProductCatalog over search-service (nil when SearchURL is empty).
func NewProducts(cfg Config, client *http.Client) port.ProductCatalog {
	if cfg.SearchURL == "" {
		return nil
	}
	return &productsImpl{base: strings.TrimRight(cfg.SearchURL, "/"), client: client, cache: newCache[[]domain.PromoProduct](5 * time.Minute)}
}

// searchHit is the part of search's Hit the promotions use.
type searchHit struct {
	Atin       string `json:"atin"`
	Title      string `json:"title"`
	ImageURL   string `json:"imageUrl"`
	PriceMinor int64  `json:"priceMinor"`
	Currency   string `json:"currency"`
	InStock    bool   `json:"inStock"`
}

// SeedQuery is the search for a product's neighbours on the shelf: the last two words of its title before any comma
// ("Ofada Rice, Destoned" → "Ofada Rice", "AfroTransact Brand Aged Basmati Rice" → "Basmati Rice").
func SeedQuery(title string) string {
	title, _, _ = strings.Cut(title, ",")
	f := strings.Fields(title)
	if len(f) > 2 {
		f = f[len(f)-2:]
	}
	return strings.Join(f, " ")
}

func (p *productsImpl) search(ctx context.Context, q url.Values) ([]domain.PromoProduct, error) {
	var page struct {
		Hits []searchHit `json:"hits"`
	}
	if err := getJSON(ctx, p.client, p.base+"/v1/search?"+q.Encode(), &page); err != nil {
		return nil, err
	}
	out := []domain.PromoProduct{}
	for _, h := range page.Hits {
		if h.InStock && h.ImageURL != "" {
			out = append(out, domain.PromoProduct{Atin: h.Atin, Title: h.Title, ImageURL: h.ImageURL, PriceMinor: h.PriceMinor, Currency: h.Currency})
		}
	}
	return out, nil
}

func (p *productsImpl) Related(ctx context.Context, seed, market string, limit int) ([]domain.PromoProduct, error) {
	if market == "" {
		market = "us"
	}
	key := market + "|" + strings.ToLower(seed) + "|" + strconv.Itoa(limit)
	if v, ok := p.cache.get(key); ok {
		return v, nil
	}
	q := url.Values{"market": {market}, "inStock": {"true"}, "track": {"false"}, "size": {strconv.Itoa(limit)}}
	var out []domain.PromoProduct
	if sq := SeedQuery(seed); sq != "" {
		q.Set("q", sq)
		found, err := p.search(ctx, q)
		if err != nil {
			return nil, err
		}
		out = found
		q.Del("q")
	}
	if len(out) < 4 {
		// Too few neighbours: top up with the best-rated in-stock products.
		q.Set("sort", "rating")
		more, err := p.search(ctx, q)
		if err != nil && len(out) == 0 {
			return nil, err
		}
		out = append(out, more...)
	}
	p.cache.put(key, out)
	return out, nil
}

// ---- open pre-orders (delivery-runs) ----

type preordersImpl struct {
	base   string
	client *http.Client
	cache  *cache[[]domain.OpenPreorder]
}

var _ port.PreorderCatalog = (*preordersImpl)(nil)

// NewPreorders returns the PreorderCatalog over delivery-runs' public API (nil when DeliveryRunsURL is empty).
func NewPreorders(cfg Config, client *http.Client) port.PreorderCatalog {
	if cfg.DeliveryRunsURL == "" {
		return nil
	}
	return &preordersImpl{base: strings.TrimRight(cfg.DeliveryRunsURL, "/"), client: client, cache: newCache[[]domain.OpenPreorder](time.Minute)}
}

func (p *preordersImpl) OpenPreorders(ctx context.Context) ([]domain.OpenPreorder, error) {
	if v, ok := p.cache.get("open"); ok {
		return v, nil
	}
	var out []domain.OpenPreorder
	if err := getJSON(ctx, p.client, p.base+"/v1/public/preorders/open", &out); err != nil {
		return nil, err
	}
	p.cache.put("open", out)
	return out, nil
}

// ---- campaigns (content placements) ----

type campaignsImpl struct {
	base, slot string
	client     *http.Client
	cache      *cache[[]domain.Campaign]
}

var _ port.CampaignCatalog = (*campaignsImpl)(nil)

// NewCampaigns returns the CampaignCatalog over content's placements (nil when ContentURL is empty).
func NewCampaigns(cfg Config, client *http.Client) port.CampaignCatalog {
	if cfg.ContentURL == "" {
		return nil
	}
	slot := cfg.CampaignSlot
	if slot == "" {
		slot = "hero_carousel"
	}
	return &campaignsImpl{base: strings.TrimRight(cfg.ContentURL, "/"), slot: slot, client: client, cache: newCache[[]domain.Campaign](time.Minute)}
}

func (c *campaignsImpl) Campaigns(ctx context.Context, market string) ([]domain.Campaign, error) {
	if v, ok := c.cache.get(market); ok {
		return v, nil
	}
	var res struct {
		Placements []struct {
			ID         string         `json:"id"`
			Advertiser *string        `json:"advertiser"`
			Payload    map[string]any `json:"payload"`
		} `json:"placements"`
	}
	q := url.Values{"market": {market}, "slot": {c.slot}, "device": {"DESKTOP"}}
	if err := getJSON(ctx, c.client, c.base+"/v1/placements/resolve?"+q.Encode(), &res); err != nil {
		return nil, err
	}
	s := func(m map[string]any, k string) string { v, _ := m[k].(string); return v }
	out := []domain.Campaign{}
	for _, p := range res.Placements {
		cm := domain.Campaign{ID: p.ID, Kicker: s(p.Payload, "kicker"), Title: s(p.Payload, "title"), Body: s(p.Payload, "sub"),
			ImageURL: s(p.Payload, "imageUrl"), ImageAlt: s(p.Payload, "imageAlt"), CTA: s(p.Payload, "cta"), Href: s(p.Payload, "href"),
			Sponsor: s(p.Payload, "advertiser")}
		if p.Advertiser != nil && *p.Advertiser != "" {
			cm.Sponsor = *p.Advertiser
		}
		out = append(out, cm)
	}
	c.cache.put(market, out)
	return out, nil
}
