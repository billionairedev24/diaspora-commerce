package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/mail"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// SiteService serves content-backed screens: site config, the home page (placements with product rails resolved
// through search and catalog), newsletter sign-up and careers.
type SiteService interface {
	SiteConfig(ctx context.Context) (domain.SiteConfig, error)
	Home(ctx context.Context, c *domain.Caller) (domain.Home, error)
	Subscribe(ctx context.Context, email string) (string, error)
	Jobs(ctx context.Context) ([]domain.Job, error)
	Apply(ctx context.Context, jobID string, a domain.JobApplication) (domain.ApplicationReceipt, error)
	// LegalDocument serves a legal page (current version, or `version`); unknown documents/versions are 404.
	LegalDocument(ctx context.Context, key, version, locale string) (domain.LegalPage, error)
	// CookiePolicy is the cookie banner configuration and the cookie table.
	CookiePolicy(ctx context.Context) (domain.CookiePolicy, error)
	// HelpArticles is a page of published help articles (the hub excluded), filtered by topic and text.
	HelpArticles(ctx context.Context, q domain.HelpQuery) (domain.HelpArticlePage, error)
	// HelpArticle is one published help page (domain.HelpHub is the help center hub); unknown pages are 404.
	HelpArticle(ctx context.Context, slug string) (domain.HelpArticle, error)
	// RecordConsent stores the caller's cookie choice under the BFF-chosen subject; secGPC is the Sec-GPC header.
	RecordConsent(ctx context.Context, c *domain.Caller, in domain.ConsentChoice, secGPC bool) (domain.ConsentRecord, error)
	// SubmitPrivacyRequest validates and forwards a privacy request (the signed-in account is attached).
	SubmitPrivacyRequest(ctx context.Context, c *domain.Caller, in domain.PrivacyRequest) (domain.PrivacyRequestReceipt, error)
}

// SiteDeps are the SiteService's collaborators.
type SiteDeps struct {
	Content   port.ContentClient
	Identity  port.IdentityClient
	Search    port.SearchClient
	Catalog   port.CatalogClient
	Inventory port.InventoryClient
	// Offers (optional) adds offer counts, lowest prices and the buy box winner to rail cards.
	Offers    port.StorefrontOffersClient
	Location  LocationService
	Estimator domain.Estimator
	Sellers   SellerDirectory
	Market    string
	Clock     Clock
	Log       *slog.Logger
}

type siteServiceImpl struct{ d SiteDeps }

var _ SiteService = (*siteServiceImpl)(nil)

// NewSiteService returns the SiteService.
func NewSiteService(d SiteDeps) SiteService {
	if d.Log == nil {
		d.Log = slog.Default()
	}
	return &siteServiceImpl{d: d}
}

// SiteConfig maps content's public site configuration onto the contract and merges identity's enabled social
// providers (content only holds their display order).
func (s *siteServiceImpl) SiteConfig(ctx context.Context) (domain.SiteConfig, error) {
	cfg, err := s.d.Content.SiteConfig(ctx)
	if err != nil {
		return domain.SiteConfig{}, mapErr("content", err)
	}
	enabled := map[string]bool{}
	if providers, err := s.d.Identity.Providers(ctx); err == nil {
		for _, p := range providers {
			enabled[strings.ToLower(p.ID)] = true
		}
	} else {
		s.d.Log.InfoContext(ctx, "identity providers unavailable; social sign-in shown as disabled", "error", err)
	}
	body, err := json.Marshal(siteConfigBody(cfg, enabled))
	if err != nil {
		return domain.SiteConfig{}, fmt.Errorf("encode site config: %w", err)
	}
	sum := sha256.Sum256(body)
	return domain.SiteConfig{Body: body, ETag: fmt.Sprintf(`"%d-%s"`, cfg.Revision, hex.EncodeToString(sum[:8])), CacheControl: cfg.CacheControl}, nil
}

var socialNetworks = map[string]bool{"tiktok": true, "instagram": true, "facebook": true, "x": true, "youtube": true, "linkedin": true, "whatsapp": true}

