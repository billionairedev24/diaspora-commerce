package app

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"maps"
	"slices"
	"sort"
	"strings"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// SampleSource says where a template's sample data comes from.
type SampleSource string

// Sample sources.
const (
	// SampleFromEvent: a contract-shaped producer event run through the real ingest (rule, When, Enrich, identity).
	SampleFromEvent SampleSource = "event"
	// SampleFromRequest: a producer's notification.requested payload run through the real ingest.
	SampleFromRequest SampleSource = "request"
	// SampleFromMap: SampleData, for keys no event or producer request maps to.
	SampleFromMap SampleSource = "sample"
	// SampleNone: a key without sample data (e.g. a new key created by staff).
	SampleNone SampleSource = "none"
)

// TemplateSample is the data a template key renders with in previews, the template gallery and the staff console:
// exactly the fields the ingest hands the renderer in production for the key's event (see SampleEvents).
type TemplateSample struct {
	Key        string         `json:"key"`
	Area       string         `json:"area"`
	Source     SampleSource   `json:"source"`
	EventTypes []string       `json:"eventTypes"`
	Data       map[string]any `json:"data"`
}

// TemplateSamples serves sample data per template key.
type TemplateSamples interface {
	// Sample returns the key's sample; ok is false when no sample exists for the key.
	Sample(key string) (sample TemplateSample, ok bool)
	// All returns every sample, sorted by key.
	All() []TemplateSample
}

type templateSamplesImpl struct{ byKey map[string]TemplateSample }

var _ TemplateSamples = (*templateSamplesImpl)(nil)

// SampleOption configures the samples.
type SampleOption func(*sampleOptions)

type sampleOptions struct {
	products  []SampleProduct
	assetBase string
}

// WithSampleProducts replaces the sample orders' products (in line order) with real catalog products, so previews
// show real photos. Missing entries keep the defaults.
func WithSampleProducts(p []SampleProduct) SampleOption {
	return func(o *sampleOptions) { o.products = p }
}

// WithSampleAssets sets the email asset base the default sample photos (placeholder-product.png) come from
// (default <storefront>/email).
func WithSampleAssets(base string) SampleOption { return func(o *sampleOptions) { o.assetBase = base } }

// NewTemplateSamples builds the samples by running SampleEvents and SampleRequests through the real ingest (with a
// sample identity: first name "Ada", market us) and a restock event through the back-in-stock alerts, capturing the
// data each rule's Enrich produces. Keys nothing produces come from SampleData. Keys AdvertorialPolicy allows
// promotions for get sample advertorials (DataAdvertorials) built by the real Advertorials from sample catalogs.
func NewTemplateSamples(ctx context.Context, links Links, opts ...SampleOption) (TemplateSamples, error) {
	o := sampleOptions{assetBase: join(links.Storefront, "/email")}
	for _, opt := range opts {
		opt(&o)
	}
	products := sampleProducts(o)
	capture := &captureNotifier{data: map[string]map[string]any{}, source: map[string]SampleSource{}}
	discard := slog.New(slog.DiscardHandler)
	alerts := NewStockAlerts(sampleAlertStore{}, capture, sampleIdentity{}, links, discard)
	ingest := NewIngest(capture, IngestConfig{Resolver: sampleIdentity{}, Links: links, StockAlerts: alerts,
		Audience: SampleAudience()}, discard)

	handle := func(typ string, i int, payload map[string]any, src SampleSource) error {
		payload = swapProducts(payload, products).(map[string]any)
		raw, err := json.Marshal(payload)
		if err != nil {
			return fmt.Errorf("sample %s[%d]: %w", typ, i, err)
		}
		capture.current = src
		if err := ingest.Handle(ctx, events.Envelope{ID: fmt.Sprintf("sample-%s-%d", typ, i), Type: typ, Payload: raw}); err != nil {
			return fmt.Errorf("sample %s[%d]: %w", typ, i, err)
		}
		return nil
	}
	evs := SampleEvents(links)
	types := slices.Sorted(maps.Keys(evs))
	for _, typ := range types {
		for i, p := range evs[typ] {
			if err := handle(typ, i, p, SampleFromEvent); err != nil {
				return nil, err
			}
		}
	}
	reqs := SampleRequests()
	for i, key := range slices.Sorted(maps.Keys(reqs)) {
		if err := handle(GenericEventType, i, reqs[key], SampleFromRequest); err != nil {
			return nil, err
		}
	}
	if err := handle(AvailabilityChangedType, 0, map[string]any{"sku": sampleAtin, "nodeId": "HOU-FC", "ownerOrg": "retail",
		"available": 4, "onHand": 4, "reserved": 0, "version": 7}, SampleFromEvent); err != nil {
		return nil, err
	}

	triggers := TemplateTriggers()
	out := &templateSamplesImpl{byKey: map[string]TemplateSample{}}
	for key, d := range capture.data {
		out.byKey[key] = TemplateSample{Key: key, Area: TemplateArea(key), Source: capture.source[key], EventTypes: triggers[key], Data: d}
	}
	for key, d := range SampleData(links) {
		if _, done := out.byKey[key]; done {
			continue
		}
		out.byKey[key] = TemplateSample{Key: key, Area: TemplateArea(key), Source: SampleFromMap, EventTypes: triggers[key], Data: d}
	}
	ads := NewAdvertorials(AdvertorialsConfig{Products: sampleProductCatalog{products: products},
		Preorders: samplePreorderCatalog{}, Campaigns: sampleCampaignCatalog{image: products[0].ImageURL}, Links: links},
		slog.New(slog.DiscardHandler))
	for key, s := range out.byKey {
		if s.EventTypes == nil {
			s.EventTypes = []string{}
		}
		if _, allowed := AdvertorialPolicy[key]; allowed {
			if found := ads.For(ctx, AdvertorialRequest{TemplateKey: key, Category: domain.CategoryTransactional, Market: "us",
				Data: s.Data}); len(found) > 0 {
				s.Data[DataAdvertorials] = advertorialsJSON(found)
			}
		}
		out.byKey[key] = s
	}
	return out, nil
}

