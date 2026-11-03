package httpapi_test

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

// recordingCarts records whose cart a request reads.
type recordingCarts struct {
	app.CartService
	owner domain.Owner
}

func (c *recordingCarts) View(_ context.Context, owner domain.Owner) (domain.View, error) {
	c.owner = owner
	return domain.View{}, nil
}

// sellerOwnerToken is identity's token for a customer whose seller application was approved: pt SELLER_MEMBER with
// the organisation (unsigned: the local profile reads the claims without a verifier).
func sellerOwnerToken() string {
	enc := base64.RawURLEncoding
	return enc.EncodeToString([]byte(`{"alg":"RS256"}`)) + "." +
		enc.EncodeToString([]byte(`{"sub":"user-1","pt":"SELLER_MEMBER","org":"org_malo","roles":["account.customer","seller.owner"]}`)) + ".c2ln"
}

func TestAnApprovedSellerOnTheStorefrontReadsTheirOwnCart(t *testing.T) {
	carts := &recordingCarts{}
	mux := http.NewServeMux()
	httpapi.New(carts).Register(mux, auth.Middleware(nil, true), auth.Middleware(nil, false))
	view := func(actingAs string) domain.Owner {
		req := httptest.NewRequestWithContext(context.Background(), http.MethodGet, "/v1/cart", nil)
		req.Header.Set("Authorization", "Bearer "+sellerOwnerToken())
		if actingAs != "" {
			req.Header.Set(auth.ActingAsHeader, actingAs)
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("GET /v1/cart = %d: %s", rec.Code, rec.Body)
		}
		return carts.owner
	}

	if o := view(auth.ActingAsCustomer); o.Kind != domain.OwnerUser || o.ID != "user-1" {
		t.Fatalf("storefront (X-Acting-As: CUSTOMER): owner = %+v, want the person's own cart", o)
	}
	// Without the storefront's acting context an organisation member has no shopper cart (a fresh guest cart).
	if o := view(""); o.Kind != domain.OwnerGuest {
		t.Fatalf("no acting context: owner = %+v, want a guest cart", o)
	}
}