func siteConfigBody(c port.ContentSiteConfig, enabled map[string]bool) map[string]any {
	str := func(k string) string {
		v, _ := c.Contacts[k].(string)
		return v
	}
	socials := []map[string]string{}
	for _, so := range c.Socials {
		if socialNetworks[strings.ToLower(so.Network)] && strings.HasPrefix(so.URL, "https://") {
			socials = append(socials, map[string]string{"network": strings.ToLower(so.Network), "url": so.URL, "label": firstNonEmpty(so.Label, so.Network)})
		}
	}
	legal := []map[string]string{}
	// Content's public config already decides which documents are shown; its draft flag (legal review pending) is
	// not part of the storefront contract, so every listed document is linked.
	for _, l := range c.Legal {
		legal = append(legal, map[string]string{"key": l.Key, "title": l.Title, "version": l.Version, "effective": l.Effective, "href": l.Href})
	}
	idps := c.IdentityProviders
	sort.SliceStable(idps, func(i, j int) bool { return idps[i].Order < idps[j].Order })
	providers := []map[string]any{}
	for _, p := range idps {
		id := strings.ToLower(p.ID)
		if socialProviders[id] {
			providers = append(providers, map[string]any{"id": id, "name": firstNonEmpty(p.Name, p.ID), "enabled": enabled[id]})
		}
	}
	locales := []map[string]string{}
	for _, l := range c.Locales {
		dir := l.Dir
		if dir != "rtl" {
			dir = "ltr"
		}
		locales = append(locales, map[string]string{"code": l.Code, "name": l.Name, "dir": dir})
	}
	return map[string]any{
		"revision": c.Revision,
		"socials":  socials,
		"contacts": map[string]string{"phone": str("phone"), "privacyEmail": str("privacyEmail"), "accessibilityEmail": str("accessibilityEmail"),
			"legalEmail": str("legalEmail"), "pressEmail": str("pressEmail"), "partnersEmail": str("partnersEmail"), "address": str("address")},
		"legal":             legal,
		"identityProviders": providers,
		"locales":           locales,
		"market": map[string]string{"code": firstNonEmpty(c.Market.Code, "us"), "currency": firstNonEmpty(c.Market.Currency, domain.Currency),
			"country": firstNonEmpty(c.Market.Country, "US")},
		"appLinks":       map[string]*string{"ios": strPtr(c.AppLinks.IOS), "android": strPtr(c.AppLinks.Android)},
		"paymentMethods": orEmpty(c.PaymentMethods),
		"smsProgramName": c.SMSProgramName,
	}
}

// defaultPlacements keep the home page useful while content is unavailable: product rails only.
func defaultPlacements() domain.Placements {
	return domain.Placements{Rails: []domain.RailPlacement{
		{ID: "fastest", Title: "Fast delivery to {city}", Href: "/s?fulfilment=afrotransact", Source: domain.RailSource{Kind: "fulfilled-fastest", Limit: 12}},
		{ID: "popular", Title: "Popular right now", Href: "/s", Source: domain.RailSource{Kind: "search", Sort: domain.SortRating, Limit: 12}},
	}}
}

func (s *siteServiceImpl) Home(ctx context.Context, c *domain.Caller) (domain.Home, error) {
	loc := s.d.Location.Current(ctx, c)
	pl, err := s.d.Content.Placements(ctx, s.d.Market, loc.ZIP)
	if err != nil {
		s.d.Log.InfoContext(ctx, "placements unavailable; serving default rails", "error", err)
		pl = defaultPlacements()
	}
	rails := make([]*domain.Rail, len(pl.Rails))
	var wg sync.WaitGroup
	for i, r := range pl.Rails {
		wg.Go(func() {
			items, err := s.railItems(ctx, r.Source)
			if err != nil {
				s.d.Log.InfoContext(ctx, "rail not resolved", "rail", r.ID, "error", err)
				return
			}
			if len(items) > 0 {
				rails[i] = &domain.Rail{ID: r.ID, Title: strings.ReplaceAll(r.Title, "{city}", firstNonEmpty(loc.City, "you")), Href: r.Href, Items: items}
			}
		})
	}
	wg.Wait()
	home := domain.Home{Announcements: orEmpty(pl.Announcements), Hero: orEmpty(pl.Hero), Tiles: orEmpty(pl.Tiles),
		Banners: orEmpty(pl.Banners), Rails: []domain.Rail{}, DeliverTo: loc}
	for _, r := range rails {
		if r != nil {
			home.Rails = append(home.Rails, *r)
		}
	}
	for i := range home.Tiles {
		if len(home.Tiles[i].Cells) > 4 {
			home.Tiles[i].Cells = home.Tiles[i].Cells[:4]
		}
		home.Tiles[i].Cells = orEmpty(home.Tiles[i].Cells)
	}
	return home, nil
}

