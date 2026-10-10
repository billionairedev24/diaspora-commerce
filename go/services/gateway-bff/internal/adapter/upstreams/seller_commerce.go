package upstreams

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

func idem(key string) http.Header {
	if key == "" {
		return nil
	}
	return http.Header{"Idempotency-Key": {key}}
}

// ---------------------------------------------------------------- catalog (contracts/openapi/catalog.yaml)

type sellerCatalogImpl struct {
	c     httpclient.Client
	cache port.Cache
	ttl   time.Duration
}

var _ port.SellerCatalogClient = (*sellerCatalogImpl)(nil)

// NewSellerCatalog returns catalog's authenticated product-builder client. The category tree and product types are
// public reference data and cached for ttl.
func NewSellerCatalog(c httpclient.Client, cache port.Cache, ttl time.Duration) port.SellerCatalogClient {
	return &sellerCatalogImpl{c: c, cache: cache, ttl: ttl}
}

func (k *sellerCatalogImpl) refTTL(h http.Header) time.Duration { return ttlFor(h, k.ttl, k.ttl) }

func (k *sellerCatalogImpl) Products(ctx context.Context, bearer string, q port.ProductQuery) (port.CatalogProductPage, error) {
	v := url.Values{"page": {strconv.Itoa(q.Page)}, "size": {strconv.Itoa(q.Size)}}
	if q.Status != "" {
		v.Set("status", q.Status)
	}
	if q.Q != "" {
		v.Set("q", q.Q)
	}
	var p port.CatalogProductPage
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/products", Query: v, Bearer: bearer}, &p)
	return p, err
}

func (k *sellerCatalogImpl) Create(ctx context.Context, bearer string, d domain.ProductDraft) (port.CatalogOwnProduct, error) {
	body := map[string]any{"title": d.Title, "attributes": d.Attributes, "images": d.Images, "variationTheme": d.VariationTheme,
		"variants": d.Variants, "currency": domain.Currency}
	if d.CategoryID != "" {
		body["categoryId"] = d.CategoryID
	} else {
		body["categoryRequestId"] = d.CategoryRequestID
	}
	var p port.CatalogOwnProduct
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/products", Bearer: bearer, JSON: body}, &p)
	return p, err
}

func (k *sellerCatalogImpl) SetActive(ctx context.Context, bearer, productID string, active bool) (port.CatalogOwnProduct, error) {
	action := "/pause"
	if active {
		action = "/resume"
	}
	var p port.CatalogOwnProduct
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/products/" + url.PathEscape(productID) + action, Bearer: bearer}, &p)
	return p, err
}

func (k *sellerCatalogImpl) CategoryTree(ctx context.Context) ([]port.CatalogCategoryNode, error) {
	return cached(k.cache, "catalog:category-nodes", func() ([]port.CatalogCategoryNode, http.Header, error) {
		res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/categories"}, nil)
		if err != nil {
			return nil, nil, err
		}
		items, _, err := itemsOf[port.CatalogCategoryNode](res.Body)
		return items, res.Header, err
	}, k.refTTL)
}

func (k *sellerCatalogImpl) ProductType(ctx context.Context, code string) (port.CatalogProductType, error) {
	return cached(k.cache, "catalog:product-type:"+code, func() (port.CatalogProductType, http.Header, error) {
		var t port.CatalogProductType
		res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/product-types/" + url.PathEscape(code)}, &t)
		if err != nil {
			return t, nil, err
		}
		return t, res.Header, nil
	}, k.refTTL)
}

func (k *sellerCatalogImpl) CheckGTIN(ctx context.Context, bearer, code string, isbn bool) (port.CatalogGtinCheck, error) {
	var g port.CatalogGtinCheck
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/identifiers/gtin/" + url.PathEscape(code), Bearer: bearer,
		Query: url.Values{"isbn": {strconv.FormatBool(isbn)}}}, &g)
	return g, err
}

func (k *sellerCatalogImpl) CategoryRequests(ctx context.Context, bearer string) ([]port.CatalogCategoryRequest, error) {
	res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/category-requests/mine", Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.CatalogCategoryRequest](res.Body)
	return items, err
}

func (k *sellerCatalogImpl) RequestCategory(ctx context.Context, bearer string, in domain.CategoryRequestInput) (port.CatalogCategoryRequest, error) {
	var r port.CatalogCategoryRequest
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/category-requests", Bearer: bearer, JSON: in}, &r)
	return r, err
}

// ---------------------------------------------------------------- inventory and offers

type stockImpl struct{ c httpclient.Client }

var _ port.StockClient = (*stockImpl)(nil)

// NewStock returns inventory's positions client.
func NewStock(c httpclient.Client) port.StockClient { return &stockImpl{c: c} }

