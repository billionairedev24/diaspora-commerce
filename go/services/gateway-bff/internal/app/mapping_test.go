package app

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

var est = domain.NewEstimator(domain.DeliveryPolicy{SellerShippingMinor: 599, FreeShippingThresholdMinor: 4900})

func TestSummaryFromHit(t *testing.T) {
	now := time.Date(2026, 10, 7, 9, 0, 0, 0, time.UTC)
	s := summaryFromHit(port.SearchHit{ATIN: "ATSHEA0001", Title: "Shea", CategoryPath: "Beauty › Skin", SellerKind: "SELLER",
		Fulfilment: "SELLER", PriceMinor: 1250, Rating: 7, InStock: true, LowStock: true, AvailableQty: 2, DeliveryDays: 3}, est, now)
	if s.OfferID != "ATSHEA0001" || s.Department != "Beauty" || s.Rating != 5 || s.StockStatus != domain.LowStock || !s.LowStock ||
		s.SellerName != "Marketplace seller" || s.Currency != "USD" || s.OfferCount != 1 || s.ImageURL != nil || s.Delivery.FeeMinor != 599 {
		t.Fatalf("%+v", s)
	}
	out := summaryFromHit(port.SearchHit{ATIN: "ATGARI0001", SellerKind: "PLATFORM", Fulfilment: "AFROTRANSACT", InStock: false, AvailableQty: 9}, est, now)
	if out.StockStatus != domain.OutOfStock || out.InStock || out.AvailableQty != 0 || out.SellerName != PlatformSellerName || !out.Delivery.Free {
		t.Fatalf("%+v", out)
	}
	seller := summaryFromHit(port.SearchHit{ATIN: "ATX0000001", InStock: true}, est, now) // seller-managed stock without a count
	if seller.StockStatus != domain.InStock || !seller.InStock {
		t.Fatalf("%+v", seller)
	}
}

func TestSellerDirectory(t *testing.T) {
	d := SellerDirectory{RetailOrgID: "org_retail", Names: map[string]string{"org_k": "Kente"}}
	if s, f := d.Describe("SELLER", "org_retail"); s.Kind != domain.SellerKindPlatform || f != domain.FulfilledByAfroTransact {
		t.Fatalf("retail org: %+v %s", s, f)
	}
	if s, f := d.Describe("SELLER", "org_k"); s.Name != "Kente" || f != domain.ShipsFromSeller {
		t.Fatalf("seller: %+v %s", s, f)
	}
	if s, _ := d.Describe("SELLER", "org_unknown"); s.Name != "Marketplace seller" {
		t.Fatalf("unknown: %+v", s)
	}
}

func TestErrorMapping(t *testing.T) {
	cases := []struct {
		err  error
		code string
		st   int
	}{
		{fmt.Errorf("x: %w", domain.ErrUpstreamUnavailable), domain.CodeUpstreamUnavailable, 503},
		{context.DeadlineExceeded, domain.CodeUpstreamUnavailable, 503},
		{&port.UpstreamProblem{Status: 401}, domain.CodeUnauthenticated, 401},
		{&port.UpstreamProblem{Status: 404}, domain.CodeNotFound, 404},
		{&port.UpstreamProblem{Status: 422, Fields: map[string]string{"email": "bad"}}, domain.CodeValidationFailed, 422},
		{&port.UpstreamProblem{Status: 429, RetryAfter: 9}, domain.CodeRateLimited, 429},
		{&port.UpstreamProblem{Status: 418}, domain.CodeUpstreamError, 502},
		{errors.New("decode"), domain.CodeUpstreamError, 502},
	}
	for _, c := range cases {
		p, ok := domain.AsProblem(mapErr("svc", c.err))
		if !ok || p.Code != c.code || p.Status != c.st {
			t.Fatalf("%v → %+v", c.err, p)
		}
	}
	identity := []struct{ code, want string }{
		{"identity.email-taken", domain.CodeEmailTaken},
		{"identity.invalid-code", domain.CodeInvalidCode},
		{"identity.code-expired", domain.CodeChallengeExpired},
		{"identity.too-many-attempts", domain.CodeChallengeExpired},
		{"identity.resend-too-soon", domain.CodeRateLimited},
	}
	for _, c := range identity {
		p, _ := domain.AsProblem(mapIdentityErr(&port.UpstreamProblem{Status: 422, Code: c.code}))
		if p.Code != c.want {
			t.Fatalf("%s → %s", c.code, p.Code)
		}
	}
	p, _ := domain.AsProblem(mapIdentityErr(&port.UpstreamProblem{Status: 422, Code: "identity.invalid-sign-up", Fields: map[string]string{"password": "contains your name"}}))
	if p.Code != domain.CodeWeakPassword || p.Errors["password"] != "contains your name" {
		t.Fatalf("weak password = %+v", p)
	}
	p, _ = domain.AsProblem(cartErr(&port.UpstreamProblem{Status: 404, Code: port.CartLineNotFound}, domain.CodeSavedNotFound))
	if p.Code != domain.CodeSavedNotFound {
		t.Fatalf("saved not found = %+v", p)
	}
}

