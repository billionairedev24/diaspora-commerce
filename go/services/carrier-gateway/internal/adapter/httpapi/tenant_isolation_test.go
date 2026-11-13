package httpapi_test

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	carriergateway "github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

type memLabels struct {
	mu sync.Mutex
	m  map[string]domain.LabelRecord
}

func (s *memLabels) Reserve(_ context.Context, r domain.LabelRecord) (domain.LabelRecord, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.m[r.ID] = r
	return r, true, nil
}

func (s *memLabels) Complete(_ context.Context, r domain.LabelRecord, _ events.Envelope) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.m[r.ID] = r
	return nil
}
func (s *memLabels) Release(context.Context, string) error { return nil }
func (s *memLabels) Get(_ context.Context, id string) (domain.LabelRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if r, ok := s.m[id]; ok {
		return r, nil
	}
	return domain.LabelRecord{}, domain.ErrNotFound
}

func (s *memLabels) ByTracking(_ context.Context, c domain.CarrierCode, tn string) (domain.LabelRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range s.m {
		if r.Carrier == c && r.TrackingNumber == tn {
			return r, nil
		}
	}
	return domain.LabelRecord{}, domain.ErrNotFound
}

func (s *memLabels) MarkVoided(_ context.Context, id string, at time.Time, _ events.Envelope) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	r := s.m[id]
	r.Status, r.VoidedAt = domain.LabelVoided, &at
	s.m[id] = r
	return nil
}
func (s *memLabels) Ping(context.Context) error { return nil }

type memObjects struct{}

func (memObjects) Put(context.Context, string, string, []byte) error { return nil }
func (memObjects) Get(context.Context, string) ([]byte, error) {
	return []byte("%PDF-secret-label"), nil
}
func (memObjects) SignedURL(_ context.Context, key string, _ time.Duration) (string, error) {
	return "https://signed.example/" + key, nil
}

type memPickupRepo struct {
	mu sync.Mutex
	m  map[string]domain.PickupLocation
}

func (p *memPickupRepo) SavePickup(context.Context, domain.Pickup, domain.PickupRequest) error {
	return nil
}

func (p *memPickupRepo) SaveLocation(_ context.Context, l domain.PickupLocation) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.m[l.ID] = l
	return nil
}

func (p *memPickupRepo) GetLocation(_ context.Context, id string) (domain.PickupLocation, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if l, ok := p.m[id]; ok {
		return l, nil
	}
	return domain.PickupLocation{}, domain.ErrNotFound
}

func (p *memPickupRepo) ListLocations(_ context.Context, owner, _ string, activeOnly bool) ([]domain.PickupLocation, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := []domain.PickupLocation{}
	for _, l := range p.m {
		if (owner == "" || l.OwnerOrg == owner) && (!activeOnly || l.Active) {
			out = append(out, l)
		}
	}
	return out, nil
}

type nopOutbox struct{}

func (nopOutbox) Enqueue(context.Context, events.Envelope) error { return nil }
func (nopOutbox) Dispatch(context.Context, int, func(context.Context, events.Envelope) error) (int, error) {
	return 0, nil
}

type stubAccounts struct{}

func (stubAccounts) Accounts(context.Context, string) ([]domain.Account, error) { return nil, nil }

var (
	_ port.LabelRepository  = (*memLabels)(nil)
	_ port.ObjectStore      = memObjects{}
	_ port.PickupRepository = (*memPickupRepo)(nil)
	_ port.Outbox           = nopOutbox{}
)

func hoursOf() []domain.OpeningHours {
	return []domain.OpeningHours{{Weekday: time.Monday, Open: "09:00", Close: "17:00"}}
}

// tenantServer wires the real label and pickup use cases (only the stores are in memory) behind the real auth middleware.
func tenantServer(t *testing.T, w tenanttest.World) (http.Handler, *memLabels, *memPickupRepo) {
	t.Helper()
	labels := &memLabels{m: map[string]domain.LabelRecord{}}
	pickups := &memPickupRepo{m: map[string]domain.PickupLocation{}}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	svc := httpapi.Services{
		Labels:  app.NewLabelService(app.Carriers{}, stubAccounts{}, labels, memObjects{}, domain.BillingPolicy{}, log, time.Now),
		Pickups: app.NewPickupService(app.Carriers{}, stubAccounts{}, pickups, nopOutbox{}, time.Now),
		Prompt:  app.NewPickupPromptService(nil, time.Now),
		Billing: domain.BillingPolicy{},
	}
	mux := http.NewServeMux()
	httpapi.New(svc).Register(mux, w.Auth(true), w.Auth(false))
	return mux, labels, pickups
}