// advertorialsJSON is advertorials as plain JSON values, the shape the staff console edits and sends back.
func advertorialsJSON(ads []domain.Advertorial) any {
	raw, err := json.Marshal(ads)
	if err != nil {
		return nil
	}
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		return nil
	}
	return v
}

// sampleProducts are the default sample catalog over the configured real products, with the hosted placeholder
// photo where a product has none.
func sampleProducts(o sampleOptions) []SampleProduct {
	out := slices.Clone(sampleCatalog)
	for i := range out {
		if i < len(o.products) && o.products[i].Title != "" && domain.SafeURL(o.products[i].ImageURL) {
			out[i] = o.products[i]
		}
		if out[i].ImageURL == "" {
			out[i].ImageURL = strings.TrimRight(o.assetBase, "/") + "/placeholder-product.png"
		}
	}
	// Extra products for "Complete your pantry".
	for i := len(sampleCatalog); i < len(o.products); i++ {
		if o.products[i].Title != "" && domain.SafeURL(o.products[i].ImageURL) {
			out = append(out, o.products[i])
		}
	}
	return out
}

// swapProducts replaces the default sample catalog's products in a payload (matched by ATIN) with products.
func swapProducts(v any, products []SampleProduct) any {
	switch x := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(x))
		for k, e := range x {
			out[k] = swapProducts(e, products)
		}
		for i, def := range sampleCatalog {
			if str(out, "variantAtin") == def.Atin && i < len(products) {
				p := products[i]
				out["variantAtin"], out["productAtin"], out["title"], out["imageUrl"] = p.Atin, p.Atin, p.Title, p.ImageURL
				if _, has := out["unitPriceMinor"]; has {
					out["unitPriceMinor"] = def.PriceMinor
				}
			}
		}
		return out
	case []any:
		out := make([]any, len(x))
		for i, e := range x {
			out[i] = swapProducts(e, products)
		}
		return out
	default:
		return v
	}
}

// Sample catalogs for the sample advertorials.
type sampleProductCatalog struct{ products []SampleProduct }

var _ port.ProductCatalog = sampleProductCatalog{}