func (s *stockImpl) Positions(ctx context.Context, bearer, ownerOrg string, lowStock bool) ([]domain.StockPosition, error) {
	var page struct {
		Items []struct {
			SKU       string `json:"sku"`
			NodeID    string `json:"nodeId"`
			OnHand    int    `json:"onHand"`
			Reserved  int    `json:"reserved"`
			Available int    `json:"available"`
			Inbound   int    `json:"inbound"`
		} `json:"items"`
	}
	q := url.Values{"ownerOrg": {ownerOrg}, "size": {"200"}}
	if lowStock {
		q.Set("lowStock", "true")
	}
	if _, err := call(ctx, s.c, httpclient.Request{Path: "/v1/positions", Query: q, Bearer: bearer}, &page); err != nil {
		return nil, err
	}
	out := make([]domain.StockPosition, 0, len(page.Items))
	for _, p := range page.Items {
		out = append(out, domain.StockPosition{SKU: p.SKU, NodeID: p.NodeID, OnHand: p.OnHand, Reserved: p.Reserved, Available: p.Available,
			Inbound: p.Inbound, LowStock: p.Available <= domain.InventoryLowStockThreshold})
	}
	return out, nil
}

type offersImpl struct{ c httpclient.Client }

var _ port.OffersClient = (*offersImpl)(nil)

// NewOffers returns the offers client (planned: PUT /v1/offers/stock/{sellerSku}).
func NewOffers(c httpclient.Client) port.OffersClient { return &offersImpl{c: c} }

func (o *offersImpl) SetStock(ctx context.Context, bearer, key, sku string, quantity int, reason string) (domain.StockAdjustment, error) {
	var out domain.StockAdjustment
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPut, Path: "/v1/offers/stock/" + url.PathEscape(sku), Bearer: bearer,
		Header: idem(key), RetryUnsafe: key != "", JSON: map[string]any{"quantity": quantity, "reason": reason}}, &out)
	return out, err
}

// ---------------------------------------------------------------- media (contracts/openapi/media.yaml)

type mediaImpl struct{ c httpclient.Client }

var _ port.MediaClient = (*mediaImpl)(nil)

// NewMedia returns the media client.
func NewMedia(c httpclient.Client) port.MediaClient { return &mediaImpl{c: c} }

func (m *mediaImpl) RequestUpload(ctx context.Context, bearer, purpose, fileName, contentType string, size int64) (port.MediaUpload, error) {
	var out port.MediaUpload
	_, err := call(ctx, m.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/uploads", Bearer: bearer,
		JSON: map[string]any{"purpose": purpose, "fileName": fileName, "contentType": contentType, "size": size}}, &out)
	return out, err
}

func (m *mediaImpl) Complete(ctx context.Context, bearer, assetID string) (port.MediaAsset, error) {
	var a port.MediaAsset
	_, err := call(ctx, m.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/assets/" + url.PathEscape(assetID) + "/complete", Bearer: bearer}, &a)
	return a, err
}

// ---------------------------------------------------------------- orders (seller side; service being built)

type sellerOrdersImpl struct{ c httpclient.Client }

var _ port.SellerOrdersClient = (*sellerOrdersImpl)(nil)

// NewSellerOrders returns the orders client for sellers: GET /v1/seller/shipments, GET /v1/orders/{number},
// POST /v1/shipments/{id}/transitions and /tracking, and the planned GET /v1/seller/analytics.
func NewSellerOrders(c httpclient.Client) port.SellerOrdersClient { return &sellerOrdersImpl{c: c} }

func (o *sellerOrdersImpl) Queue(ctx context.Context, bearer, status, cursor string, limit int) (port.ShipmentQueue, error) {
	q := url.Values{"limit": {strconv.Itoa(limit)}}
	if status != "" {
		q.Set("status", status)
	}
	if cursor != "" {
		q.Set("cursor", cursor)
	}
	var out port.ShipmentQueue
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/seller/shipments", Query: q, Bearer: bearer}, &out)
	return out, err
}

func (o *sellerOrdersImpl) Order(ctx context.Context, bearer, number string) (port.OrderView, error) {
	var out port.OrderView
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/orders/" + url.PathEscape(number), Bearer: bearer}, &out)
	return out, err
}

func (o *sellerOrdersImpl) Transition(ctx context.Context, bearer, key, shipmentID, to string) (port.OrderView, error) {
	var out struct {
		Order port.OrderView `json:"order"`
	}
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/shipments/" + url.PathEscape(shipmentID) + "/transitions",
		Bearer: bearer, Header: idem(key), RetryUnsafe: key != "", JSON: map[string]string{"to": to}}, &out)
	return out.Order, err
}

func (o *sellerOrdersImpl) AttachTracking(ctx context.Context, bearer, shipmentID, carrier, tracking string) (port.OrderView, error) {
	var out port.OrderView
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/shipments/" + url.PathEscape(shipmentID) + "/tracking",
		Bearer: bearer, JSON: map[string]string{"carrier": carrier, "trackingNumber": tracking}}, &out)
	return out, err
}

