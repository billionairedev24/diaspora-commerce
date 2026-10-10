// Package httpapi is the inbound HTTP adapter for cart (contract: contracts/openapi/cart.yaml).
package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strings"

	"github.com/google/uuid"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/problem"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

// GuestHeader carries the guest cart id; the service issues one when it is missing.
const GuestHeader = "X-Guest-Cart"

var guestID = regexp.MustCompile(`^[0-9a-f-]{36}$`)

// Handlers exposes the cart over HTTP.
type Handlers interface {
	Register(mux *http.ServeMux, optionalAuth, requiredAuth func(http.Handler) http.Handler)
}

type handlersImpl struct{ carts app.CartService }

var _ Handlers = (*handlersImpl)(nil)

// New returns the HTTP handlers.
func New(carts app.CartService) Handlers { return &handlersImpl{carts: carts} }

func (h *handlersImpl) Register(mux *http.ServeMux, optionalAuth, requiredAuth func(http.Handler) http.Handler) {
	mux.Handle("GET /v1/cart", optionalAuth(located(h.view)))
	mux.Handle("POST /v1/cart/items", optionalAuth(located(h.add)))
	mux.Handle("PUT /v1/cart/items/{atin}", optionalAuth(located(h.setQty)))
	mux.Handle("DELETE /v1/cart/items/{atin}", optionalAuth(located(h.remove)))
	mux.Handle("POST /v1/cart/items/{atin}/save-for-later", optionalAuth(located(h.save)))
	mux.Handle("POST /v1/cart/saved/{atin}/move-to-cart", optionalAuth(located(h.move)))
	mux.Handle("PUT /v1/cart/preorder-fulfilment", optionalAuth(located(h.preorderFulfilment)))
	mux.Handle("POST /v1/cart/merge", requiredAuth(located(h.merge)))
	mux.Handle("POST /v1/carts/{customerId}/ordered", requiredAuth(auth.Require("cart:service", h.ordered)))
}

// owner resolves the signed-in customer or the guest cart (issuing a new guest id when needed).
func owner(w http.ResponseWriter, r *http.Request) (domain.Owner, bool) {
	if a, ok := auth.FromContext(r.Context()); ok && a.Subject != "" && a.Type == "CUSTOMER" {
		return domain.Owner{Kind: domain.OwnerUser, ID: a.Subject}, true
	}
	id := r.Header.Get(GuestHeader)
	if id == "" {
		id = uuid.NewString()
	} else if !guestID.MatchString(id) {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "cart.invalid-guest-id", "Malformed guest cart id"))
		return domain.Owner{}, false
	}
	w.Header().Set(GuestHeader, id)
	return domain.Owner{Kind: domain.OwnerGuest, ID: id}, true
}

// Delivery location headers (or ?country=&region=&city=) the BFF sends from the shopper's delivery address; cart
// views then group pre-order lines into window packages (S272).
const (
	CountryHeader = "X-Delivery-Country"
	RegionHeader  = "X-Delivery-Region"
	CityHeader    = "X-Delivery-City"
)

// located puts the request's delivery location on the context.
func located(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		pick := func(param, header string) string {
			if v := strings.TrimSpace(q.Get(param)); v != "" {
				return v
			}
			return strings.TrimSpace(r.Header.Get(header))
		}
		loc := domain.Location{Country: strings.ToUpper(pick("country", CountryHeader)),
			Region: pick("region", RegionHeader), City: pick("city", CityHeader)}
		if loc.Known() {
			r = r.WithContext(app.WithLocation(r.Context(), loc))
		}
		next(w, r)
	})
}

type preorderBody struct {
	WindowID   string `json:"windowId"`
	Fulfilment string `json:"fulfilment"`
}

func (h *handlersImpl) preorderFulfilment(w http.ResponseWriter, r *http.Request) {
	o, ok := owner(w, r)
	if !ok {
		return
	}
	var b preorderBody
	if !decode(w, r, &b) {
		return
	}
	v, err := h.carts.SetPreorderFulfilment(r.Context(), o, b.WindowID, b.Fulfilment)
	respond(w, r, v, err)
}

func (h *handlersImpl) view(w http.ResponseWriter, r *http.Request) {
	o, ok := owner(w, r)
	if !ok {
		return
	}
	v, err := h.carts.View(r.Context(), o)
	respond(w, r, v, err)
}

type itemBody struct {
	VariantATIN string `json:"variantAtin"`
	OfferID     string `json:"offerId"`
	Quantity    int    `json:"quantity"`
}

func (h *handlersImpl) add(w http.ResponseWriter, r *http.Request) {
	o, ok := owner(w, r)
	if !ok {
		return
	}
	var b itemBody
	if !decode(w, r, &b) {
		return
	}
	if b.Quantity == 0 {
		b.Quantity = 1
	}
	if b.VariantATIN == "" && b.OfferID == "" {
		problem.Write(w, r, &problem.Error{Status: http.StatusUnprocessableEntity, Code: "cart.invalid",
			Detail: "variantAtin or offerId is required", Fields: map[string]string{"variantAtin": "Required without offerId"}})
		return
	}
	v, err := h.carts.Add(r.Context(), o, b.VariantATIN, b.OfferID, b.Quantity)
	respond(w, r, v, err)
}