func TestCrossTenantPickupLocationsAndLabels(t *testing.T) {
	w := tenanttest.NewWorld(tenanttest.SellerPerms(app.PermRate, app.PermLabel, app.PermVoid), tenanttest.StaffPerms(app.PermLabel, app.PermVoid))
	h, labels, pickups := tenantServer(t, w)
	addr := domain.Address{Line1: "SECRET-LINE-B", City: "Houston", State: "TX", PostalCode: "77002"}
	pickups.m["loc-b-draft"] = domain.PickupLocation{ID: "loc-b-draft", Kind: domain.PickupSeller, OwnerOrg: w.OrgB, Name: "B draft",
		Address: addr, Timezone: "America/Chicago", Hours: hoursOf()}
	pickups.m["loc-b-live"] = domain.PickupLocation{ID: "loc-b-live", Kind: domain.PickupSeller, OwnerOrg: w.OrgB, Name: "B live", Address: addr,
		Timezone: "America/Chicago", Hours: hoursOf(), Active: true, EnabledBy: "user_seller_b"}
	labels.m["lbl-b"] = domain.LabelRecord{ID: "lbl-b", OrgID: w.OrgB, Carrier: domain.UPS, TrackingNumber: "1ZB", LabelKey: "labels/b.pdf",
		Status: domain.LabelPurchased, Format: domain.FormatPDF}

	attackers := []tenanttest.Principal{w.SellerA, w.CustomerA, w.Anonymous}
	tenanttest.CrossTenant(t, h, []tenanttest.Principal{w.SellerA, w.CustomerA, w.Anonymous}, []tenanttest.Request{
		tenanttest.Get("/v1/pickup-locations/loc-b-draft"),
	}, "SECRET-LINE-B")
	tenanttest.CrossTenant(t, h, []tenanttest.Principal{w.SellerA, w.CustomerA}, []tenanttest.Request{
		tenanttest.Get("/v1/labels/lbl-b"),
		tenanttest.Get("/v1/labels/lbl-b/document"),
		tenanttest.Get("/v1/labels/by-tracking/ups/1ZB/document"),
		tenanttest.Send(http.MethodPost, "/v1/labels/lbl-b/void", ""),
		tenanttest.Get("/v1/tracking/ups/1ZB"),
		tenanttest.Send(http.MethodPut, "/v1/pickup-locations/loc-b-live",
			`{"kind":"SELLER","ownerOrg":"`+w.OrgB+`","name":"hijack","timezone":"America/Chicago","active":true,"hoursConfirmed":true,`+
				`"address":{"line1":"1 Evil St","postalCode":"1"},"hours":[{"weekday":1,"open":"09:00","close":"17:00"}]}`),
		tenanttest.Send(http.MethodPost, "/v1/pickup-locations/loc-b-live/ready", `{"orderRef":"o","customerRef":"c"}`),
		tenanttest.Get("/v1/pickup-locations?includeInactive=true&ownerOrg=" + w.OrgB),
	}, "SECRET-LINE-B", "%PDF-secret-label")

	if labels.m["lbl-b"].Status == domain.LabelVoided {
		t.Fatal("another organisation voided the label")
	}
	if pickups.m["loc-b-live"].Name != "B live" {
		t.Fatal("another organisation changed the location")
	}
	for _, a := range attackers {
		res := tenanttest.Do(h, a, tenanttest.Get("/v1/pickup-locations/loc-b-live"))
		tenanttest.All(tenanttest.Allowed(), tenanttest.Leaks("user_seller_b"))(t, res)
		list := tenanttest.Do(h, a, tenanttest.Get("/v1/pickup-locations?ownerOrg="+w.OrgB))
		tenanttest.All(tenanttest.Allowed(), tenanttest.Leaks("user_seller_b", "B draft"))(t, list)
	}
	tenanttest.Run(t, h, []tenanttest.Case{
		{Name: "owner reads own draft", As: w.SellerB, Req: tenanttest.Get("/v1/pickup-locations/loc-b-draft"), Expect: tenanttest.Allowed()},
		{Name: "owner reads own label", As: w.SellerB, Req: tenanttest.Get("/v1/labels/lbl-b"), Expect: tenanttest.Allowed()},
		{Name: "staff reads label", As: w.Staff, Req: tenanttest.Get("/v1/labels/lbl-b"), Expect: tenanttest.Allowed()},
	})
}

// TestSellerCannotSpendAnotherOriginsCarrierAccount: the origin node picks the carrier account, so a seller may only
// rate, buy and book pickups from its own organisation's origin.
func TestSellerCannotSpendAnotherOriginsCarrierAccount(t *testing.T) {
	w := tenanttest.NewWorld(tenanttest.SellerPerms(app.PermRate, app.PermLabel, app.PermVoid), tenanttest.StaffPerms(app.PermLabel, app.PermVoid))
	h, _, _ := tenantServer(t, w)
	ship := func(origin string) string {
		return `{"originNode":"` + origin + `","shipDate":"2026-10-12","from":{"line1":"1","city":"H","postalCode":"77002","country":"US"},` +
			`"to":{"line1":"2","city":"B","postalCode":"11201","country":"US"},"parcels":[{"weightLb":2}]}`
	}
	for _, origin := range []string{w.OrgB, "fc_houston_tx"} {
		tenanttest.Run(t, h, []tenanttest.Case{
			{Name: "rates " + origin, As: w.SellerA, Req: tenanttest.Send(http.MethodPost, "/v1/rates", `{"shipment":`+ship(origin)+`}`), Expect: tenanttest.Status(http.StatusForbidden)},
			{Name: "labels " + origin, As: w.SellerA, Req: tenanttest.Send(http.MethodPost, "/v1/labels", `{"carrier":"ups","service":"03","shipment":`+ship(origin)+`}`).
				WithHeader("Idempotency-Key", "k"), Expect: tenanttest.Status(http.StatusForbidden)},
			{Name: "return " + origin, As: w.SellerA, Req: tenanttest.Send(http.MethodPost, "/v1/return-labels", `{"shipment":`+ship(origin)+`}`).
				WithHeader("Idempotency-Key", "k"), Expect: tenanttest.Status(http.StatusForbidden)},
			{Name: "pickup " + origin, As: w.SellerA, Req: tenanttest.Send(http.MethodPost, "/v1/pickups",
				`{"carrier":"ups","originNode":"`+origin+`","date":"2026-10-12"}`), Expect: tenanttest.Status(http.StatusForbidden)},
		})
	}
}

func TestRouteMatrix(t *testing.T) {
	m, err := carriergateway.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "."), string(m), ".", "../../app")
}