func (o *sellerOrdersImpl) Analytics(ctx context.Context, bearer string, from, to time.Time) (port.SalesAnalytics, error) {
	var out port.SalesAnalytics
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/seller/analytics", Bearer: bearer,
		Query: url.Values{"from": {from.Format(time.DateOnly)}, "to": {to.Format(time.DateOnly)}}}, &out)
	return out, err
}

// ---------------------------------------------------------------- carrier-gateway (contracts/openapi/carrier-gateway.yaml)

type carrierImpl struct{ c httpclient.Client }

var _ port.CarrierClient = (*carrierImpl)(nil)

// NewCarrier returns the carrier-gateway client (rates and labels).
func NewCarrier(c httpclient.Client) port.CarrierClient { return &carrierImpl{c: c} }

func (k *carrierImpl) Rates(ctx context.Context, bearer string, s port.CarrierShipment) (port.CarrierRates, error) {
	var out port.CarrierRates
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/rates", Bearer: bearer, JSON: map[string]any{"shipment": s}}, &out)
	return out, err
}

func (k *carrierImpl) BuyLabel(ctx context.Context, bearer, key, carrier, service string, s port.CarrierShipment) (port.CarrierLabel, error) {
	var out port.CarrierLabel
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/labels", Bearer: bearer, Header: idem(key), RetryUnsafe: true,
		JSON: map[string]any{"carrier": carrier, "service": service, "shipment": s}}, &out)
	return out, err
}

// ---------------------------------------------------------------- payments: payouts (contracts/openapi/payments.yaml)

type payoutsImpl struct{ c httpclient.Client }

var _ port.PayoutsClient = (*payoutsImpl)(nil)

// NewPayouts returns the payouts client.
func NewPayouts(c httpclient.Client) port.PayoutsClient { return &payoutsImpl{c: c} }

func (p *payoutsImpl) Balance(ctx context.Context, bearer, orgID string) (port.PayoutBalanceResponse, error) {
	var b port.PayoutBalanceResponse
	_, err := call(ctx, p.c, httpclient.Request{Path: "/v1/payouts/balance", Query: url.Values{"orgId": {orgID}}, Bearer: bearer}, &b)
	return b, err
}

func (p *payoutsImpl) ProviderFees(ctx context.Context, bearer, orgID string) ([]port.ProviderFeeResponse, error) {
	res, err := call(ctx, p.c, httpclient.Request{Path: "/v1/payouts/provider-fees", Query: url.Values{"orgId": {orgID}}, Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.ProviderFeeResponse](res.Body)
	return items, err
}

func (p *payoutsImpl) Requests(ctx context.Context, bearer, orgID string) ([]port.PayoutResponse, error) {
	res, err := call(ctx, p.c, httpclient.Request{Path: "/v1/payouts/requests", Query: url.Values{"orgId": {orgID}, "limit": {"50"}}, Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.PayoutResponse](res.Body)
	return items, err
}

func (p *payoutsImpl) RequestPayout(ctx context.Context, bearer, key string, amountMinor int64, all bool) (port.PayoutResponse, error) {
	body := map[string]any{"currency": domain.Currency}
	if all {
		body["all"] = true
	} else {
		body["amountMinor"] = amountMinor
	}
	var out port.PayoutResponse
	_, err := call(ctx, p.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/payouts/requests", Bearer: bearer, Header: idem(key),
		RetryUnsafe: key != "", JSON: body}, &out)
	return out, err
}

// ---------------------------------------------------------------- storefront (contracts/openapi/storefront.yaml)

type storefrontImpl struct{ c httpclient.Client }

var _ port.StorefrontClient = (*storefrontImpl)(nil)

// NewStorefront returns the storefront-service client (draft, save, publish).
func NewStorefront(c httpclient.Client) port.StorefrontClient { return &storefrontImpl{c: c} }

func (s *storefrontImpl) Draft(ctx context.Context, bearer string) (port.Storefront, error) {
	out := port.Storefront{}
	_, err := call(ctx, s.c, httpclient.Request{Path: "/v1/storefronts/me/draft", Bearer: bearer}, &out)
	return out, err
}

func (s *storefrontImpl) SaveDraft(ctx context.Context, bearer string, doc port.Storefront) (port.Storefront, error) {
	out := port.Storefront{}
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPut, Path: "/v1/storefronts/me/draft", Bearer: bearer, JSON: doc}, &out)
	return out, err
}

