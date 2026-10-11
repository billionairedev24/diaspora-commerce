package testkit

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

// Variant is a fake sellable variant.
type Variant struct {
	ATIN       string
	Options    map[string]string
	PriceMinor int64
	Stock      int
}

// Product is a fake live catalog product.
type Product struct {
	ATIN        string
	Title       string
	OwnerKind   string // PLATFORM | SELLER
	SellerOrgID string
	SellerName  string
	CategoryID  string
	Attributes  map[string]string
	Variants    []Variant
}

// Fixture products: low stock (platform), a seller product with an in-stock and a sold-out variant, and a sold-out
// platform product.
func defaultProducts() []Product {
	return []Product{
		{ATIN: "ATJOLLOF01", Title: "Jollof Seasoning", OwnerKind: "PLATFORM", SellerOrgID: "org_afrotransact_retail",
			SellerName: "AfroTransact Retail", CategoryID: "cat-spices", Attributes: map[string]string{"brand": "Mama's", "origin": "Nigeria",
				"bullets": "Smoky tomato blend\nNo MSG", "community": "Nigerian"},
			Variants: []Variant{{ATIN: "ATJOLLOFV1", Options: map[string]string{"Size": "6 oz"}, PriceMinor: 599, Stock: 3}}},
		{ATIN: "ATSHEA0001", Title: "Raw Shea Butter", OwnerKind: "SELLER", SellerOrgID: "org_kente", SellerName: "Kente Naturals",
			CategoryID: "cat-skin", Attributes: map[string]string{"brand": "Kente", "origin": "Ghana"},
			Variants: []Variant{{ATIN: "ATSHEAV001", Options: map[string]string{"Size": "8 oz"}, PriceMinor: 1250, Stock: 40},
				{ATIN: "ATSHEAV002", Options: map[string]string{"Size": "16 oz"}, PriceMinor: 2000, Stock: 0}}},
		{ATIN: "ATGARI0001", Title: "Ijebu Garri", OwnerKind: "PLATFORM", SellerOrgID: "org_afrotransact_retail",
			SellerName: "AfroTransact Retail", CategoryID: "cat-grains", Attributes: map[string]string{"unit": "2 lb bag"},
			Variants: []Variant{{ATIN: "ATGARIV001", PriceMinor: 899, Stock: 0}}},
	}
}

var categories = []map[string]string{
	{"id": "cat-spices", "name": "Spices", "path": "Groceries › Spices"},
	{"id": "cat-skin", "name": "Skin care", "path": "Beauty › Skin care"},
	{"id": "cat-grains", "name": "Grains", "path": "Groceries › Grains"},
}

type cartLine struct {
	atin  string
	qty   int
	offer string
}

type fakeCart struct {
	lines []cartLine
	saved []cartLine
	// preorder is the pre-order fulfilment chosen with PUT /v1/cart/preorder-fulfilment (DOOR | PICKUP); once set,
	// the first package is presented as the window's pre-order package.
	preorder string
}

// Options choose which pending upstreams the fake serves (others answer 404 for every route, like a service not
// built yet).
type Options struct {
	Content  bool
	Payments bool
	Promise  bool
	// Offers serves the offers service's public reads (buy boxes, other sellers, offer lookup).
	Offers bool
	// Notifications serves back-in-stock alerts.
	Notifications bool
	// SellerPending serves the seller-side routes of services still being built (orders, bookings) so their
	// mapping can be tested; without it they answer 404 like a service that does not exist yet.
	SellerPending bool
}

// Upstreams is one httptest server playing every upstream the BFF calls.
type Upstreams struct {
	Server   *httptest.Server
	ClientID string
	Secret   string

	mu        sync.Mutex
	products  []Product
	carts     map[string]*fakeCart
	codes     map[string]issuedCode
	refresh   map[string]string // refresh token → subject
	revoked   []string
	addresses map[string][]map[string]any
	key       *rsa.PrivateKey
	// AccessTTL is the lifetime of issued access tokens.
	AccessTTL time.Duration
	// Calls counts requests per "METHOD path" (tests assert retries and caching).
	Calls map[string]int
	// FailInventory makes inventory answer 503.
	FailInventory bool
	opts          Options
	// MemberOrgs are the organisations of every signed-in member; ActiveOrgID is identity's active org.
	MemberOrgs  []MemberOrg
	ActiveOrgID string
	// ServiceTokenRequests records the scopes of client-credentials token requests.
	ServiceTokenRequests []string
	// LastClientIP is the clientIp identity's first-party API last received.
	LastClientIP string
	tickets      map[string]string
	// AccessDeniedMatrix makes identity's module matrix answer 403 (like for org members without access:read).
	AccessDeniedMatrix bool
	seller             *sellerState
	// PhoneVerified is identity's phoneVerified for every account; LastSignUp is the last POST /v1/accounts body.
	PhoneVerified bool
	LastSignUp    map[string]any
	team          *teamState
	// LastWaitlist is the last delivery-runs POST /v1/public/subscriptions body (pre-order waitlist).
	LastWaitlist map[string]any
}

type issuedCode struct {
	challenge, nonce, subject, redirectURI string
}

