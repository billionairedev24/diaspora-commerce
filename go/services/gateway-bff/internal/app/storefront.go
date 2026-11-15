package app

import (
	"context"
	"encoding/json"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// StorefrontService serves a seller's public storefront (/b/{slug}): the published settings plus the seller's real,
// sellable products. Only a published page of an approved seller exists; everything else is a 404.
type StorefrontService interface {
	Get(ctx context.Context, slug string) (domain.Storefront, error)
	Products(ctx context.Context, slug string, q domain.StorefrontQuery) (domain.StorefrontProducts, error)
	// Build makes the storefront from a source (the seller's preview uses the draft).
	Build(ctx context.Context, src StorefrontSource) (domain.Storefront, error)
	// FindProducts is the seller's featured-product picker: sellable products of the organisation matching the words.
	FindProducts(ctx context.Context, orgID, text string) ([]domain.ProductSummary, error)
	// List is the published storefronts that have at least one sellable product (the sitemap's stores).
	List(ctx context.Context) (domain.StorefrontList, error)
}

// StorefrontSource is everything the page is made from besides the live products.
type StorefrontSource struct {
	OrgID, Slug, Name, Description, LogoURL, ContactEmail string
	Config                                                domain.StoreConfig
	Since                                                 time.Time
	Preview                                               bool
}

const (
	storefrontPageSize   = 24
	storefrontMaxSize    = 48
	storefrontScanSize   = 48
	storefrontScanPages  = 3
	storefrontPickerSize = 20
	storefrontTTL        = 60 * time.Second
)

type storefrontServiceImpl struct {
	pages    port.StorefrontsClient
	search   SearchService
	pickup   port.DeliveryFactsClient
	policy   domain.StorePolicy
	clock    Clock
	cache    *ttlCache[port.UpStorefront]
	built    *ttlCache[domain.Storefront]
	products *ttlCache[domain.StorefrontProducts]
}

var _ StorefrontService = (*storefrontServiceImpl)(nil)

// NewStorefrontService returns the StorefrontService. pickup is optional (without it the page never claims pickup).
func NewStorefrontService(pages port.StorefrontsClient, search SearchService, pickup port.DeliveryFactsClient, policy domain.StorePolicy, clock Clock) StorefrontService {
	return &storefrontServiceImpl{pages: pages, search: search, pickup: pickup, policy: policy, clock: clock,
		cache: newTTLCache[port.UpStorefront](storefrontTTL, 5*time.Minute), built: newTTLCache[domain.Storefront](storefrontTTL, 5*time.Minute),
		products: newTTLCache[domain.StorefrontProducts](30*time.Second, time.Minute)}
}

func storefrontNotFound(cause error) error {
	p := domain.NewProblem(http.StatusNotFound, domain.CodeNotFound, "Store not found", "There is no store at this address.")
	if cause != nil {
		return p.WithCause(cause)
	}
	return p
}

func (s *storefrontServiceImpl) upstream(ctx context.Context, slug string) (port.UpStorefront, error) {
	slug = strings.ToLower(strings.TrimSpace(slug))
	if len(slug) > 80 || !businessSlug.MatchString(slug) {
		return port.UpStorefront{}, storefrontNotFound(nil)
	}
	up, _, err := s.cache.get(slug, s.clock.now(), func() (port.UpStorefront, error) {
		out, err := s.pages.Storefront(ctx, slug)
		if err != nil {
			if p, ok := port.AsUpstreamProblem(err); ok && p.Status == http.StatusNotFound {
				return out, storefrontNotFound(err)
			}
			return out, mapErr("seller", err)
		}
		return out, nil
	})
	return up, err
}

func (s *storefrontServiceImpl) config(raw json.RawMessage) domain.StoreConfig {
	cfg := domain.DefaultStoreConfig()
	if len(raw) > 0 && string(raw) != "null" {
		var in domain.StoreConfig
		if json.Unmarshal(raw, &in) == nil {
			cfg = domain.SanitizeStoreConfig(in, s.policy)
		}
	}
	return cfg
}

func (s *storefrontServiceImpl) Get(ctx context.Context, slug string) (domain.Storefront, error) {
	up, err := s.upstream(ctx, slug)
	if err != nil {
		return domain.Storefront{}, err
	}
	v, _, err := s.built.get(up.Slug, s.clock.now(), func() (domain.Storefront, error) {
		return s.Build(ctx, StorefrontSource{OrgID: up.OrgID, Slug: up.Slug, Name: up.Name, Description: up.Description, LogoURL: up.LogoURL,
			ContactEmail: up.ContactEmail, Config: s.config(up.Config), Since: up.OrgCreatedAt})
	})
	return v, err
}

func (s *storefrontServiceImpl) Products(ctx context.Context, slug string, q domain.StorefrontQuery) (domain.StorefrontProducts, error) {
	up, err := s.upstream(ctx, slug)
	if err != nil {
		return domain.StorefrontProducts{}, err
	}
	return s.page(ctx, up.OrgID, q)
}

func clampPage(q domain.StorefrontQuery) domain.StorefrontQuery {
	if q.Size <= 0 {
		q.Size = storefrontPageSize
	}
	q.Size = min(q.Size, storefrontMaxSize)
	q.Page = max(q.Page, 1)
	q.Q = strings.TrimSpace(q.Q)
	if len([]rune(q.Q)) > 80 {
		q.Q = string([]rune(q.Q)[:80])
	}
	switch q.Sort {
	case domain.SortPriceAsc, domain.SortPriceDesc, domain.SortRating, domain.SortNewest:
	default:
		q.Sort = domain.SortRelevance
	}
	return q
}

// page is one page of the organisation's sellable products. Only in-stock products with a real photo reach the grid:
// search filters (eligibleOnly) and the same photo rule is applied again here.
func (s *storefrontServiceImpl) page(ctx context.Context, orgID string, q domain.StorefrontQuery) (domain.StorefrontProducts, error) {
	q = clampPage(q)
	key := orgID + "|" + q.Q + "|" + q.Category + "|" + q.Price + "|" + q.Sort + "|" + itoa(q.Page) + "|" + itoa(q.Size)
	v, _, err := s.products.get(key, s.clock.now(), func() (domain.StorefrontProducts, error) {
		res, err := s.search.Search(ctx, &domain.Caller{}, domain.SearchQuery{Q: q.Q, Department: q.Category, Price: q.Price, Sort: q.Sort,
			Page: q.Page - 1, Size: q.Size, SellerID: orgID, EligibleOnly: true})
		if err != nil {
			return domain.StorefrontProducts{}, err
		}
		return domain.StorefrontProducts{Hits: eligibleOnly(res.Hits), Total: res.Total, Page: q.Page, Size: res.Size, PageCount: res.PageCount,
			Categories: categoriesOf(res.Facets.Department), Prices: pricesOf(res.Facets.Price)}, nil
	})
	if err != nil {
		return domain.StorefrontProducts{}, err
	}
	return v, nil
}

func itoa(n int) string { return strconv.Itoa(n) }

func categoriesOf(in []domain.FacetBucket) []domain.StorefrontCategory {
	out := make([]domain.StorefrontCategory, 0, len(in))
	for _, b := range in {
		if b.Count > 0 && strings.TrimSpace(b.Value) != "" {
			out = append(out, domain.StorefrontCategory{Name: b.Value, Count: int(b.Count)})
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Count > out[j].Count })
	return out
}

func pricesOf(in []domain.FacetBucket) []domain.StorefrontPrice {
	out := make([]domain.StorefrontPrice, 0, len(in))
	for _, b := range in {
		if b.Count > 0 {
			out = append(out, domain.StorefrontPrice{Value: b.Value, Label: b.Label, Count: int(b.Count)})
		}
	}
	return out
}

// Build makes the storefront: the first page of products, the categories, the seller's featured picks that are still
// sellable, the reviews summary (only when there are reviews) and pickup (only when shown and on).
func (s *storefrontServiceImpl) Build(ctx context.Context, src StorefrontSource) (domain.Storefront, error) {
	first, err := s.page(ctx, src.OrgID, domain.StorefrontQuery{Page: 1, Size: storefrontPageSize})
	if err != nil {
		return domain.Storefront{}, err
	}
	// The whole sellable range, for featured picks and the reviews summary (a few pages at most).
	var all []domain.ProductSummary
	for p := 1; p <= storefrontScanPages; p++ {
		pg, err := s.page(ctx, src.OrgID, domain.StorefrontQuery{Page: p, Size: storefrontScanSize})
		if err != nil {
			break
		}
		all = append(all, pg.Hits...)
		if p >= pg.PageCount {
			break
		}
	}
	cfg := src.Config
	byATIN := map[string]domain.ProductSummary{}
	for _, p := range all {
		byATIN[p.ATIN] = p
	}
	featured := make([]domain.ProductSummary, 0, len(cfg.Featured))
	for _, a := range cfg.Featured {
		if p, ok := byATIN[a]; ok {
			featured = append(featured, p)
		}
	}
	out := domain.Storefront{Slug: src.Slug, Name: src.Name, LogoURL: strPtr(src.LogoURL), Description: strPtr(src.Description), Config: cfg,
		Preview: src.Preview, Verified: true, Categories: first.Categories, Featured: featured, Products: first}
	if !src.Since.IsZero() {
		y := src.Since.Year()
		out.SellingSinceYear = &y
	}
	if cfg.Contact.ShowEmail {
		out.ContactEmail = strPtr(src.ContactEmail)
	}
	out.Reviews = reviewsOf(all)
	out.Pickup = s.pickupOf(ctx, src.OrgID, cfg)
	out.Indexable = !src.Preview && len(first.Hits) > 0
	return out, nil
}

// reviewsOf averages the real ratings of the products that have reviews, weighted by review count; nil when none do
// (never "0 reviews").
func reviewsOf(products []domain.ProductSummary) *domain.StorefrontReviews {
	var count int
	var sum float64
	for _, p := range products {
		if p.ReviewCount > 0 && p.Rating > 0 {
			count += p.ReviewCount
			sum += p.Rating * float64(p.ReviewCount)
		}
	}
	if count == 0 {
		return nil
	}
	return &domain.StorefrontReviews{Average: float64(int(sum/float64(count)*10+0.5)) / 10, Count: count}
}

// pickupOf is the ZIP of the seller's first active pickup place, only when the seller shows pickup. Carrier names and
// who collects are never part of it.
func (s *storefrontServiceImpl) pickupOf(ctx context.Context, orgID string, cfg domain.StoreConfig) *domain.StorefrontPickup {
	if !cfg.Pickup.Show || s.pickup == nil {
		return nil
	}
	places, err := s.pickup.PickupLocations(ctx, orgID, "")
	if err != nil {
		return nil
	}
	for _, p := range places {
		if p.Active && p.Kind == "SELLER" && p.OwnerOrg == orgID && len(p.Address.PostalCode) >= 5 {
			zip := p.Address.PostalCode[:5]
			return &domain.StorefrontPickup{Zip: zip}
		}
	}
	return nil
}

func (s *storefrontServiceImpl) FindProducts(ctx context.Context, orgID, text string) ([]domain.ProductSummary, error) {
	res, err := s.search.Search(ctx, &domain.Caller{}, domain.SearchQuery{Q: strings.TrimSpace(text), SellerID: orgID, EligibleOnly: true,
		Sort: domain.SortRelevance, Size: storefrontPickerSize})
	if err != nil {
		return nil, err
	}
	return eligibleOnly(res.Hits), nil
}

func (s *storefrontServiceImpl) List(ctx context.Context) (domain.StorefrontList, error) {
	all, err := s.pages.PublishedStorefronts(ctx)
	if err != nil {
		return domain.StorefrontList{}, mapErr("seller", err)
	}
	live := make([]bool, len(all))
	sem := make(chan struct{}, 8)
	var wg sync.WaitGroup
	for i, st := range all {
		sem <- struct{}{}
		wg.Add(1)
		go func() {
			defer func() { <-sem; wg.Done() }()
			res, err := s.search.Search(ctx, &domain.Caller{}, domain.SearchQuery{SellerID: st.OrgID, EligibleOnly: true, Size: 1})
			live[i] = err == nil && len(eligibleOnly(res.Hits)) > 0
		}()
	}
	wg.Wait()
	out := domain.StorefrontList{Items: []domain.StorefrontListItem{}}
	for i, st := range all {
		if live[i] {
			out.Items = append(out.Items, domain.StorefrontListItem{Slug: st.Slug, PublishedAt: st.PublishedAt})
		}
	}
	return out, nil
}
