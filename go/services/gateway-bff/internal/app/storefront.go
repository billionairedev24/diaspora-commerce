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
	// Kind is domain.StoreKindSeller (the default) or domain.StoreKindProvider.
	Kind                                                  string
	OrgID, Slug, Name, Description, LogoURL, ContactEmail string
	Config                                                domain.StoreConfig
	Since                                                 time.Time
	Preview                                               bool
	// WebsiteEarned is whether the store may show its stored website links (EarnedWebsite); false hides them.
	WebsiteEarned bool
}

// publicConfig is the config as the public sees it: no phone, no email switch, and website links only when earned.
func publicConfig(cfg domain.StoreConfig, websiteEarned bool) domain.StoreConfig {
	cfg.Contact.Phone = ""
	cfg.Contact.ShowEmail = false
	social := make([]domain.StoreSocialLink, 0, len(cfg.Social))
	for _, l := range cfg.Social {
		if l.Network == domain.NetworkWebsite && !websiteEarned {
			continue
		}
		social = append(social, l)
	}
	cfg.Social = social
	return cfg
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
	ratings  port.SellerRatingsClient
	bookings port.CustomerBookingsClient
	policy   domain.StorePolicy
	gate     WebsiteGate
	clock    Clock
	cache    *ttlCache[port.UpStorefront]
	built    *ttlCache[domain.Storefront]
	products *ttlCache[domain.StorefrontProducts]
}

var _ StorefrontService = (*storefrontServiceImpl)(nil)

// StorefrontOption wires an optional dependency of the StorefrontService.
type StorefrontOption func(*storefrontServiceImpl)

// WithSellerRatings sets where a seller's rating aggregate comes from (catalog, computed from real published reviews).
// Without it a seller's page never shows a rating.
func WithSellerRatings(r port.SellerRatingsClient) StorefrontOption {
	return func(s *storefrontServiceImpl) { s.ratings = r }
}

// WebsiteGate says whether a store has earned showing its website links.
type WebsiteGate interface {
	WebsiteEarned(ctx context.Context, orgID string) bool
}

// WithWebsiteGate sets who decides whether a store's stored website links are shown. Without it they are always hidden.
func WithWebsiteGate(g WebsiteGate) StorefrontOption {
	return func(s *storefrontServiceImpl) { s.gate = g }
}

// WithProviders sets bookings, which makes provider pages bookable: their services, schedule, area, team and rating.
// Without it a provider's page has only its own settings.
func WithProviders(b port.CustomerBookingsClient) StorefrontOption {
	return func(s *storefrontServiceImpl) { s.bookings = b }
}

// NewStorefrontService returns the StorefrontService. pickup is optional (without it the page never claims pickup).
func NewStorefrontService(pages port.StorefrontsClient, search SearchService, pickup port.DeliveryFactsClient, policy domain.StorePolicy, clock Clock, opts ...StorefrontOption) StorefrontService {
	s := &storefrontServiceImpl{pages: pages, search: search, pickup: pickup, policy: policy, clock: clock,
		cache: newTTLCache[port.UpStorefront](storefrontTTL, 5*time.Minute), built: newTTLCache[domain.Storefront](storefrontTTL, 5*time.Minute),
		products: newTTLCache[domain.StorefrontProducts](30*time.Second, time.Minute)}
	for _, o := range opts {
		o(s)
	}
	return s
}

