package upstreams

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ---------------------------------------------------------------- content (contracts/openapi/content.yaml)

type contentImpl struct {
	c       httpclient.Client
	cache   port.Cache
	ceiling time.Duration
}

var _ port.ContentClient = (*contentImpl)(nil)

// NewContent returns the content-service client. Site config, placements and eligibility are cached for the
// upstream's max-age (capped by ceiling) — they are public and keyed by market/ZIP, never by user.
func NewContent(c httpclient.Client, cache port.Cache, ceiling time.Duration) port.ContentClient {
	return &contentImpl{c: c, cache: cache, ceiling: ceiling}
}

func (k *contentImpl) ttl(h http.Header) time.Duration { return ttlFor(h, k.ceiling, k.ceiling) }

type siteConfigEntry struct {
	Config       port.ContentSiteConfig `json:"config"`
	CacheControl string                 `json:"cacheControl"`
}

func (k *contentImpl) SiteConfig(ctx context.Context) (port.ContentSiteConfig, error) {
	e, err := cached(k.cache, "content:site-config", func() (siteConfigEntry, http.Header, error) {
		var cfg port.ContentSiteConfig
		res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/site-config"}, &cfg)
		if err != nil {
			return siteConfigEntry{}, nil, err
		}
		return siteConfigEntry{Config: cfg, CacheControl: res.Header.Get("Cache-Control")}, res.Header, nil
	}, k.ttl)
	if err != nil {
		return port.ContentSiteConfig{}, err
	}
	e.Config.CacheControl = e.CacheControl
	return e.Config, nil
}

// servedPlacement is content's ServedPlacement: the slot decides the payload shape (JSON Schemas in content's slot
// catalogue: announcement_bar, hero_carousel, home_tiles, display_banner, home_product_row).
type servedPlacement struct {
	ID         string          `json:"id"`
	Slot       string          `json:"slot"`
	Label      string          `json:"label"`
	Advertiser string          `json:"advertiser"`
	Payload    json.RawMessage `json:"payload"`
}

func (k *contentImpl) Placements(ctx context.Context, market, zip string) (domain.Placements, error) {
	return cached(k.cache, "content:placements:"+market+":"+zip, func() (domain.Placements, http.Header, error) {
		var out struct {
			Placements []servedPlacement `json:"placements"`
		}
		q := url.Values{"market": {market}, "device": {"DESKTOP"}}
		if zip != "" {
			q.Set("zip", zip)
		}
		res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/placements/resolve", Query: q}, &out)
		if err != nil {
			return domain.Placements{}, nil, err
		}
		return placementsFrom(out.Placements), res.Header, nil
	}, k.ttl)
}

// placementsFrom maps slot payloads onto the home page shapes; payloads that do not decode are skipped.
func placementsFrom(in []servedPlacement) domain.Placements {
	var p domain.Placements
	for _, sp := range in {
		switch sp.Slot {
		case "announcement_bar":
			var a domain.Announcement
			if json.Unmarshal(sp.Payload, &a) == nil && a.Text != "" {
				a.ID = sp.ID
				p.Announcements = append(p.Announcements, a)
			}
		case "hero_carousel":
			var h domain.HeroSlide
			if json.Unmarshal(sp.Payload, &h) == nil && h.Title != "" {
				h.ID = sp.ID
				if h.Label == "" {
					h.Label = "AfroTransact"
				}
				p.Hero = append(p.Hero, h)
			}
		case "home_tiles":
			var t domain.Tile
			if json.Unmarshal(sp.Payload, &t) == nil && t.Title != "" {
				t.ID = sp.ID
				p.Tiles = append(p.Tiles, t)
			}
		case "display_banner":
			var b struct {
				Position   string  `json:"position"`
				Advertiser string  `json:"advertiser"`
				Title      string  `json:"title"`
				Sub        string  `json:"sub"`
				CTA        string  `json:"cta"`
				Href       string  `json:"href"`
				ImageURL   *string `json:"imageUrl"`
			}
			if json.Unmarshal(sp.Payload, &b) == nil && b.Title != "" {
				adv := b.Advertiser
				if adv == "" {
					adv = sp.Advertiser
				}
				p.Banners = append(p.Banners, domain.Banner{ID: sp.ID, Slot: b.Position, Advertiser: adv, Title: b.Title, Sub: b.Sub,
					CTA: b.CTA, Href: b.Href, ImageURL: b.ImageURL})
			}
		case "home_product_row":
			var r struct {
				Title string   `json:"title"`
				Href  string   `json:"href"`
				Query string   `json:"query"`
				SKUs  []string `json:"skus"`
			}
			if json.Unmarshal(sp.Payload, &r) == nil && r.Title != "" {
				src := domain.RailSource{Kind: "search", Query: r.Query, Limit: 12}
				if len(r.SKUs) > 0 {
					src = domain.RailSource{Kind: "atins", ATINs: r.SKUs, Limit: 24}
				}
				p.Rails = append(p.Rails, domain.RailPlacement{ID: sp.ID, Title: r.Title, Href: r.Href, Source: src})
			}
		}
	}
	return p
}