func (s *storefrontImpl) Publish(ctx context.Context, bearer string) error {
	_, err := call(ctx, s.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/storefronts/me/publish", Bearer: bearer}, nil)
	return err
}

// ---------------------------------------------------------------- bookings (planned; service being built)

type bookingsImpl struct{ c httpclient.Client }

var _ port.BookingsClient = (*bookingsImpl)(nil)

// NewBookings returns the bookings client for the planned provider endpoints under /v1/providers/me and
// /v1/bookings/{id}/{action}.
func NewBookings(c httpclient.Client) port.BookingsClient { return &bookingsImpl{c: c} }

func dateRange(from, to time.Time) url.Values {
	return url.Values{"from": {from.Format(time.DateOnly)}, "to": {to.Format(time.DateOnly)}}
}

func (b *bookingsImpl) Calendar(ctx context.Context, bearer string, from, to time.Time) (port.BookingsCalendar, error) {
	var out port.BookingsCalendar
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/providers/me/calendar", Query: dateRange(from, to), Bearer: bearer}, &out)
	return out, err
}

func (b *bookingsImpl) Requests(ctx context.Context, bearer string) ([]domain.BookingCard, error) {
	res, err := call(ctx, b.c, httpclient.Request{Path: "/v1/providers/me/bookings", Bearer: bearer,
		Query: url.Values{"status": {"REQUESTED,RESCHEDULE_REQUESTED"}}}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[domain.BookingCard](res.Body)
	return items, err
}

var bookingActionPaths = map[string]string{"CONFIRM": "confirm", "DECLINE": "decline", "PROPOSE": "propose", "COMPLETE": "complete",
	"NO_SHOW": "no-show", "CANCEL": "cancel"}

func (b *bookingsImpl) Act(ctx context.Context, bearer, key, id, action, reason string, proposed *time.Time) (domain.BookingCard, error) {
	seg, ok := bookingActionPaths[action]
	if !ok {
		return domain.BookingCard{}, fmt.Errorf("bookings: unknown action %q", action)
	}
	body := map[string]any{}
	if reason != "" {
		body["reason"] = reason
	}
	if proposed != nil {
		body["proposedStartAt"] = proposed.UTC().Format(time.RFC3339)
	}
	var out domain.BookingCard
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/bookings/" + url.PathEscape(id) + "/" + seg, Bearer: bearer,
		Header: idem(key), RetryUnsafe: key != "", JSON: body}, &out)
	return out, err
}

func (b *bookingsImpl) Availability(ctx context.Context, bearer string) (domain.Availability, error) {
	var out domain.Availability
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/providers/me/availability", Bearer: bearer}, &out)
	return out, err
}

func (b *bookingsImpl) SaveAvailability(ctx context.Context, bearer string, a domain.Availability) (domain.Availability, error) {
	var out domain.Availability
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPut, Path: "/v1/providers/me/availability", Bearer: bearer, JSON: a}, &out)
	return out, err
}

func (b *bookingsImpl) Services(ctx context.Context, bearer string) ([]domain.ServiceOffer, error) {
	res, err := call(ctx, b.c, httpclient.Request{Path: "/v1/providers/me/services", Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[domain.ServiceOffer](res.Body)
	return items, err
}

func (b *bookingsImpl) CreateService(ctx context.Context, bearer string, in domain.ServiceInput) (domain.ServiceOffer, error) {
	var out domain.ServiceOffer
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/providers/me/services", Bearer: bearer, JSON: in}, &out)
	return out, err
}

func (b *bookingsImpl) UpdateService(ctx context.Context, bearer, id string, in domain.ServiceInput) (domain.ServiceOffer, error) {
	var out domain.ServiceOffer
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPatch, Path: "/v1/providers/me/services/" + url.PathEscape(id), Bearer: bearer, JSON: in}, &out)
	return out, err
}

func (b *bookingsImpl) Policy(ctx context.Context, bearer string) (domain.PolicyInput, error) {
	var out domain.PolicyInput
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/providers/me/cancellation-policy", Bearer: bearer}, &out)
	return out, err
}

func (b *bookingsImpl) SavePolicy(ctx context.Context, bearer string, p domain.PolicyInput) (domain.PolicyInput, error) {
	var out domain.PolicyInput
	_, err := call(ctx, b.c, httpclient.Request{Method: http.MethodPut, Path: "/v1/providers/me/cancellation-policy", Bearer: bearer, JSON: p}, &out)
	return out, err
}

func (b *bookingsImpl) Earnings(ctx context.Context, bearer string, from, to time.Time) (port.BookingsEarnings, error) {
	var out port.BookingsEarnings
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/providers/me/earnings", Query: dateRange(from, to), Bearer: bearer}, &out)
	return out, err
}

func (b *bookingsImpl) Analytics(ctx context.Context, bearer string, from, to time.Time) (port.BookingsAnalytics, error) {
	var out port.BookingsAnalytics
	_, err := call(ctx, b.c, httpclient.Request{Path: "/v1/providers/me/analytics", Query: dateRange(from, to), Bearer: bearer}, &out)
	return out, err
}
