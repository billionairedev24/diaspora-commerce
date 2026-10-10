package app_test

import (
	"context"
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

type memRepo struct {
	carts     map[string]domain.Cart
	conflicts int
}

func (m *memRepo) Load(_ context.Context, o domain.Owner) (domain.Cart, error) {
	c, ok := m.carts[o.Key()]
	if !ok {
		return domain.Cart{Owner: o}, nil
	}
	c.Owner = o
	c.Lines = append([]domain.Line{}, c.Lines...)
	c.Saved = append([]domain.Line{}, c.Saved...)
	return c, nil
}

func (m *memRepo) Save(_ context.Context, c *domain.Cart) error {
	if m.conflicts > 0 {
		m.conflicts--
		return domain.ErrConflict
	}
	if m.carts[c.Owner.Key()].Version != c.Version {
		return domain.ErrConflict
	}
	c.Version++
	m.carts[c.Owner.Key()] = *c
	return nil
}

func (m *memRepo) Delete(_ context.Context, o domain.Owner) error {
	delete(m.carts, o.Key())
	return nil
}
func (m *memRepo) Ping(context.Context) error { return nil }

type offers map[string]domain.Offer

func (o offers) Offers(_ context.Context, atins []string) (map[string]domain.Offer, error) {
	out := map[string]domain.Offer{}
	for _, a := range atins {
		if v, ok := o[a]; ok {
			out[a] = v
		}
	}
	return out, nil
}

type stock map[string]int

func (s stock) Available(_ context.Context, atins []string) (map[string]int, error) {
	out := map[string]int{}
	for _, a := range atins {
		out[a] = s[a]
	}
	return out, nil
}

var (
	jollof = domain.Offer{VariantATIN: "ATJOL00001", Title: "Jollof Seasoning", SellerOrgID: "org_retail",
		SellerName: "AfroTransact Retail", Fulfilment: "AFROTRANSACT", PriceMinor: 899, Currency: "USD"}
	dress = domain.Offer{VariantATIN: "ATDRS00001", Title: "Ankara Dress", SellerOrgID: "org_lagos",
		SellerName: "Lagos Textile Hub", Fulfilment: "SELLER", PriceMinor: 6499, Currency: "USD"}
)

func svc(conflicts int) (app.CartService, *memRepo) {
	guests, users := &memRepo{carts: map[string]domain.Cart{}}, &memRepo{carts: map[string]domain.Cart{}, conflicts: conflicts}
	return app.NewCartService(guests, users, offers{"ATJOL00001": jollof, "ATDRS00001": dress},
		stock{"ATJOL00001": 3, "ATDRS00001": 10}), guests
}

func TestAddRetriesOnConcurrentChange(t *testing.T) {
	s, _ := svc(2)
	user := domain.Owner{Kind: domain.OwnerUser, ID: "u1"}
	v, err := s.Add(context.Background(), user, "ATJOL00001", 2)
	if err != nil {
		t.Fatal(err)
	}
	if v.ItemCount != 2 || len(v.Packages) != 1 {
		t.Fatalf("view %+v", v)
	}
}

func TestNonLiveProductCannotBeAdded(t *testing.T) {
	s, _ := svc(0)
	_, err := s.Add(context.Background(), domain.Owner{Kind: domain.OwnerGuest, ID: "g"}, "ATNOPE0001", 1)
	if !errors.Is(err, app.ErrNotSellable) {
		t.Fatalf("expected ErrNotSellable, got %v", err)
	}
}

func TestGuestMergesIntoCustomerOnSignIn(t *testing.T) {
	s, guests := svc(0)
	ctx := context.Background()
	guest := domain.Owner{Kind: domain.OwnerGuest, ID: "11111111-1111-1111-1111-111111111111"}
	user := domain.Owner{Kind: domain.OwnerUser, ID: "u1"}
	if _, err := s.Add(ctx, guest, "ATJOL00001", 2); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Add(ctx, user, "ATJOL00001", 2); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Add(ctx, guest, "ATDRS00001", 1); err != nil {
		t.Fatal(err)
	}
	v, err := s.MergeGuest(ctx, user, guest.ID)
	if err != nil {
		t.Fatal(err)
	}
	if v.ItemCount != 4 || len(v.Packages) != 2 {
		t.Fatalf("merged view: count=%d packages=%d", v.ItemCount, len(v.Packages))
	}
	if _, ok := guests.carts[guest.Key()]; ok {
		t.Fatal("guest cart should be deleted after merge")
	}
}

func TestOrderedLinesAreCleared(t *testing.T) {
	s, _ := svc(0)
	ctx := context.Background()
	user := domain.Owner{Kind: domain.OwnerUser, ID: "u1"}
	_, _ = s.Add(ctx, user, "ATJOL00001", 1)
	_, _ = s.Add(ctx, user, "ATDRS00001", 1)
	if err := s.RemoveOrdered(ctx, user, []string{"ATJOL00001", "ATDRS00001"}); err != nil {
		t.Fatal(err)
	}
	v, _ := s.View(ctx, user)
	if v.ItemCount != 0 {
		t.Fatalf("cart should be empty after checkout, got %d", v.ItemCount)
	}
}