func orEmpty[T any](v []T) []T {
	if v == nil {
		return []T{}
	}
	return v
}

func (s *siteServiceImpl) railItems(ctx context.Context, src domain.RailSource) ([]domain.ProductSummary, error) {
	limit := src.Limit
	if limit <= 0 || limit > 24 {
		limit = 12
	}
	now := s.d.Clock.now()
	if src.Kind == "atins" {
		return s.byATIN(ctx, src.ATINs, limit, now), nil
	}
	p := port.SearchParams{Q: src.Query, Market: s.d.Market, Department: src.Department, Sort: src.Sort, Size: limit, InStock: true, NoTrack: true}
	switch src.Kind {
	case "fulfilled-fastest":
		p.Fulfilment = domain.FulfilledByAfroTransact
	case "department-bestsellers":
		p.Sort = domain.SortRating
	}
	if p.Sort == "" || p.Sort == domain.SortFastest {
		p.Sort = domain.SortRelevance
	}
	// Content rails may say "rice OR gari OR fufu flour"; search requires every word, so run one query per term.
	terms := strings.Split(p.Q, " OR ")
	out := []domain.ProductSummary{}
	seen := map[string]bool{}
	var firstErr error
	for _, term := range terms {
		q := p
		q.Q = strings.TrimSpace(term)
		res, err := s.d.Search.Search(ctx, q)
		if err != nil {
			firstErr = err
			continue
		}
		for _, h := range res.Hits {
			if !seen[h.ATIN] && len(out) < limit {
				seen[h.ATIN] = true
				out = append(out, summaryFromHit(h, s.d.Estimator, now))
			}
		}
	}
	if len(out) == 0 && firstErr != nil {
		return nil, fmt.Errorf("rail search: %w", firstErr)
	}
	return withOffers(ctx, s.d.Offers, out), nil
}

// byATIN builds cards for explicitly placed products from catalog + inventory (unknown products are skipped).
func (s *siteServiceImpl) byATIN(ctx context.Context, atins []string, limit int, now time.Time) []domain.ProductSummary {
	if len(atins) > limit {
		atins = atins[:limit]
	}
	out := make([]*domain.ProductSummary, len(atins))
	var wg sync.WaitGroup
	for i, a := range atins {
		wg.Go(func() {
			p, _, err := resolveProduct(ctx, s.d.Catalog, a)
			if err != nil || len(p.Variants) == 0 {
				return
			}
			v := p.Variants[0]
			avail, err := s.d.Inventory.Available(ctx, []string{v.ATIN})
			if err != nil {
				return
			}
			seller, fulfilment := s.d.Sellers.Describe(p.OwnerKind, p.SellerOrgID)
			qty := max(0, avail[v.ATIN])
			status := domain.StockStatusFor(qty)
			var img *string
			if len(p.Images) > 0 {
				img = strPtr(p.Images[0].URL)
			}
			minPrice := v.PriceMinor
			for _, x := range p.Variants {
				minPrice = min(minPrice, x.PriceMinor)
			}
			out[i] = &domain.ProductSummary{ATIN: p.ATIN, Title: p.Title, Brand: attr(p.Attributes, "brand"),
				Community: attr(p.Attributes, "community", "communities"), Department: departmentFor(ctx, s.d.Catalog, p.CategoryID),
				Unit: firstNonEmpty(attr(p.Attributes, "unit", "size", "netContent"), optionsLabel(v.Options)), Rating: 0, ReviewCount: 0,
				ImageURL: img, OfferID: v.ATIN, PriceMinor: v.PriceMinor, ListPriceMinor: nil, Currency: firstNonEmpty(strings.ToUpper(v.Currency), domain.Currency),
				SellerName: seller.Name, SellerKind: seller.Kind, SellerCity: "", Fulfilment: fulfilment, AvailableQty: qty,
				InStock: qty > 0, LowStock: status == domain.LowStock, StockStatus: status, Delivery: s.d.Estimator.Promise(fulfilment, 0, now),
				OfferCount: 1, MinOfferPriceMinor: minPrice, Sponsor: nil}
		})
	}
	wg.Wait()
	res := []domain.ProductSummary{}
	for _, p := range out {
		if p != nil {
			res = append(res, *p)
		}
	}
	return withOffers(ctx, s.d.Offers, res)
}

