package app

import (
	"context"
	"log/slog"
	"net/url"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// AdvertorialPolicy lists, per template key, the promotion kinds its email may show below the main content, in
// order. Templates that are not listed never carry promotions: security codes and alerts, invitations, payment
// problems, cancellations and refunds, claims, and every seller, provider and retail email.
var AdvertorialPolicy = map[string][]string{
	"order.confirmed":           {domain.AdProducts, domain.AdPreorder},
	"preorder.confirmed":        {domain.AdProducts},
	"shipment.shipped":          {domain.AdProducts},
	"shipment.out_for_delivery": {domain.AdCampaign},
	"shipment.ready_for_pickup": {domain.AdCampaign},
	"shipment.delivered":        {domain.AdProducts},
	"booking.confirmed":         {domain.AdCampaign},
	"booking.reminder":          {domain.AdCampaign},
	"account.welcome":           {domain.AdCampaign, domain.AdProducts},
	"stock.back_in_stock":       {domain.AdProducts},
	"preorder.announce":         {domain.AdProducts},
	"preorder.reminder":         {domain.AdProducts},
	"preorder.waitlist.opened":  {domain.AdProducts},
}

// AdvertorialsAllowed reports whether a template may show promotions at all (never security emails).
func AdvertorialsAllowed(key string, category domain.Category) bool {
	return category != domain.CategorySecurity && len(AdvertorialPolicy[key]) > 0
}

// AdvertorialRequest is what the notifier knows when it renders an email.
type AdvertorialRequest struct {
	TemplateKey string
	Category    domain.Category
	Market      string
	Data        map[string]any
}

// Advertorials finds the promotions for one email: related products ("Complete your pantry"), an open pre-order
// in the customer's city, or a house campaign. Best-effort: every source is bounded by the timeout and failures
// only drop the block.
type Advertorials interface {
	For(ctx context.Context, req AdvertorialRequest) []domain.Advertorial
}

// AdvertorialsConfig wires the sources; nil sources are skipped.
type AdvertorialsConfig struct {
	Products  port.ProductCatalog
	Preorders port.PreorderCatalog
	Campaigns port.CampaignCatalog
	Links     Links
	// Timeout bounds all lookups for one email (default 800 ms).
	Timeout time.Duration
	// Max is the number of advertorial blocks per email (default 1).
	Max int
}

type advertorialsImpl struct {
	cfg AdvertorialsConfig
	log *slog.Logger
}

var _ Advertorials = (*advertorialsImpl)(nil)

// NewAdvertorials returns the advertorial finder.
func NewAdvertorials(cfg AdvertorialsConfig, log *slog.Logger) Advertorials {
	if cfg.Timeout <= 0 {
		cfg.Timeout = 800 * time.Millisecond
	}
	if cfg.Max <= 0 {
		cfg.Max = 1
	}
	return &advertorialsImpl{cfg: cfg, log: log}
}

func (a *advertorialsImpl) For(ctx context.Context, req AdvertorialRequest) []domain.Advertorial {
	if !AdvertorialsAllowed(req.TemplateKey, req.Category) {
		return nil
	}
	ctx, cancel := context.WithTimeout(ctx, a.cfg.Timeout)
	defer cancel()
	kinds := AdvertorialPolicy[req.TemplateKey]
	found := make([]*domain.Advertorial, len(kinds))
	var wg sync.WaitGroup
	for i, kind := range kinds {
		wg.Go(func() {
			ad, ok := a.one(ctx, kind, req)
			if ok && ad.Valid() {
				found[i] = &ad
			}
		})
	}
	wg.Wait()
	out := []domain.Advertorial{}
	for _, ad := range found {
		if ad != nil && len(out) < a.cfg.Max {
			out = append(out, *ad)
		}
	}
	return out
}

func (a *advertorialsImpl) one(ctx context.Context, kind string, req AdvertorialRequest) (domain.Advertorial, bool) {
	switch kind {
	case domain.AdProducts:
		return a.products(ctx, req)
	case domain.AdPreorder:
		return a.preorder(ctx, req)
	case domain.AdCampaign:
		return a.campaign(ctx, req)
	}
	return domain.Advertorial{}, false
}

// productSeed is the title the related products are found for: the order's first line, else the product the
// email is about.
func productSeed(d map[string]any) string {
	for _, s := range objects(d, "shipments") {
		for _, l := range objects(s, "lines") {
			if t := str(l, "title"); t != "" {
				return t
			}
		}
	}
	for _, l := range objects(d, "items") {
		if t := str(l, "title"); t != "" {
			return t
		}
	}
	return firstNonEmptyStr(str(d, "productTitle"), str(d, "title"))
}

func (a *advertorialsImpl) products(ctx context.Context, req AdvertorialRequest) (domain.Advertorial, bool) {
	if a.cfg.Products == nil {
		return domain.Advertorial{}, false
	}
	seed := productSeed(req.Data)
	found, err := a.cfg.Products.Related(ctx, seed, req.Market, 8)
	if err != nil {
		a.log.DebugContext(ctx, "advertorial products unavailable", "template", req.TemplateKey, "error", err)
		return domain.Advertorial{}, false
	}
	exclude := orderIDs(req.Data)
	if atin := str(req.Data, "atin"); atin != "" {
		exclude = append(exclude, atin)
	}
	ad := domain.Advertorial{Kind: domain.AdProducts, Eyebrow: "Picked for you", Title: "Complete your pantry",
		CTALabel: "Shop more like this", CTAURL: join(a.cfg.Links.Storefront, "/s"), Source: domain.PromoSource{Service: "search"}}
	if seed != "" {
		ad.CTAURL = join(a.cfg.Links.Storefront, "/s?q="+url.QueryEscape(seedQuery(seed)))
	}
	if strings.HasPrefix(req.TemplateKey, "preorder.") || req.TemplateKey == "account.welcome" {
		ad.Title = "Taste of home, delivered"
	}
	for _, p := range found {
		if slices.Contains(exclude, p.Atin) || !domain.SafeURL(p.ImageURL) || p.Title == "" {
			continue
		}
		ad.Products = append(ad.Products, domain.PromoItem{Title: p.Title, ImageURL: p.ImageURL,
			Price: money(p.PriceMinor, firstNonEmptyStr(p.Currency, "USD")), URL: join(a.cfg.Links.Storefront, "/p/"+url.PathEscape(p.Atin))})
		if len(ad.Products) == 3 {
			break
		}
	}
	if len(ad.Products) < 2 {
		return domain.Advertorial{}, false
	}
	return ad, true
}

// seedQuery keeps the first words of a product title as the "more like this" search ("Ofada Rice, Destoned" →
// "Ofada Rice").
func seedQuery(title string) string {
	title, _, _ = strings.Cut(title, ",")
	f := strings.Fields(title)
	if len(f) > 3 {
		f = f[:3]
	}
	return strings.Join(f, " ")
}

func (a *advertorialsImpl) preorder(ctx context.Context, req AdvertorialRequest) (domain.Advertorial, bool) {
	city, region := strings.TrimSpace(str(req.Data, "shipToCity")), strings.TrimSpace(str(req.Data, "shipToState"))
	if a.cfg.Preorders == nil || city == "" {
		return domain.Advertorial{}, false
	}
	windows, err := a.cfg.Preorders.OpenPreorders(ctx)
	if err != nil {
		a.log.DebugContext(ctx, "advertorial pre-orders unavailable", "template", req.TemplateKey, "error", err)
		return domain.Advertorial{}, false
	}
	ordered := map[string]bool{}
	for _, s := range objects(req.Data, "shipments") {
		if pre, _ := s["preorder"].(map[string]any); pre != nil {
			ordered[str(pre, "windowId")] = true
		}
	}
	for _, w := range windows {
		if !w.Open || ordered[w.ID] || !strings.EqualFold(w.City, city) || region != "" && !strings.EqualFold(w.Region, region) {
			continue
		}
		where := firstNonEmptyStr(w.Where, w.City)
		return domain.Advertorial{Kind: domain.AdPreorder, Eyebrow: "Pre-order open in " + w.City, Title: w.Name,
			Body:     firstNonEmptyStr(w.Label, "Order together with your neighbours in "+where+" and we deliver on one day.") + " No minimum, one delivery fee.",
			ImageURL: w.HeroURL, ImageAlt: w.Name, CTALabel: "See the pre-order",
			CTAURL: withAttribution(join(a.cfg.Links.Storefront, "/pre-orders"), "email", w.Code, "advertorial"),
			Source: domain.PromoSource{Service: "delivery-runs", ID: w.ID}}, true
	}
	return domain.Advertorial{}, false
}

func (a *advertorialsImpl) campaign(ctx context.Context, req AdvertorialRequest) (domain.Advertorial, bool) {
	if a.cfg.Campaigns == nil {
		return domain.Advertorial{}, false
	}
	market := firstNonEmptyStr(req.Market, "us")
	cs, err := a.cfg.Campaigns.Campaigns(ctx, market)
	if err != nil {
		a.log.DebugContext(ctx, "advertorial campaigns unavailable", "template", req.TemplateKey, "error", err)
		return domain.Advertorial{}, false
	}
	for _, c := range cs {
		href := c.Href
		if href != "" && !domain.SafeURL(href) {
			href = join(a.cfg.Links.Storefront, "/"+strings.TrimLeft(href, "/"))
		}
		if c.Title == "" || href == "" {
			continue
		}
		return domain.Advertorial{Kind: domain.AdCampaign, Eyebrow: firstNonEmptyStr(c.Kicker, "From AfroTransact"), Title: c.Title,
			Body: c.Body, ImageURL: c.ImageURL, ImageAlt: firstNonEmptyStr(c.ImageAlt, c.Title), CTALabel: firstNonEmptyStr(c.CTA, "Shop now"),
			CTAURL: href, Sponsor: c.Sponsor, Source: domain.PromoSource{Service: "content", ID: c.ID}}, true
	}
	return domain.Advertorial{}, false
}