func (h *handlersImpl) setQty(w http.ResponseWriter, r *http.Request) {
	o, ok := owner(w, r)
	if !ok {
		return
	}
	var b itemBody
	if !decode(w, r, &b) {
		return
	}
	v, err := h.carts.SetQuantity(r.Context(), o, r.PathValue("atin"), b.Quantity)
	respond(w, r, v, err)
}

func (h *handlersImpl) remove(w http.ResponseWriter, r *http.Request) {
	o, ok := owner(w, r)
	if !ok {
		return
	}
	v, err := h.carts.Remove(r.Context(), o, r.PathValue("atin"))
	respond(w, r, v, err)
}

func (h *handlersImpl) save(w http.ResponseWriter, r *http.Request) {
	o, ok := owner(w, r)
	if !ok {
		return
	}
	v, err := h.carts.SaveForLater(r.Context(), o, r.PathValue("atin"))
	respond(w, r, v, err)
}

func (h *handlersImpl) move(w http.ResponseWriter, r *http.Request) {
	o, ok := owner(w, r)
	if !ok {
		return
	}
	v, err := h.carts.MoveToCart(r.Context(), o, r.PathValue("atin"))
	respond(w, r, v, err)
}

func (h *handlersImpl) merge(w http.ResponseWriter, r *http.Request) {
	a, _ := auth.FromContext(r.Context())
	g := r.Header.Get(GuestHeader)
	if a.Type != "CUSTOMER" || !guestID.MatchString(g) {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "cart.merge-invalid", "A signed-in customer and a guest cart id are required"))
		return
	}
	v, err := h.carts.MergeGuest(r.Context(), domain.Owner{Kind: domain.OwnerUser, ID: a.Subject}, g)
	respond(w, r, v, err)
}

func (h *handlersImpl) ordered(w http.ResponseWriter, r *http.Request) {
	var b struct {
		VariantATINs []string `json:"variantAtins"`
	}
	if !decode(w, r, &b) {
		return
	}
	err := h.carts.RemoveOrdered(r.Context(), domain.Owner{Kind: domain.OwnerUser, ID: r.PathValue("customerId")}, b.VariantATINs)
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func decode(w http.ResponseWriter, r *http.Request, dst any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16<<10)).Decode(dst); err != nil {
		problem.Write(w, r, problem.New(http.StatusBadRequest, "cart.invalid-json", "Malformed request body"))
		return false
	}
	return true
}

func respond(w http.ResponseWriter, r *http.Request, v domain.View, err error) {
	if err != nil {
		writeErr(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, domain.ErrQuantityTooHigh), errors.Is(err, domain.ErrOutOfStock):
		problem.Write(w, r, &problem.Error{Status: http.StatusConflict, Code: "cart.insufficient-stock", Detail: err.Error()})
	case errors.Is(err, app.ErrNotSellable):
		problem.Write(w, r, &problem.Error{Status: http.StatusConflict, Code: "cart.not-sellable", Detail: err.Error()})
	case errors.Is(err, app.ErrNotBuyable):
		problem.Write(w, r, &problem.Error{Status: http.StatusConflict, Code: "cart.offer-not-buyable", Detail: err.Error()})
	case errors.Is(err, app.ErrOfferMismatch):
		problem.Write(w, r, &problem.Error{Status: http.StatusUnprocessableEntity, Code: "cart.invalid", Detail: err.Error(),
			Fields: map[string]string{"offerId": "Not an offer of this variant"}})
	case errors.Is(err, domain.ErrInvalidFulfilment):
		problem.Write(w, r, &problem.Error{Status: http.StatusUnprocessableEntity, Code: "cart.invalid", Detail: err.Error(),
			Fields: map[string]string{"fulfilment": "DOOR or PICKUP", "windowId": "Required"}})
	case errors.Is(err, domain.ErrInvalidQuantity), errors.Is(err, domain.ErrCartFull):
		problem.Write(w, r, &problem.Error{Status: http.StatusUnprocessableEntity, Code: "cart.invalid", Detail: err.Error()})
	case errors.Is(err, domain.ErrUnknownLine):
		problem.Write(w, r, problem.New(http.StatusNotFound, "cart.line-not-found", err.Error()))
	case errors.Is(err, domain.ErrConflict):
		problem.Write(w, r, problem.New(http.StatusConflict, "cart.concurrent-update", "The cart changed; please retry"))
	default:
		problem.Write(w, r, problem.New(http.StatusBadGateway, "cart.unavailable", "Cart is temporarily unavailable"))
	}
}