func (k *contentImpl) Subscribe(ctx context.Context, email string) (string, error) {
	var out struct {
		Status string `json:"status"`
	}
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/newsletter/subscriptions",
		JSON: map[string]string{"email": email, "source": "storefront"}}, &out)
	return out.Status, err
}

func (k *contentImpl) Jobs(ctx context.Context) ([]domain.Job, error) {
	var out struct {
		Jobs []struct {
			domain.Job
			Status string `json:"status"`
		} `json:"jobs"`
	}
	if _, err := call(ctx, k.c, httpclient.Request{Path: "/v1/careers/jobs"}, &out); err != nil {
		return nil, err
	}
	jobs := make([]domain.Job, 0, len(out.Jobs))
	for _, j := range out.Jobs {
		if j.Status == "" || j.Status == "OPEN" {
			jobs = append(jobs, j.Job)
		}
	}
	return jobs, nil
}

func (k *contentImpl) Apply(ctx context.Context, jobID string, a domain.JobApplication) (domain.ApplicationReceipt, error) {
	var out domain.ApplicationReceipt
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/careers/jobs/" + url.PathEscape(jobID) + "/applications", JSON: a}, &out)
	if out.JobID == "" {
		out.JobID = jobID
	}
	return out, err
}

func (k *contentImpl) Eligibility(ctx context.Context, country, state, zip string) (domain.MarketEligibility, error) {
	return cached(k.cache, "content:eligibility:"+country+":"+state+":"+zip, func() (domain.MarketEligibility, http.Header, error) {
		var out domain.MarketEligibility
		q := url.Values{"country": {country}}
		if state != "" {
			q.Set("state", state)
		}
		if zip != "" {
			q.Set("zip", zip)
		}
		res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/markets/eligibility", Query: q}, &out)
		if err != nil {
			return out, nil, err
		}
		return out, res.Header, nil
	}, k.ttl)
}

// ---------------------------------------------------------------- payments (planned paths)

type paymentsImpl struct{ c httpclient.Client }

var _ port.PaymentsClient = (*paymentsImpl)(nil)

// NewPayments returns the payments client (saved payment methods and SetupIntents of the token's customer).
func NewPayments(c httpclient.Client) port.PaymentsClient { return &paymentsImpl{c: c} }

func (p *paymentsImpl) PaymentMethods(ctx context.Context, bearer string) ([]domain.PaymentMethod, error) {
	res, err := call(ctx, p.c, httpclient.Request{Path: "/v1/customers/me/payment-methods", Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[domain.PaymentMethod](res.Body)
	return items, err
}

func (p *paymentsImpl) SetupIntent(ctx context.Context, bearer string) (domain.SetupIntent, error) {
	var out domain.SetupIntent
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/customers/me/setup-intents", Bearer: bearer}, &out)
	return out, err
}

func (p *paymentsImpl) DeletePaymentMethod(ctx context.Context, bearer, id string) error {
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodDelete, Path: "/v1/customers/me/payment-methods/" + url.PathEscape(id), Bearer: bearer}, nil)
	return err
}

// ---------------------------------------------------------------- promise (planned)

type promiseImpl struct{ c httpclient.Client }

var _ port.PromiseClient = (*promiseImpl)(nil)

// NewPromise returns the promise client (POST /v1/promises {zip, items} → {promises:[{atin, days}]}).
func NewPromise(c httpclient.Client) port.PromiseClient { return &promiseImpl{c: c} }

func (p *promiseImpl) Promises(ctx context.Context, zip string, items []port.PromiseItem) (map[string]int, error) {
	var out struct {
		Promises []struct {
			ATIN string `json:"atin"`
			Days int    `json:"days"`
		} `json:"promises"`
	}
	if _, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/promises", RetryUnsafe: true,
		JSON: map[string]any{"zip": zip, "items": items}}, &out); err != nil {
		return nil, err
	}
	m := make(map[string]int, len(out.Promises))
	for _, x := range out.Promises {
		m[x.ATIN] = x.Days
	}
	return m, nil
}

// ---------------------------------------------------------------- orders (planned)