// NewUpstreams starts the fake upstream server.
func NewUpstreams(o Options) *Upstreams {
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		panic(err)
	}
	u := &Upstreams{ClientID: "gateway-bff", Secret: "test-secret", products: defaultProducts(), carts: map[string]*fakeCart{},
		codes: map[string]issuedCode{}, refresh: map[string]string{}, addresses: map[string][]map[string]any{}, key: key,
		AccessTTL: 5 * time.Minute, Calls: map[string]int{}, opts: o, MemberOrgs: DefaultMemberOrgs(), ActiveOrgID: SellerOrgID,
		AccessDeniedMatrix: true, seller: newSellerState(), tickets: map[string]string{},
		team: &teamState{removed: map[string]bool{}}}
	mux := http.NewServeMux()
	u.routes(mux)
	u.preorderRoutes(mux)
	u.sellerRoutes(mux)
	u.firstPartyRoutes(mux)
	u.shopperRoutes(mux)
	u.Server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		u.Calls[r.Method+" "+r.URL.Path]++
		u.mu.Unlock()
		mux.ServeHTTP(w, r)
	}))
	return u
}

// Close stops the server.
func (u *Upstreams) Close() { u.Server.Close() }

// URL is the base URL of every fake upstream.
func (u *Upstreams) URL() string { return u.Server.URL }

// CallCount returns how often "METHOD path" was requested.
func (u *Upstreams) CallCount(key string) int {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.Calls[key]
}

// Revoked lists revoked tokens.
func (u *Upstreams) Revoked() []string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return slices.Clone(u.revoked)
}

// ForgetRefreshTokens invalidates every refresh token (as if rotated on another device).
func (u *Upstreams) ForgetRefreshTokens() {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.refresh = map[string]string{}
}

// SetStock changes a variant's stock.
func (u *Upstreams) SetStock(atin string, stock int) {
	u.mu.Lock()
	defer u.mu.Unlock()
	for i := range u.products {
		for j := range u.products[i].Variants {
			if u.products[i].Variants[j].ATIN == atin {
				u.products[i].Variants[j].Stock = stock
			}
		}
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func problem(w http.ResponseWriter, status int, code, detail string, extra map[string]any) {
	doc := map[string]any{"type": "https://developers.afrotransact.com/problems/" + code, "title": http.StatusText(status), "status": status, "code": code, "detail": detail}
	for k, v := range extra {
		doc[k] = v
	}
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(doc)
}

func (u *Upstreams) variant(atin string) (Product, Variant, bool) {
	for _, p := range u.products {
		for _, v := range p.Variants {
			if v.ATIN == atin {
				return p, v, true
			}
		}
	}
	return Product{}, Variant{}, false
}

func (u *Upstreams) product(atin string) (Product, bool) {
	for _, p := range u.products {
		if p.ATIN == atin {
			return p, true
		}
	}
	return Product{}, false
}

func publicProduct(p Product) map[string]any {
	vs := []map[string]any{}
	for _, v := range p.Variants {
		vs = append(vs, map[string]any{"atin": v.ATIN, "options": v.Options, "priceMinor": v.PriceMinor, "currency": "usd"})
	}
	return map[string]any{"atin": p.ATIN, "ownerKind": p.OwnerKind, "sellerOrgId": p.SellerOrgID, "categoryId": p.CategoryID,
		"title": p.Title, "attributes": p.Attributes, "images": []map[string]any{{"url": "https://img.example/" + p.ATIN + ".jpg"}}, "variants": vs}
}

func (u *Upstreams) routes(mux *http.ServeMux) {
	// ---- catalog
	mux.HandleFunc("GET /v1/public/products/{atin}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		p, ok := u.product(r.PathValue("atin"))
		u.mu.Unlock()
		if !ok {
			problem(w, 404, "catalog.product-not-found", "No live product", nil)
			return
		}
		w.Header().Set("Cache-Control", "max-age=60, public")
		writeJSON(w, 200, publicProduct(p))
	})
	mux.HandleFunc("GET /v1/public/variants/{atin}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		p, v, ok := u.variant(r.PathValue("atin"))
		u.mu.Unlock()
		if !ok {
			problem(w, 404, "catalog.product-not-found", "No live variant", nil)
			return
		}
		w.Header().Set("Cache-Control", "max-age=60, public")
		writeJSON(w, 200, map[string]any{"product": publicProduct(p), "variant": map[string]any{"atin": v.ATIN, "options": v.Options, "priceMinor": v.PriceMinor, "currency": "usd"}})
	})
	mux.HandleFunc("GET /v1/categories", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, categories) })
	// ---- inventory
	mux.HandleFunc("GET /v1/availability", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		if u.FailInventory {
			w.WriteHeader(503)
			return
		}
		rows := []map[string]any{}
		for _, s := range strings.Split(r.URL.Query().Get("skus"), ",") {
			if _, v, ok := u.variant(s); ok {
				rows = append(rows, map[string]any{"sku": s, "available": v.Stock, "inStock": v.Stock > 0})
			}
		}
		writeJSON(w, 200, rows)
	})
	// ---- search
	mux.HandleFunc("GET /v1/search", u.search)
	mux.HandleFunc("GET /v1/suggest", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		q := strings.ToLower(r.URL.Query().Get("q"))
		var hits []map[string]any
		for _, p := range u.products {
			if strings.Contains(strings.ToLower(p.Title), q) {
				hits = append(hits, u.hit(p))
			}
		}
		writeJSON(w, 200, map[string]any{"phrases": []map[string]any{{"text": q + " seasoning", "department": "Groceries"}, {"text": q}}, "products": hits})
	})
	// ---- cart
	u.cartRoutes(mux)
	// ---- geo
	mux.HandleFunc("GET /v1/zip/{zip}", func(w http.ResponseWriter, r *http.Request) {
		switch r.PathValue("zip") {
		case "77042":
			writeJSON(w, 200, map[string]any{"city": "Houston", "state": "TX", "postalCode": "77042", "country": "US"})
		case "10456":
			writeJSON(w, 200, map[string]any{"city": "Bronx", "state": "NY", "postalCode": "10456", "country": "US"})
		case "79401":
			writeJSON(w, 200, map[string]any{"city": "Lubbock", "state": "TX", "postalCode": "79401", "country": "US"})
		default:
			problem(w, 404, "geo.not-found", "No matching address", nil)
		}
	})
	mux.HandleFunc("GET /v1/reverse", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("lng") == "" {
			problem(w, 400, "geo.invalid-point", "lat and lng are required numbers", nil)
			return
		}
		if strings.HasPrefix(r.URL.Query().Get("lat"), "0.") {
			problem(w, 404, "geo.not-found", "No matching address", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "postalCode": "77042", "country": "US", "lat": 29.7376, "lon": -95.5134})
	})
	mux.HandleFunc("GET /v1/addresses/autocomplete", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"suggestions": []map[string]any{
			{"id": "o:1", "text": "9800 Westheimer Rd, Houston, TX", "mainText": "9800 Westheimer Rd", "secondaryText": "Houston, TX 77042", "provider": "osm-nominatim"}}})
	})
	mux.HandleFunc("GET /v1/addresses/resolve/{id}", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "postalCode": "77042", "country": "US", "lat": 29.73, "lon": -95.55})
	})
	mux.HandleFunc("POST /v1/addresses/validate", func(w http.ResponseWriter, r *http.Request) {
		var a map[string]any
		_ = json.NewDecoder(r.Body).Decode(&a)
		if l, _ := a["line1"].(string); strings.Contains(l, "Nowhere") {
			writeJSON(w, 200, map[string]any{"status": "UNDELIVERABLE", "suggestions": []any{}, "messages": []string{"USPS cannot deliver to this address."}})
			return
		}
		writeJSON(w, 200, map[string]any{"status": "VALID", "standardized": a, "suggestions": []any{}, "messages": []string{}})
	})
	// ---- identity
	u.identityRoutes(mux)
	// ---- pending services, when enabled
	if u.opts.Content {
		u.contentRoutes(mux)
	}
	if u.opts.Payments {
		mux.HandleFunc("GET /v1/customers/me/payment-methods", func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, 200, map[string]any{"items": []map[string]any{{"id": "pm_1", "type": "card", "brand": "visa", "last4": "4242", "label": "Visa ending in 4242", "expires": "08/29", "isDefault": true}}})
		})
		mux.HandleFunc("POST /v1/customers/me/setup-intents", func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, 201, map[string]any{"clientSecret": "seti_123_secret_abc", "publishableKey": "pk_test_123"})
		})
	}
	if u.opts.Offers {
		u.offersRoutes(mux)
	}
	if u.opts.Notifications {
		u.notificationsRoutes(mux)
	}
	if u.opts.Promise {
		mux.HandleFunc("POST /v1/promises", func(w http.ResponseWriter, r *http.Request) {
			var b struct {
				Items []struct {
					ATIN string `json:"atin"`
				} `json:"items"`
			}
			_ = json.NewDecoder(r.Body).Decode(&b)
			out := []map[string]any{}
			for _, it := range b.Items {
				out = append(out, map[string]any{"atin": it.ATIN, "days": 1})
			}
			writeJSON(w, 200, map[string]any{"promises": out})
		})
	}
}