func (c sampleProductCatalog) Related(_ context.Context, _, _ string, limit int) ([]domain.PromoProduct, error) {
	out := []domain.PromoProduct{}
	extra := []SampleProduct{
		{Atin: "ATPEPP0001", Title: "Ata Rodo Scotch Bonnet Pepper Mix", PriceMinor: 599},
		{Atin: "ATSUYA0001", Title: "Suya Spice (Yaji), 100 g", PriceMinor: 499},
		{Atin: "ATPALM0001", Title: "Red Palm Oil, 1 L", PriceMinor: 899},
	}
	pool := append(slices.Clone(c.products[min(len(c.products), len(sampleCatalog)):]), extra...)
	for _, p := range pool {
		img := p.ImageURL
		if img == "" {
			img = c.products[0].ImageURL
		}
		out = append(out, domain.PromoProduct{Atin: p.Atin, Title: p.Title, ImageURL: img, PriceMinor: p.PriceMinor, Currency: "USD"})
		if len(out) == limit {
			break
		}
	}
	return out, nil
}

type samplePreorderCatalog struct{}

var _ port.PreorderCatalog = samplePreorderCatalog{}

func (samplePreorderCatalog) OpenPreorders(context.Context) ([]domain.OpenPreorder, error) {
	return []domain.OpenPreorder{{ID: "8c2f3d4e-0000-4000-8000-000000000008", Code: "HOU-3", Name: "Houston Pre-order #3",
		Where: "Houston, TX", City: "Houston", Region: "TX", Country: "US", Stage: "OPEN", Open: true,
		Label: "Order by Oct 19, delivered Oct 22."}}, nil
}

type sampleCampaignCatalog struct{ image string }

var _ port.CampaignCatalog = sampleCampaignCatalog{}

func (c sampleCampaignCatalog) Campaigns(context.Context, string) ([]domain.Campaign, error) {
	return []domain.Campaign{{ID: "plc_sample_jollof", Kicker: "AfroTransact Brand", Title: "Party jollof, sorted.",
		Body: "Our own seasoning, rice and pepper sauce, sold and fulfilled by AfroTransact.", ImageURL: c.image,
		ImageAlt: "A pot of party jollof rice", CTA: "Shop AfroTransact Brand", Href: "/s?q=AfroTransact+Brand"}}, nil
}

func (s *templateSamplesImpl) Sample(key string) (TemplateSample, bool) {
	t, ok := s.byKey[key]
	if !ok {
		return TemplateSample{Key: key, Area: TemplateArea(key), Source: SampleNone, EventTypes: []string{}, Data: map[string]any{}}, false
	}
	t.Data = maps.Clone(t.Data)
	return t, true
}

func (s *templateSamplesImpl) All() []TemplateSample {
	out := make([]TemplateSample, 0, len(s.byKey))
	for _, k := range slices.Sorted(maps.Keys(s.byKey)) {
		t, _ := s.Sample(k)
		out = append(out, t)
	}
	return out
}

// TemplateTriggers maps each template key to the event types that send it (from DefaultEventRules,
// RequestedTemplates and the back-in-stock alerts), sorted.
func TemplateTriggers() map[string][]string {
	out := map[string][]string{}
	add := func(key, typ string) {
		if !slices.Contains(out[key], typ) {
			out[key] = append(out[key], typ)
		}
	}
	for typ, rules := range DefaultEventRules {
		for _, r := range rules {
			add(r.Template, typ)
		}
	}
	for key := range RequestedTemplates {
		add(key, GenericEventType)
	}
	add(StockAlertTemplate, AvailabilityChangedType)
	for k := range out {
		sort.Strings(out[k])
	}
	return out
}

// Template areas, used to group templates in the gallery and the staff console.
const (
	AreaAccount   = "account"
	AreaOrders    = "orders"
	AreaSeller    = "seller"
	AreaPayouts   = "payouts"
	AreaBookings  = "bookings"
	AreaTrust     = "trust"
	AreaRetail    = "retail"
	AreaMarketing = "marketing"
	AreaSecurity  = "security"
	AreaOther     = "other"
)

// TemplateArea groups a template key by product area (security codes, account, orders and shipments, seller, payouts,
// bookings, trust and safety, retail, marketing).
func TemplateArea(key string) string {
	has := func(prefixes ...string) bool {
		return slices.ContainsFunc(prefixes, func(p string) bool { return key == p || strings.HasPrefix(key, p+".") })
	}
	switch {
	case has("identity.verification", "identity.password_reset", "identity.password_changed"):
		return AreaSecurity
	case has("account", "org", "careers", "identity"):
		return AreaAccount
	case has("order", "shipment", "payment", "preorder"):
		return AreaOrders
	case has("payout", "seller.payouts"):
		return AreaPayouts
	case has("claim", "listing", "seller.suspended", "seller.reinstated"):
		return AreaTrust
	case has("seller"):
		return AreaSeller
	case has("booking"):
		return AreaBookings
	case has("retail"):
		return AreaRetail
	case has("newsletter", "stock", "marketing"):
		return AreaMarketing
	default:
		return AreaOther
	}
}

