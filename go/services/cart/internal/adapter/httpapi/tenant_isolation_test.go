package httpapi_test

import (
	"context"
	"net/http"
	"strconv"
	"testing"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

// ownerCarts stands in for the cart store: one counter per owner key, so the test sees which cart a request touched.
type ownerCarts struct {
	app.CartService
	items   map[string]int
	ordered []string
}

func (c *ownerCarts) view(o domain.Owner) domain.View {
	return domain.View{ItemCount: c.items[o.Key()], Currency: "USD", Version: int64(len(o.Key()))}
}

func (c *ownerCarts) View(_ context.Context, o domain.Owner) (domain.View, error) {
	return c.view(o), nil
}

func (c *ownerCarts) Add(_ context.Context, o domain.Owner, _, _ string, q int) (domain.View, error) {
	c.items[o.Key()] += q
	return c.view(o), nil
}

func (c *ownerCarts) MergeGuest(_ context.Context, user domain.Owner, guestID string) (domain.View, error) {
	g := domain.Owner{Kind: domain.OwnerGuest, ID: guestID}
	c.items[user.Key()] += c.items[g.Key()]
	delete(c.items, g.Key())
	return c.view(user), nil
}

func (c *ownerCarts) RemoveOrdered(_ context.Context, o domain.Owner, _ []string) error {
	c.ordered = append(c.ordered, o.Key())
	return nil
}

func cartServer(t *testing.T, w tenanttest.World) (http.Handler, *ownerCarts) {
	t.Helper()
	carts := &ownerCarts{items: map[string]int{}}
	mux := http.NewServeMux()
	httpapi.New(carts).Register(mux, w.Auth(true), w.Auth(false))
	return mux, carts
}

func count(t *testing.T, res tenanttest.Result) int {
	t.Helper()
	i := indexOf(res.Body, `"itemCount":`)
	if i < 0 {
		t.Fatalf("no itemCount in %s", res.Body)
	}
	j := i + len(`"itemCount":`)
	k := j
	for k < len(res.Body) && res.Body[k] >= '0' && res.Body[k] <= '9' {
		k++
	}
	n, err := strconv.Atoi(res.Body[j:k])
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func TestCrossTenantCartsAreKeyedByTheVerifiedSubject(t *testing.T) {
	w := tenanttest.NewWorld(tenanttest.ServicePerms("cart:service"))
	h, carts := cartServer(t, w)
	add := tenanttest.Send(http.MethodPost, "/v1/cart/items", `{"variantAtin":"AT1","quantity":2}`)

	tenanttest.Do(h, w.CustomerB, add)
	// A guest cart of someone else, whose id leaked to A: a signed-in shopper never reads or writes a guest cart through the header.
	victimGuest := uuid.NewString()
	guestRes := tenanttest.Do(h, w.Anonymous, add.WithHeader(httpapi.GuestHeader, victimGuest).WithHeader("X-Delivery-Country", "US"))
	if guestRes.Status != http.StatusOK {
		t.Fatalf("guest add: %d %s", guestRes.Status, guestRes.Body)
	}

	view := tenanttest.Get("/v1/cart")
	if n := count(t, tenanttest.Do(h, w.CustomerA, view)); n != 0 {
		t.Fatalf("customer A sees %d items of someone else's cart", n)
	}
	if n := count(t, tenanttest.Do(h, w.CustomerA, view.WithHeader(httpapi.GuestHeader, victimGuest))); n != 0 {
		t.Fatalf("a signed-in shopper reached a guest cart through X-Guest-Cart: %d", n)
	}
	if n := count(t, tenanttest.Do(h, w.CustomerB, view)); n != 2 {
		t.Fatalf("customer B lost its own cart: %d", n)
	}
	// Seller and staff tokens act as guests on the shopper cart (no X-Acting-As), never as the shopper whose subject they share.
	for _, p := range []tenanttest.Principal{w.SellerA, w.Staff} {
		res := tenanttest.Do(h, p, view.WithHeader(httpapi.GuestHeader, victimGuest))
		if res.Header.Get(httpapi.GuestHeader) != victimGuest {
			t.Fatalf("%s: org members and staff use a guest cart without X-Acting-As", p)
		}
	}
	// A request cannot choose another user's cart by any parameter.
	for _, q := range []string{"?customerId=" + w.CustomerB.Actor.Subject, "?userId=" + w.CustomerB.Actor.Subject} {
		if n := count(t, tenanttest.Do(h, w.CustomerA, tenanttest.Get("/v1/cart"+q))); n != 0 {
			t.Fatalf("query %s picked another cart: %d", q, n)
		}
	}

	// Malformed and low-entropy guest ids are refused: an id an attacker can pick is an id they can plant.
	for _, bad := range []string{"../../etc", "user:" + w.CustomerB.Actor.Subject, "00000000-0000-0000-0000-000000000000",
		"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "------------------------------------"} {
		res := tenanttest.Do(h, w.Anonymous, view.WithHeader(httpapi.GuestHeader, bad))
		if res.Status != http.StatusBadRequest {
			t.Fatalf("guest id %q accepted: %d", bad, res.Status)
		}
	}

	// Merge needs a signed-in shopper, moves only the named guest cart and leaves other users alone.
	merge := tenanttest.Send(http.MethodPost, "/v1/cart/merge", "").WithHeader(httpapi.GuestHeader, victimGuest)
	tenanttest.Run(t, h, []tenanttest.Case{
		{Name: "anonymous cannot merge", As: w.Anonymous, Req: merge, Expect: tenanttest.Status(http.StatusUnauthorized)},
		{Name: "seller cannot merge into a shopper cart", As: w.SellerA, Req: merge, Expect: tenanttest.Status(http.StatusBadRequest)},
	})
	tenanttest.Do(h, w.CustomerA, merge)
	if carts.items["user:"+w.CustomerB.Actor.Subject] != 2 {
		t.Fatal("merge touched another user's cart")
	}

	// The "ordered" hook clears a customer's lines: services only, and never a shopper for someone else.
	ordered := tenanttest.Send(http.MethodPost, "/v1/carts/"+w.CustomerB.Actor.Subject+"/ordered", `{"variantAtins":["AT1"]}`)
	tenanttest.CrossTenant(t, h, []tenanttest.Principal{w.CustomerA, w.CustomerB, w.SellerA, w.Anonymous}, []tenanttest.Request{ordered})
	if len(carts.ordered) != 0 {
		t.Fatalf("ordered cleared carts for a non-service caller: %v", carts.ordered)
	}
	tenanttest.Run(t, h, []tenanttest.Case{{Name: "service clears the cart", As: w.Service, Req: ordered, Expect: tenanttest.Status(http.StatusNoContent)}})
}

func TestRouteMatrix(t *testing.T) {
	m, err := cart.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "."), string(m), ".")
}