func TestSessionCookieIsSigned(t *testing.T) {
	store := &memSessions{m: map[string]domain.Session{}}
	svc := NewSessionService(store, nil, nil, SessionOptions{SigningKey: "k-0123456789abcdef0123456789abcdef"})
	c := &domain.Caller{}
	svc.Ensure(c)
	c.Session.ZIP = "10456"
	if err := svc.Save(context.Background(), c); err != nil {
		t.Fatal(err)
	}
	good := svc.Cookie(c)
	got, err := svc.Resolve(context.Background(), ResolveInput{CookieValue: good})
	if err != nil || got.Session == nil || got.Session.ZIP != "10456" {
		t.Fatalf("resolve = %+v %v", got, err)
	}
	for _, bad := range []string{c.Session.ID, c.Session.ID + ".forged", "x.y", good + "x"} {
		got, _ := svc.Resolve(context.Background(), ResolveInput{CookieValue: bad})
		if got.Session != nil {
			t.Fatalf("tampered cookie %q accepted", bad)
		}
	}
}

func TestCartPresentation(t *testing.T) {
	store := memSelection{}
	svc := NewCartService(CartDeps{Selection: store, Estimator: est, Catalog: stubCatalog{}}).(*cartServiceImpl)
	c := &domain.Caller{Session: &domain.Session{ID: "s", GuestCartID: "g1"}}
	_ = store.SetSelected(context.Background(), "guest:g1", []string{"ATB0000001"}, false)
	up := port.UpstreamCart{Currency: "USD", Packages: []port.UpstreamCartPackage{
		{SellerOrgID: "org_retail", SellerName: "AfroTransact Retail", Fulfilment: "AFROTRANSACT", Lines: []port.UpstreamCartLine{
			{VariantATIN: "ATA0000001", ProductATIN: "ATA0000000", Title: "A", SellerKind: "PLATFORM", PriceMinor: 500, Quantity: 2, Available: 4},
			{VariantATIN: "ATB0000001", ProductATIN: "ATB0000000", Title: "B", SellerKind: "PLATFORM", PriceMinor: 300, Quantity: 1, Available: 10},
		}},
		{SellerOrgID: "org_k", SellerName: "Kente", Fulfilment: "SELLER", Lines: []port.UpstreamCartLine{
			{VariantATIN: "ATC0000001", ProductATIN: "ATC0000000", Title: "C", SellerKind: "SELLER", PriceMinor: 1000, Quantity: 5, Available: 2, Unavailable: true},
		}},
	}, Saved: []port.UpstreamCartLine{{VariantATIN: "ATD0000001", Title: "D", PriceMinor: 100, Available: 0}}}
	cart, err := svc.present(context.Background(), c, up)
	if err != nil {
		t.Fatal(err)
	}
	if cart.ItemCount != 8 || cart.SelectedCount != 2 || cart.SubtotalMinor != 1000 || cart.AllSelected || cart.SellerCount != 2 {
		t.Fatalf("totals = %+v", cart)
	}
	a := cart.Packages[0].Lines[0]
	if a.LineID != "ATA0000001" || a.ATIN != "ATA0000000" || a.MaxQuantity != 4 || a.StockStatus != domain.LowStock || !a.Selected || a.LineTotalMinor != 1000 {
		t.Fatalf("line A = %+v", a)
	}
	if cart.Packages[0].Lines[1].Selected {
		t.Fatal("B was deselected")
	}
	cl := cart.Packages[1].Lines[0]
	if cl.Selected || cl.MaxQuantity != 2 || cl.Quantity != 5 {
		t.Fatalf("unavailable line = %+v", cl)
	}
	if cart.Packages[1].FreeShippingNudge != nil {
		t.Fatal("no nudge when nothing in the package is selected")
	}
	if cart.Packages[0].PackageID != "org_retail:AFROTRANSACT" || cart.Packages[0].Heading != "Shipped by AfroTransact" || cart.Packages[0].Delivery.Label == "" {
		t.Fatalf("package = %+v", cart.Packages[0])
	}
	if cart.Saved[0].Available || cart.Saved[0].ATIN != "ATD0000001" {
		t.Fatalf("saved = %+v", cart.Saved)
	}
	if cart.CartID == "cart_new" || len(cart.CartID) != len("cart_")+16 {
		t.Fatalf("cart id = %s", cart.CartID)
	}
}

type memSelection map[string]map[string]bool

func (m memSelection) Deselected(_ context.Context, owner string) (map[string]bool, error) {
	return m[owner], nil
}

func (m memSelection) SetSelected(_ context.Context, owner string, atins []string, selected bool) error {
	if m[owner] == nil {
		m[owner] = map[string]bool{}
	}
	for _, a := range atins {
		if selected {
			delete(m[owner], a)
		} else {
			m[owner][a] = true
		}
	}
	return nil
}

type memSessions struct{ m map[string]domain.Session }

func (s *memSessions) Load(_ context.Context, key string) (*domain.Session, error) {
	v, ok := s.m[key]
	if !ok {
		return nil, nil
	}
	return &v, nil
}

func (s *memSessions) Save(_ context.Context, key string, v *domain.Session, _ time.Duration) error {
	s.m[key] = *v
	return nil
}

func (s *memSessions) Delete(_ context.Context, key string) error {
	delete(s.m, key)
	return nil
}

func (s *memSessions) Lock(context.Context, string, time.Duration) (func(), bool, error) {
	return func() {}, true, nil
}

type stubCatalog struct{}

func (stubCatalog) Product(context.Context, string) (port.CatalogProduct, error) {
	return port.CatalogProduct{}, port.ErrNotFound
}

func (stubCatalog) Variant(context.Context, string) (port.CatalogVariantView, error) {
	return port.CatalogVariantView{}, fmt.Errorf("catalog: %w", domain.ErrUpstreamUnavailable)
}

func (stubCatalog) Categories(context.Context) ([]port.Category, error) { return nil, nil }