func (u *Upstreams) hit(p Product) map[string]any {
	v := p.Variants[0]
	stock := 0
	for _, x := range p.Variants {
		stock += x.Stock
	}
	ff := "SELLER"
	if p.OwnerKind == "PLATFORM" {
		ff = "AFROTRANSACT"
	}
	path := ""
	for _, c := range categories {
		if c["id"] == p.CategoryID {
			path = c["path"]
		}
	}
	return map[string]any{"atin": p.ATIN, "title": p.Title, "brand": p.Attributes["brand"], "categoryPath": path,
		"sellerName": p.SellerName, "sellerKind": p.OwnerKind, "fulfilment": ff, "priceMinor": v.PriceMinor, "currency": "USD",
		"rating": 4.6, "reviewCount": 120, "inStock": stock > 0, "lowStock": stock > 0 && stock <= 5, "availableQty": stock,
		"deliveryDays": 1, "imageUrl": "https://img.example/" + p.ATIN + ".jpg", "offerCount": 1, "score": 1.0}
}

func (u *Upstreams) search(w http.ResponseWriter, r *http.Request) {
	u.mu.Lock()
	defer u.mu.Unlock()
	q := strings.ToLower(r.URL.Query().Get("q"))
	if r.URL.Query().Get("sort") == "bogus" {
		problem(w, 400, "search.invalid-query", "unknown sort bogus", nil)
		return
	}
	hits := []map[string]any{}
	for _, p := range u.products {
		if q == "" || strings.Contains(strings.ToLower(p.Title), q) {
			if ff := r.URL.Query().Get("fulfilment"); ff == "AFROTRANSACT" && p.OwnerKind != "PLATFORM" {
				continue
			}
			hits = append(hits, u.hit(p))
		}
	}
	size, _ := strconv.Atoi(r.URL.Query().Get("size"))
	if size == 0 {
		size = 24
	}
	writeJSON(w, 200, map[string]any{"searchId": u.searchSeen(r), "hits": u.withAds(r, hits), "total": len(hits), "page": 0, "size": size, "tookMs": 3, "semantic": false,
		"facets": map[string]any{
			"department":  []map[string]any{{"value": "Groceries", "count": 2}, {"value": "Beauty", "count": 1}},
			"communities": []map[string]any{{"value": "Nigerian", "count": 1}},
			"fulfilment":  []map[string]any{{"value": "AFROTRANSACT", "count": 2}, {"value": "SELLER", "count": 1}},
			"seller_kind": []map[string]any{{"value": "PLATFORM", "count": 2}, {"value": "SELLER", "count": 1}},
			"rating":      []map[string]any{{"value": "4+", "count": 3}, {"value": "3+", "count": 3}},
			"price":       []map[string]any{{"value": "under-10", "count": 2}, {"value": "10-25", "count": 1}, {"value": "25-50", "count": 0}, {"value": "50-plus", "count": 0}},
			"delivery":    []map[string]any{{"value": "tomorrow", "count": 3}, {"value": "2-days", "count": 3}},
		}})
}