func validEmail(e string) bool {
	a, err := mail.ParseAddress(strings.TrimSpace(e))
	return err == nil && a.Address == strings.TrimSpace(e) && strings.Contains(e, ".")
}

func (s *siteServiceImpl) Subscribe(ctx context.Context, email string) (string, error) {
	if !validEmail(email) {
		return "", domain.ValidationProblem(map[string]string{"email": "Enter a valid email address"})
	}
	status, err := s.d.Content.Subscribe(ctx, strings.ToLower(strings.TrimSpace(email)))
	if err != nil {
		return "", mapErr("content", err)
	}
	if status != "subscribed" { // content may also say "unsubscribed" (re-subscribing needs confirmation again)
		status = "pending_confirmation"
	}
	return status, nil
}

func (s *siteServiceImpl) Jobs(ctx context.Context) ([]domain.Job, error) {
	jobs, err := s.d.Content.Jobs(ctx)
	if err != nil {
		return nil, mapErr("content", err)
	}
	for i := range jobs {
		jobs[i].Responsibilities = orEmpty(jobs[i].Responsibilities)
		jobs[i].Requirements = orEmpty(jobs[i].Requirements)
	}
	return orEmpty(jobs), nil
}

var workAuth = map[string]bool{"Yes": true, "Yes, with sponsorship": true, "No": true}

// ValidateApplication applies the contract's JobApplication rules.
func ValidateApplication(a domain.JobApplication) map[string]string {
	errs := map[string]string{}
	if len(strings.TrimSpace(a.Name)) < 3 {
		errs["name"] = "Enter your full name"
	}
	if !validEmail(a.Email) {
		errs["email"] = "Enter a valid email address"
	}
	if l := strings.TrimSpace(a.LinkedIn); l != "" && !strings.HasPrefix(l, "https://linkedin.com/") && !strings.HasPrefix(l, "https://www.linkedin.com/") {
		errs["linkedin"] = "Enter a LinkedIn URL (https://linkedin.com/…)"
	}
	if !workAuth[a.WorkAuthorization] {
		errs["workAuthorization"] = "Choose an option"
	}
	if strings.TrimSpace(a.ResumeAssetID) == "" {
		errs["resumeAssetId"] = "Upload your résumé"
	}
	if a.ResumeFileName != "" && !strings.HasSuffix(strings.ToLower(a.ResumeFileName), ".pdf") {
		errs["resumeFileName"] = "Upload your résumé as a PDF"
	}
	if !a.Consent {
		errs["consent"] = "Please confirm"
	}
	return errs
}

func (s *siteServiceImpl) Apply(ctx context.Context, jobID string, a domain.JobApplication) (domain.ApplicationReceipt, error) {
	if errs := ValidateApplication(a); len(errs) > 0 {
		return domain.ApplicationReceipt{}, domain.ValidationProblem(errs)
	}
	r, err := s.d.Content.Apply(ctx, jobID, a)
	if err != nil {
		if errors.Is(err, port.ErrNotFound) {
			return r, domain.NewProblem(http.StatusNotFound, domain.CodeJobNotFound, "Role not found", "This role is no longer open.").WithCause(err)
		}
		return r, mapErr("content", err)
	}
	return r, nil
}