type ordersImpl struct{ c httpclient.Client }

var _ port.OrdersClient = (*ordersImpl)(nil)

// NewOrders returns the orders client (orders of the token's customer, checkout, shipment cancel, returns).
func NewOrders(c httpclient.Client) port.OrdersClient { return &ordersImpl{c: c} }

func (o *ordersImpl) List(ctx context.Context, bearer, cursor string, limit int) (domain.Page[domain.OrderSummary], error) {
	q := url.Values{"customer": {"me"}, "limit": {strconv.Itoa(limit)}}
	if cursor != "" {
		q.Set("cursor", cursor)
	}
	res, err := call(ctx, o.c, httpclient.Request{Path: "/v1/orders", Query: q, Bearer: bearer}, nil)
	if err != nil {
		return domain.Page[domain.OrderSummary]{}, err
	}
	items, next, err := itemsOf[domain.OrderSummary](res.Body)
	return domain.Page[domain.OrderSummary]{Items: items, NextCursor: next}, err
}

func (o *ordersImpl) Place(ctx context.Context, bearer, key string, req domain.PlaceOrderRequest, lines []port.OrderLine) (port.PlaceOrderResult, error) {
	body := map[string]any{"addressId": req.AddressID, "deliverySelections": req.DeliverySelections, "lines": lines,
		"gift": req.Gift}
	if req.PaymentMethodID != "" {
		body["paymentMethodId"] = req.PaymentMethodID
	}
	if req.Wallet != "" {
		body["wallet"] = req.Wallet
	}
	if code := strings.TrimSpace(req.CouponCode); code != "" {
		body["couponCode"] = code
	}
	var out domain.OrderConfirmation
	res, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/orders", Bearer: bearer, JSON: body,
		Header: http.Header{"Idempotency-Key": {key}}, RetryUnsafe: true}, &out)
	if err != nil {
		return port.PlaceOrderResult{}, err
	}
	return port.PlaceOrderResult{Status: res.Status, Confirmation: out}, nil
}

func (o *ordersImpl) CancelShipment(ctx context.Context, bearer, order, shipment string) (domain.OrderSummary, error) {
	var out domain.OrderSummary
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Bearer: bearer,
		Path: "/v1/orders/" + url.PathEscape(order) + "/shipments/" + url.PathEscape(shipment) + "/cancel"}, &out)
	return out, err
}

func (o *ordersImpl) Returns(ctx context.Context, bearer string) (domain.Page[domain.ReturnSummary], error) {
	res, err := call(ctx, o.c, httpclient.Request{Path: "/v1/returns", Query: url.Values{"customer": {"me"}}, Bearer: bearer}, nil)
	if err != nil {
		return domain.Page[domain.ReturnSummary]{}, err
	}
	items, next, err := itemsOf[domain.ReturnSummary](res.Body)
	return domain.Page[domain.ReturnSummary]{Items: items, NextCursor: next}, err
}

func (o *ordersImpl) StartReturn(ctx context.Context, bearer string, r domain.StartReturn) (domain.ReturnSummary, error) {
	var out domain.ReturnSummary
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/returns", Bearer: bearer, JSON: r}, &out)
	return out, err
}

// ---------------------------------------------------------------- notifications (contracts/openapi/notifications.yaml)

type notificationsImpl struct{ c httpclient.Client }

var _ port.NotificationsClient = (*notificationsImpl)(nil)

// NewNotifications returns the notifications client for back-in-stock alerts (POST/GET /v1/stock-alerts,
// DELETE /v1/stock-alerts/{atin}), always with the shopper's own token.
func NewNotifications(c httpclient.Client) port.NotificationsClient { return &notificationsImpl{c: c} }

func (n *notificationsImpl) SubscribeStockAlert(ctx context.Context, bearer string, in port.StockAlertInput) error {
	// 201 armed, 200 already active: both succeed.
	_, err := call(ctx, n.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/stock-alerts", Bearer: bearer, JSON: in}, nil)
	return err
}

func (n *notificationsImpl) StockAlerts(ctx context.Context, bearer string) ([]port.StockAlert, error) {
	res, err := call(ctx, n.c, httpclient.Request{Path: "/v1/stock-alerts", Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.StockAlert](res.Body)
	return items, err
}

func (n *notificationsImpl) RemoveStockAlert(ctx context.Context, bearer, atin string) error {
	_, err := call(ctx, n.c, httpclient.Request{Method: http.MethodDelete, Path: "/v1/stock-alerts/" + url.PathEscape(atin), Bearer: bearer}, nil)
	return err
}