// ---------------------------------------------------------------- cart (same rules as go/services/cart)

func subjectOf(r *http.Request) string {
	t, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok {
		return ""
	}
	claims := jwt.MapClaims{}
	if _, _, err := jwt.NewParser().ParseUnverified(t, claims); err != nil {
		return ""
	}
	s, _ := claims["sub"].(string)
	return s
}

func (u *Upstreams) owner(w http.ResponseWriter, r *http.Request) string {
	if s := subjectOf(r); s != "" {
		return "user:" + s
	}
	g := r.Header.Get("X-Guest-Cart")
	if g == "" {
		g = newUUID()
	}
	w.Header().Set("X-Guest-Cart", g)
	return "guest:" + g
}

func newUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func (u *Upstreams) cartOf(key string) *fakeCart {
	c := u.carts[key]
	if c == nil {
		c = &fakeCart{}
		u.carts[key] = c
	}
	return c
}

func idx(lines []cartLine, atin string) int {
	return slices.IndexFunc(lines, func(l cartLine) bool { return l.atin == atin })
}

func (u *Upstreams) view(c *fakeCart) map[string]any {
	type pkg struct {
		SellerOrgID string           `json:"sellerOrgId"`
		SellerName  string           `json:"sellerName"`
		Fulfilment  string           `json:"fulfilment"`
		Lines       []map[string]any `json:"lines"`
		Subtotal    int64            `json:"subtotalMinor"`
	}
	line := func(l cartLine) map[string]any {
		p, v, _ := u.variant(l.atin)
		ff := "SELLER"
		if p.OwnerKind == "PLATFORM" {
			ff = "AFROTRANSACT"
		}
		return map[string]any{"offerId": l.offer, "variantAtin": v.ATIN, "productAtin": p.ATIN, "title": p.Title, "options": v.Options,
			"imageUrl": "https://img.example/" + p.ATIN + ".jpg", "sellerOrgId": p.SellerOrgID, "sellerName": p.SellerName,
			"sellerKind": p.OwnerKind, "fulfilment": ff, "priceMinor": v.PriceMinor, "currency": "USD", "quantity": l.qty,
			"available": v.Stock, "lowStock": v.Stock > 0 && v.Stock <= 5, "unavailable": v.Stock <= 0 || l.qty > v.Stock,
			"lineTotalMinor": v.PriceMinor * int64(l.qty)}
	}
	var pkgs []*pkg
	byKey := map[string]*pkg{}
	items, subtotal := 0, int64(0)
	for _, l := range c.lines {
		lv := line(l)
		key := lv["sellerOrgId"].(string) + "|" + lv["fulfilment"].(string)
		pk := byKey[key]
		if pk == nil {
			pk = &pkg{SellerOrgID: lv["sellerOrgId"].(string), SellerName: lv["sellerName"].(string), Fulfilment: lv["fulfilment"].(string)}
			byKey[key] = pk
			pkgs = append(pkgs, pk)
		}
		pk.Lines = append(pk.Lines, lv)
		pk.Subtotal += lv["lineTotalMinor"].(int64)
		items += l.qty
		subtotal += lv["lineTotalMinor"].(int64)
	}
	saved := []map[string]any{}
	for _, l := range c.saved {
		saved = append(saved, line(l))
	}
	if pkgs == nil {
		pkgs = []*pkg{}
	}
	if c.preorder != "" && len(pkgs) > 0 {
		out := make([]any, 0, len(pkgs))
		for i, p := range pkgs {
			m := map[string]any{"sellerOrgId": p.SellerOrgID, "sellerName": p.SellerName, "fulfilment": p.Fulfilment, "lines": p.Lines,
				"subtotalMinor": p.Subtotal}
			if i == 0 {
				m["fulfilment"] = "PREORDER"
				m["preorder"] = PreorderCartBlock(c.preorder)
			}
			out = append(out, m)
		}
		return map[string]any{"packages": out, "saved": saved, "itemCount": items, "subtotalMinor": subtotal, "currency": "USD", "checkoutable": items > 0, "version": 1}
	}
	return map[string]any{"packages": pkgs, "saved": saved, "itemCount": items, "subtotalMinor": subtotal, "currency": "USD", "checkoutable": items > 0, "version": 1}
}

func (u *Upstreams) stock409(w http.ResponseWriter, limit int) {
	problem(w, 409, "cart.insufficient-stock", fmt.Sprintf("quantity exceeds available stock: only %d available", max(limit, 0)), nil)
}