// captureNotifier records the data of the first request per template key instead of enqueueing.
type captureNotifier struct {
	data    map[string]map[string]any
	source  map[string]SampleSource
	current SampleSource
}

var _ Notifier = (*captureNotifier)(nil)

func (c *captureNotifier) Submit(_ context.Context, req domain.Request) ([]domain.Notification, error) {
	c.record(req)
	return nil, nil
}

func (c *captureNotifier) SubmitForEvent(_ context.Context, _, _ string, reqs []domain.Request) (bool, error) {
	for _, r := range reqs {
		c.record(r)
	}
	return true, nil
}

func (c *captureNotifier) record(r domain.Request) {
	if _, ok := c.data[r.TemplateKey]; ok {
		return
	}
	d := maps.Clone(r.Data)
	if d == nil {
		d = map[string]any{}
	}
	c.data[r.TemplateKey], c.source[r.TemplateKey] = d, c.current
}

// sampleIdentity answers recipient lookups like identity would for a typical shopper or seller owner.
type sampleIdentity struct{}

var _ port.RecipientResolver = sampleIdentity{}

func (sampleIdentity) Contact(_ context.Context, id string) (port.Contact, error) {
	return port.Contact{UserID: id, FirstName: "Ada", Email: id + "@example.com", Phone: "+15550001111",
		PushTokens: []domain.PushToken{{Token: "tok-" + id, Platform: domain.PushFCM}}, Locale: "en",
		TimeZone: "America/Chicago", Market: "us"}, nil
}

func (sampleIdentity) OrgOwners(context.Context, string) ([]string, error) {
	return []string{"owner-1"}, nil
}

// sampleAudienceImpl answers pre-order audience pages like delivery-runs would: one signed-in subscriber (cust-1)
// on every channel, plus an email-only waitlist sign-up.
type sampleAudienceImpl struct{}

var _ port.PreorderAudience = sampleAudienceImpl{}

// SampleAudience returns the pre-order audience used by the samples, the gallery and the integration tests.
func SampleAudience() port.PreorderAudience { return sampleAudienceImpl{} }

func (sampleAudienceImpl) Audience(_ context.Context, windowID string, _ domain.Channel, segment, _ string, _ int) (port.AudiencePage, error) {
	unsub := "http://localhost:3000/pre-orders/waitlist/unsubscribe?token=sample-" + windowID
	if segment == port.AudienceWaitlist {
		return port.AudiencePage{Members: []port.AudienceMember{{SubscriptionID: "3e7a1c2b-0000-4000-8000-000000000001",
			Email: "cust-1@example.com", UnsubscribeURL: unsub}}}, nil
	}
	return port.AudiencePage{Members: []port.AudienceMember{{SubscriptionID: "3e7a1c2b-0000-4000-8000-000000000002", UserID: "cust-1",
		UnsubscribeURL: unsub}}}, nil
}

const sampleAtin = "ATOGBO0500"

// sampleAlertStore holds one active back-in-stock alert for sampleAtin.
type sampleAlertStore struct{}

var _ port.StockAlertStore = sampleAlertStore{}

func (sampleAlertStore) Subscribe(_ context.Context, a domain.StockAlert) (domain.StockAlert, bool, error) {
	return a, true, nil
}

func (sampleAlertStore) Unsubscribe(context.Context, string, string) error { return nil }

func (sampleAlertStore) ListByUser(context.Context, string) ([]domain.StockAlert, error) {
	return nil, nil
}

func (sampleAlertStore) ClaimForRestock(_ context.Context, atin, _ string, _ int) ([]domain.StockAlert, error) {
	return []domain.StockAlert{{ID: "6f1c2a7e-0d4b-4c7e-9a51-3b2f8e9d0a11", UserID: "cust-1", Atin: atin,
		Email: "cust-1@example.com", Market: "us", Locale: "en", ProductTitle: "Ogbono seeds 500g",
		Status: domain.StockAlertNotified}}, nil
}