// kindOf is the organisation kind of a source or upstream value: anything but PROVIDER is a seller.
func kindOf(k string) string {
	if strings.EqualFold(k, domain.StoreKindProvider) {
		return domain.StoreKindProvider
	}
	return domain.StoreKindSeller
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

func (s *storefrontServiceImpl) config(raw json.RawMessage, kind string) domain.StoreConfig {
	cfg := domain.DefaultStoreConfigFor(kind)
	if len(raw) > 0 && string(raw) != "null" {
		var in domain.StoreConfig
		if json.Unmarshal(raw, &in) == nil {
			cfg = domain.SanitizeStoreConfig(in, s.policy.ForKind(kind))
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
		kind := kindOf(up.Kind)
		return s.Build(ctx, StorefrontSource{Kind: kind, OrgID: up.OrgID, Slug: up.Slug, Name: up.Name, Description: up.Description, LogoURL: up.LogoURL,
			Config: s.config(up.Config, kind), Since: up.OrgCreatedAt, WebsiteEarned: s.websiteEarned(ctx, up.OrgID)})
	})
	return v, err
}

// websiteEarned asks the optional website gate; without one every website link stays hidden (the safe default).
func (s *storefrontServiceImpl) websiteEarned(ctx context.Context, orgID string) bool {
	return s.gate != nil && s.gate.WebsiteEarned(ctx, orgID)
}

func (s *storefrontServiceImpl) Products(ctx context.Context, slug string, q domain.StorefrontQuery) (domain.StorefrontProducts, error) {
	up, err := s.upstream(ctx, slug)
	if err != nil {
		return domain.StorefrontProducts{}, err
	}
	if kindOf(up.Kind) == domain.StoreKindProvider {
		return domain.StorefrontProducts{Hits: []domain.ProductSummary{}, Page: 1, Size: storefrontPageSize, Categories: []domain.StorefrontCategory{}, Prices: []domain.StorefrontPrice{}}, nil
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
	key := orgID + "|" + q.Q + "|" + q.Category + "|" + q.Price + "|" + strconv.FormatBool(q.OnSale) + "|" + q.Sort + "|" + itoa(q.Page) + "|" + itoa(q.Size)
	v, _, err := s.products.get(key, s.clock.now(), func() (domain.StorefrontProducts, error) {
		res, err := s.search.Search(ctx, &domain.Caller{}, domain.SearchQuery{Q: q.Q, Department: q.Category, Price: q.Price, Sort: q.Sort,
			Page: q.Page - 1, Size: q.Size, SellerID: orgID, EligibleOnly: true, OnSale: q.OnSale})
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

// Build makes the storefront. A seller's: the first page of products, the categories, the featured picks that are still
// sellable, the rating (only when real reviews exist) and pickup (only when shown and on). A provider's: its services,
// schedule, area, team and rating from bookings.
func (s *storefrontServiceImpl) Build(ctx context.Context, src StorefrontSource) (domain.Storefront, error) {
	if kindOf(src.Kind) == domain.StoreKindProvider {
		return s.buildProvider(ctx, src), nil
	}
	first, err := s.page(ctx, src.OrgID, domain.StorefrontQuery{Page: 1, Size: storefrontPageSize})
	if err != nil {
		return domain.Storefront{}, err
	}
	cfg := src.Config
	out := domain.Storefront{Kind: domain.StoreKindSeller, Slug: src.Slug, Name: src.Name, LogoURL: strPtr(src.LogoURL), Description: strPtr(src.Description), Config: cfg,
		Preview: src.Preview, Verified: true, Categories: first.Categories, Featured: s.featured(ctx, src.OrgID, cfg.Featured), Products: first}
	if !src.Since.IsZero() {
		y := src.Since.Year()
		out.SellingSinceYear = &y
	}
	// The organisation's login email is never public (ContactEmail stays null for old clients); the phone number is never part
	// of the public page (it reaches a customer on their order only).
	out.Config = publicConfig(cfg, src.WebsiteEarned)
	out.Reviews = s.sellerReviews(ctx, src.OrgID)
	out.Pickup = s.pickupOf(ctx, src.OrgID, cfg)
	out.Indexable = !src.Preview && len(first.Hits) > 0
	return out, nil
}

// featured resolves the seller's picks by ATIN straight from search (the seller's own, sellable products), so a pick is never
// lost because it sits beyond the first pages of the catalogue; a pick that is no longer sellable (out of stock, no photo)
// is left out, and the order is the seller's.
func (s *storefrontServiceImpl) featured(ctx context.Context, orgID string, atins []string) []domain.ProductSummary {
	out := []domain.ProductSummary{}
	if len(atins) == 0 {
		return out
	}
	res, err := s.search.Search(ctx, &domain.Caller{}, domain.SearchQuery{SellerID: orgID, EligibleOnly: true, ATINs: atins, Sort: domain.SortRelevance, Size: len(atins)})
	if err != nil {
		return out
	}
	by := map[string]domain.ProductSummary{}
	for _, p := range eligibleOnly(res.Hits) {
		by[p.ATIN] = p
	}
	for _, a := range atins {
		if p, ok := by[a]; ok {
			out = append(out, p)
		}
	}
	return out
}

// sellerReviews is the seller's rating from catalog's aggregate over its real published reviews; nil with no reviews (never
// "0 reviews") or when the aggregate cannot be read (a rating is never guessed).
func (s *storefrontServiceImpl) sellerReviews(ctx context.Context, orgID string) *domain.StorefrontReviews {
	if s.ratings == nil {
		return nil
	}
	r, err := s.ratings.SellerRating(ctx, orgID)
	if err != nil {
		return nil
	}
	return reviewsOf(r.Average, r.Count)
}

// reviewsOf rounds an average to one decimal; nil unless there is at least one real review and a real average.
func reviewsOf(average float64, count int) *domain.StorefrontReviews {
	if count < 1 || average <= 0 {
		return nil
	}
	return &domain.StorefrontReviews{Average: float64(int(min(5, average)*10+0.5)) / 10, Count: count}
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
			if kindOf(st.Kind) == domain.StoreKindProvider {
				live[i] = s.providerLive(ctx, st.OrgID)
				return
			}
			res, err := s.search.Search(ctx, &domain.Caller{}, domain.SearchQuery{SellerID: st.OrgID, EligibleOnly: true, Size: 1})
			live[i] = err == nil && len(eligibleOnly(res.Hits)) > 0
		}()
	}
	wg.Wait()
	out := domain.StorefrontList{Items: []domain.StorefrontListItem{}}
	for i, st := range all {
		if live[i] {
			out.Items = append(out.Items, domain.StorefrontListItem{Kind: kindOf(st.Kind), Slug: st.Slug, PublishedAt: st.PublishedAt})
		}
	}
	return out, nil
}