func (u *Upstreams) cartRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/cart", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, u.view(u.cartOf(u.owner(w, r))))
	})
	mux.HandleFunc("POST /v1/cart/items", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		c := u.cartOf(u.owner(w, r))
		var b struct {
			VariantATIN string `json:"variantAtin"`
			OfferID     string `json:"offerId"`
			Quantity    int    `json:"quantity"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b.OfferID == OfferPausedID {
			problem(w, 409, "cart.offer-not-buyable", "this offer cannot be bought right now", nil)
			return
		}
		_, v, ok := u.variant(b.VariantATIN)
		if !ok {
			problem(w, 409, "cart.not-sellable", "product is not available for sale", nil)
			return
		}
		limit := min(v.Stock, 99)
		if v.Stock <= 0 {
			problem(w, 409, "cart.insufficient-stock", "out of stock", nil)
			return
		}
		if i := idx(c.lines, b.VariantATIN); i >= 0 {
			if c.lines[i].qty+b.Quantity > limit {
				u.stock409(w, limit)
				return
			}
			c.lines[i].qty += b.Quantity
			c.lines[i].offer = b.OfferID
		} else {
			if b.Quantity > limit {
				u.stock409(w, limit)
				return
			}
			if j := idx(c.saved, b.VariantATIN); j >= 0 {
				c.saved = slices.Delete(c.saved, j, j+1)
			}
			c.lines = append(c.lines, cartLine{atin: b.VariantATIN, qty: b.Quantity, offer: b.OfferID})
		}
		writeJSON(w, 200, u.view(c))
	})
	mux.HandleFunc("PUT /v1/cart/items/{atin}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		c := u.cartOf(u.owner(w, r))
		var b struct {
			Quantity int `json:"quantity"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		i := idx(c.lines, r.PathValue("atin"))
		if i < 0 {
			problem(w, 404, "cart.line-not-found", "line not in cart", nil)
			return
		}
		_, v, _ := u.variant(r.PathValue("atin"))
		if b.Quantity > min(v.Stock, 99) {
			u.stock409(w, min(v.Stock, 99))
			return
		}
		if b.Quantity == 0 {
			c.lines = slices.Delete(c.lines, i, i+1)
		} else {
			c.lines[i].qty = b.Quantity
		}
		writeJSON(w, 200, u.view(c))
	})
	mux.HandleFunc("DELETE /v1/cart/items/{atin}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		c := u.cartOf(u.owner(w, r))
		a := r.PathValue("atin")
		if i := idx(c.lines, a); i >= 0 {
			c.lines = slices.Delete(c.lines, i, i+1)
		} else if j := idx(c.saved, a); j >= 0 {
			c.saved = slices.Delete(c.saved, j, j+1)
		} else {
			problem(w, 404, "cart.line-not-found", "line not in cart", nil)
			return
		}
		writeJSON(w, 200, u.view(c))
	})
	mux.HandleFunc("POST /v1/cart/items/{atin}/save-for-later", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		c := u.cartOf(u.owner(w, r))
		i := idx(c.lines, r.PathValue("atin"))
		if i < 0 {
			problem(w, 404, "cart.line-not-found", "line not in cart", nil)
			return
		}
		l := c.lines[i]
		c.lines = slices.Delete(c.lines, i, i+1)
		c.saved = append([]cartLine{l}, c.saved...)
		writeJSON(w, 200, u.view(c))
	})
	mux.HandleFunc("POST /v1/cart/saved/{atin}/move-to-cart", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		c := u.cartOf(u.owner(w, r))
		i := idx(c.saved, r.PathValue("atin"))
		if i < 0 {
			problem(w, 404, "cart.line-not-found", "line not in cart", nil)
			return
		}
		_, v, _ := u.variant(r.PathValue("atin"))
		if v.Stock <= 0 {
			problem(w, 409, "cart.insufficient-stock", "out of stock", nil)
			return
		}
		l := c.saved[i]
		l.qty = min(l.qty, v.Stock, 99)
		c.saved = slices.Delete(c.saved, i, i+1)
		c.lines = append(c.lines, l)
		writeJSON(w, 200, u.view(c))
	})
	mux.HandleFunc("POST /v1/cart/merge", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		sub := subjectOf(r)
		g := r.Header.Get("X-Guest-Cart")
		if sub == "" || g == "" {
			problem(w, 400, "cart.merge-invalid", "A signed-in customer and a guest cart id are required", nil)
			return
		}
		user, guest := u.cartOf("user:"+sub), u.cartOf("guest:"+g)
		for _, l := range guest.lines {
			if i := idx(user.lines, l.atin); i >= 0 {
				user.lines[i].qty += l.qty
			} else {
				user.lines = append(user.lines, l)
			}
		}
		delete(u.carts, "guest:"+g)
		writeJSON(w, 200, u.view(user))
	})
	mux.HandleFunc("PUT /v1/cart/preorder-fulfilment", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		var b struct {
			WindowID   string `json:"windowId"`
			Fulfilment string `json:"fulfilment"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		c := u.cartOf(u.owner(w, r))
		if b.WindowID != PreorderWindowID || len(c.lines) == 0 {
			problem(w, 404, "cart.preorder-not-found", "The cart has no pre-order items for this window", nil)
			return
		}
		c.preorder = b.Fulfilment
		writeJSON(w, 200, u.view(c))
	})
}

// PreorderWindowID is the fake delivery-runs window (Lubbock Pre-order #7).
const PreorderWindowID = "LBK-7"

// PreorderCartBlock is the cart service's pre-order block of the fake window with the chosen fulfilment.
func PreorderCartBlock(fulfilment string) map[string]any {
	//nolint:gosec // fake cart data, no credentials (G101 matches "pickup…" keys)
	return map[string]any{"windowId": PreorderWindowID, "code": "LBK-7", "name": "Lubbock Pre-order #7", "stage": "OPEN",
		"opensAt": "2026-10-06T14:00:00Z", "closesAt": "2026-10-22T04:59:00Z", "deliversOn": "2026-10-25", "timeZone": "America/Chicago",
		"fulfilment": fulfilment, "door": true, "pickup": true, "doorFeeMinor": 799, "pickupFeeMinor": 0, "currency": "USD",
		"pickupWindow": "10am–2pm", "allowedMethods": []string{"CARD", "WALLET"},
		"options": []any{map[string]any{"fulfilment": "DOOR", "feeMinor": 799}, map[string]any{"fulfilment": "PICKUP", "feeMinor": 0}}}
}

// preorderRoutes fakes delivery-runs' public pre-order routes: Lubbock (US/TX/Lubbock) has an open window; every
// other place is off with the SHIP fallback.
func (u *Upstreams) preorderRoutes(mux *http.ServeMux) {
	placements := map[string]any{"ribbon": true, "hero": true, "badge": true, "pdp": true, "cart": true, "family": true}
	//nolint:gosec // fake window, no credentials
	window := map[string]any{"id": "7b1e2c3d-0000-4000-8000-000000000007", "code": "LBK-7", "name": "Lubbock Pre-order #7",
		"ribbon": "Lubbock pre-order is open", "headline": "African groceries, delivered to Lubbock", "categories": []string{"Pantry", "Spices"},
		"departments": []string{"Groceries"}, "opensAt": "2026-10-06T14:00:00Z", "closesAt": "2026-10-22T04:59:00Z", "deliversOn": "2026-10-25",
		"timeZone": "America/Chicago", "showCount": true, "count": 142, "door": true, "pickup": true, "doorFeeMinor": 799, "pickupFeeMinor": 0,
		"currency": "USD", "pickupWindow": "10am–2pm", "allowedMethods": []string{"CARD", "WALLET"},
		"creatives": map[string]any{"hero": map[string]any{"url": "https://img.example/hero.jpg", "width": 1920, "height": 640}, "pdp": nil}}
	mux.HandleFunc("GET /v1/public/preorders/resolve", func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if q.Get("country") == "US" && q.Get("region") == "TX" && strings.EqualFold(q.Get("city"), "Lubbock") {
			writeJSON(w, 200, map[string]any{"metro": false, "covered": true, "on": true, "stage": "OPEN", "countdownTo": window["closesAt"],
				"window": window, "fallback": "SHIP", "placements": placements, "supportPhone": "+1 832 555 0100"})
			return
		}
		writeJSON(w, 200, map[string]any{"metro": q.Get("city") == "Houston", "covered": false, "on": false, "stage": nil, "countdownTo": nil,
			"window": nil, "fallback": "SHIP", "placements": placements, "supportPhone": "+1 832 555 0100"})
	})
	mux.HandleFunc("GET /v1/public/preorders/open", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"placements": placements, "windows": []any{map[string]any{"id": window["id"], "code": "LBK-7",
			"name": "Lubbock Pre-order #7", "where": "Lubbock, TX", "stage": "OPEN", "opensAt": window["opensAt"], "closesAt": window["closesAt"],
			"deliversOn": "2026-10-25", "timeZone": "America/Chicago", "place": map[string]any{"country": "US", "region": "TX", "city": "Lubbock"},
			"zip": "79401", "heroUrl": "https://img.example/hero.jpg", "doorFeeMinor": 799, "currency": "USD"}}})
	})
	mux.HandleFunc("POST /v1/public/subscriptions", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		u.LastWaitlist = b
		u.mu.Unlock()
		writeJSON(w, 202, map[string]any{"status": "CONFIRMATION_SENT"})
	})
	mux.HandleFunc("POST /v1/public/preorders/waitlist/unsubscribe", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["token"] != "wl-token-1" {
			problem(w, 422, "delivery-runs.invalid-token", "This link is not valid", nil)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
}

// ---------------------------------------------------------------- identity (authorization server + REST)

// IssueCode simulates identity's /oauth2/authorize: the user signed in, a code is bound to the PKCE challenge.
func (u *Upstreams) IssueCode(challenge, nonce, subject, redirectURI string) string {
	u.mu.Lock()
	defer u.mu.Unlock()
	code := "code-" + newUUID()
	u.codes[code] = issuedCode{challenge: challenge, nonce: nonce, subject: subject, redirectURI: redirectURI}
	return code
}

// Token signs a JWT with the fake identity key (issuer = server URL).
func (u *Upstreams) Token(claims jwt.MapClaims) string {
	claims["iss"] = u.Server.URL
	if _, ok := claims["exp"]; !ok {
		claims["exp"] = time.Now().Add(u.AccessTTL).Unix()
	}
	claims["iat"] = time.Now().Unix()
	t := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	t.Header["kid"] = "k1"
	s, err := t.SignedString(u.key)
	if err != nil {
		panic(err)
	}
	return s
}

func (u *Upstreams) tokens(sub, nonce string) map[string]any {
	rt := "rt-" + newUUID()
	u.refresh[rt] = sub
	return map[string]any{
		"access_token":  u.Token(jwt.MapClaims{"sub": sub, "pt": "CUSTOMER", "roles": []string{"account.customer"}}),
		"refresh_token": rt,
		"id_token": u.Token(jwt.MapClaims{"sub": sub, "aud": u.ClientID, "nonce": nonce, "given_name": "Adaeze",
			"family_name": "Nwosu", "email": "adaeze@example.com", "roles": []string{"account.customer"}}),
		"token_type": "Bearer",
		"expires_in": int(u.AccessTTL.Seconds()),
	}
}

func (u *Upstreams) identityRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /oauth2/jwks", func(w http.ResponseWriter, _ *http.Request) {
		pub := u.key.PublicKey
		writeJSON(w, 200, map[string]any{"keys": []map[string]any{{"kty": "RSA", "kid": "k1", "alg": "RS256", "use": "sig",
			"n": base64.RawURLEncoding.EncodeToString(pub.N.Bytes()), "e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(pub.E)).Bytes())}}})
	})
	mux.HandleFunc("POST /oauth2/token", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		id, secret, ok := r.BasicAuth()
		if !ok || id != u.ClientID || secret != u.Secret {
			writeJSON(w, 401, map[string]any{"error": "invalid_client"})
			return
		}
		_ = r.ParseForm()
		switch r.PostForm.Get("grant_type") {
		case "client_credentials":
			u.ServiceTokenRequests = append(u.ServiceTokenRequests, r.PostForm.Get("scope"))
			writeJSON(w, 200, map[string]any{"access_token": "svc-" + r.PostForm.Get("scope"), "token_type": "Bearer", "expires_in": 300})
		case "authorization_code":
			c, ok := u.codes[r.PostForm.Get("code")]
			delete(u.codes, r.PostForm.Get("code"))
			sum := sha256.Sum256([]byte(r.PostForm.Get("code_verifier")))
			if !ok || base64.RawURLEncoding.EncodeToString(sum[:]) != c.challenge || r.PostForm.Get("redirect_uri") != c.redirectURI {
				writeJSON(w, 400, map[string]any{"error": "invalid_grant"})
				return
			}
			writeJSON(w, 200, u.tokens(c.subject, c.nonce))
		case "refresh_token":
			sub, ok := u.refresh[r.PostForm.Get("refresh_token")]
			delete(u.refresh, r.PostForm.Get("refresh_token")) // single use, like identity
			if !ok {
				writeJSON(w, 400, map[string]any{"error": "invalid_grant"})
				return
			}
			writeJSON(w, 200, u.tokens(sub, ""))
		default:
			writeJSON(w, 400, map[string]any{"error": "unsupported_grant_type"})
		}
	})
	mux.HandleFunc("POST /oauth2/revoke", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		_ = r.ParseForm()
		u.revoked = append(u.revoked, r.PostForm.Get("token"))
		delete(u.refresh, r.PostForm.Get("token"))
		w.WriteHeader(200)
	})
	mux.HandleFunc("GET /v1/me", func(w http.ResponseWriter, r *http.Request) {
		sub := subjectOf(r)
		if sub == "" {
			problem(w, 401, "auth.unauthenticated", "Bearer token required", nil)
			return
		}
		u.mu.Lock()
		orgs, active := u.memberOrgsJSON(), u.ActiveOrgID
		u.mu.Unlock()
		u.mu.Lock()
		verified := u.PhoneVerified
		u.mu.Unlock()
		writeJSON(w, 200, map[string]any{"id": sub, "firstName": "Adaeze", "lastName": "Nwosu", "email": "adaeze@example.com", "roles": []string{"account.customer"},
			"landing": "shop", "orgId": active, "orgs": orgs, "phone": "+17135550142", "phoneVerified": verified})
	})
	mux.HandleFunc("GET /v1/identity-providers", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"id": "google", "displayName": "Google", "authorizationPath": "/oauth2/authorization/google"}})
	})
	mux.HandleFunc("POST /v1/accounts", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		u.LastSignUp = b
		u.mu.Unlock()
		if b["email"] == "taken@example.com" {
			problem(w, 422, "identity.email-taken", "Email already registered", map[string]any{"fieldErrors": map[string]string{"email": "taken"}})
			return
		}
		writeJSON(w, 201, map[string]any{"userId": newUUID(), "email": b["email"], "verificationRequired": true})
	})
	mux.HandleFunc("POST /v1/accounts/verification", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["code"] != "123456" {
			problem(w, 422, "identity.invalid-code", "Wrong code", map[string]any{"fieldErrors": map[string]string{"code": "wrong"}})
			return
		}
		w.WriteHeader(204)
	})
	mux.HandleFunc("POST /v1/accounts/verification/resend", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 202, map[string]any{"codeExpiresAt": time.Now().Add(10 * time.Minute)})
	})
	mux.HandleFunc("GET /v1/me/addresses", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		list := u.addresses[subjectOf(r)]
		if list == nil {
			list = []map[string]any{}
		}
		writeJSON(w, 200, list)
	})
	mux.HandleFunc("POST /v1/me/addresses", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		var a map[string]any
		_ = json.NewDecoder(r.Body).Decode(&a)
		sub := subjectOf(r)
		a["id"] = newUUID()
		a["isDefault"] = len(u.addresses[sub]) == 0 || a["makeDefault"] == true
		delete(a, "makeDefault")
		u.addresses[sub] = append(u.addresses[sub], a)
		writeJSON(w, 201, a)
	})
	mux.HandleFunc("DELETE /v1/me/addresses/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		sub := subjectOf(r)
		i := slices.IndexFunc(u.addresses[sub], func(a map[string]any) bool { return a["id"] == r.PathValue("id") })
		if i < 0 {
			problem(w, 404, "identity.address-not-found", "No such address", nil)
			return
		}
		u.addresses[sub] = slices.Delete(u.addresses[sub], i, i+1)
		w.WriteHeader(204)
	})
}

func (u *Upstreams) contentRoutes(mux *http.ServeMux) {
	// Shapes follow contracts/openapi/content.yaml.
	mux.HandleFunc("GET /v1/site-config", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("ETag", `"rev-7"`)
		w.Header().Set("Cache-Control", "public, max-age=60, stale-while-revalidate=600")
		writeJSON(w, 200, map[string]any{"revision": 7,
			"socials": []map[string]any{{"network": "instagram", "url": "https://instagram.com/afrotransact", "label": "Instagram"},
				{"network": "myspace", "url": "https://myspace.com/x", "label": "MySpace"}},
			"contacts": map[string]any{"phone": "+1 713 555 0100", "phoneE164": "+17135550100", "supportEmail": "help@afrotransact.com", "privacyEmail": "privacy@afrotransact.com",
				"accessibilityEmail": "a11y@afrotransact.com", "legalEmail": "legal@afrotransact.com", "pressEmail": "press@afrotransact.com",
				"partnersEmail": "partners@afrotransact.com", "careersEmail": "jobs@afrotransact.com", "address": "Houston, TX",
				"postalAddress": map[string]any{"line1": "1 Main", "city": "Houston", "region": "TX", "postalCode": "77002", "country": "US"}},
			"legal": []map[string]any{{"key": "terms", "title": "Conditions of Use", "version": "3", "effective": "2026-09-01", "href": "/legal/terms", "draft": false},
				{"key": "privacy", "title": "Privacy", "version": "4", "effective": "2026-12-01", "href": "/legal/privacy", "draft": true}},
			"identityProviders": []map[string]any{{"id": "apple", "name": "Apple", "order": 2}, {"id": "google", "name": "Google", "order": 1}},
			"locales":           []map[string]any{{"code": "en", "name": "English", "dir": "ltr"}, {"code": "ar", "name": "العربية", "dir": "rtl"}},
			"market":            map[string]any{"code": "us", "currency": "USD", "country": "US"},
			"appLinks":          map[string]any{"ios": "https://apps.apple.com/app/afrotransact"},
			"paymentMethods":    []string{"visa", "mastercard"},
			"smsProgramName":    "AfroTransact Alerts",
			"updatedAt":         "2026-10-01T00:00:00Z"})
	})
	mux.HandleFunc("GET /v1/placements/resolve", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Cache-Control", "public, max-age=30")
		writeJSON(w, 200, map[string]any{"market": "us", "placements": []map[string]any{
			{"id": "plc_ann", "slot": "announcement_bar", "priority": 30, "payload": map[string]any{"tone": "gold", "icon": "ph ph-truck", "text": "Free delivery over $35", "cta": "Shop", "href": "/s"}},
			{"id": "plc_hero", "slot": "hero_carousel", "priority": 40, "label": "Sponsored", "advertiser": "Kingston Kitchen",
				"payload": map[string]any{"theme": "ink", "kicker": "New", "title": "Taste of home", "sub": "Groceries from home", "cta": "Shop", "href": "/s", "label": "Sponsored", "imageUrl": nil}},
			{"id": "plc_tiles", "slot": "home_tiles", "priority": 10, "payload": map[string]any{"title": "Shop by community", "cta": "See all", "href": "/communities",
				"cells": []map[string]any{{"label": "Nigerian", "href": "/communities/nigerian", "imageUrl": nil}, {"label": "Ghanaian", "href": "/communities/ghanaian"}}}},
			{"id": "plc_banner", "slot": "display_banner", "priority": 10, "payload": map[string]any{"position": "home-mid", "advertiser": "Bonwire Weavers", "title": "Kente", "sub": "Hand-woven", "cta": "Shop", "href": "/s?q=kente", "imageUrl": nil}},
			{"id": "plc_row_fast", "slot": "home_product_row", "priority": 20, "payload": map[string]any{"title": "Pantry staples", "href": "/s?q=garri", "query": "jollof OR garri OR nothing-matches"}},
			{"id": "plc_row_picked", "slot": "home_product_row", "priority": 10, "payload": map[string]any{"title": "Picked for you", "href": "/s", "skus": []string{"ATSHEA0001", "ATNOPE0000"}}},
			{"id": "plc_unknown", "slot": "checkout_cross_sell", "priority": 10, "payload": map[string]any{"title": "x", "skus": []string{"ATSHEA0001"}}},
		}})
	})
	mux.HandleFunc("GET /v1/markets/eligibility", func(w http.ResponseWriter, r *http.Request) {
		allowed := r.URL.Query().Get("state") != "NY" // New York switched off in this fixture
		writeJSON(w, 200, map[string]any{"country": r.URL.Query().Get("country"), "countryStatus": "LIVE", "region": r.URL.Query().Get("state"),
			"onboardingAllowed": true, "deliveryAllowed": allowed, "marketsVersion": 3})
	})
	mux.HandleFunc("POST /v1/newsletter/subscriptions", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 202, map[string]any{"status": "pending_confirmation"})
	})
	mux.HandleFunc("GET /v1/careers/jobs", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"teams": []string{"Platform"}, "locations": []string{"Remote (US)"}, "jobs": []map[string]any{
			{"id": "eng-1", "title": "Backend engineer", "team": "Platform", "location": "Remote (US)", "type": "Full-time", "pay": "$150k–$190k", "summary": "Build the BFF", "responsibilities": []string{"Ship"}, "requirements": []string{"Go"}, "status": "OPEN", "postedAt": "2026-09-01T00:00:00Z"},
			{"id": "old-1", "title": "Closed role", "team": "Platform", "location": "Remote", "type": "Full-time", "pay": "-", "summary": "-", "status": "CLOSED"}}})
	})
	mux.HandleFunc("POST /v1/careers/jobs/{id}/applications", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != "eng-1" {
			problem(w, 404, "content.job-not-found", "No such job", nil)
			return
		}
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		if b["resumeAssetId"] == nil {
			problem(w, 422, "content.rule-violation", "Application invalid", map[string]any{"fieldErrors": map[string]string{"resumeAssetId": "Upload your résumé"}})
			return
		}
		writeJSON(w, 201, map[string]any{"reference": "CAR-100001", "jobId": "eng-1", "submittedAt": time.Now()})
	})
}
