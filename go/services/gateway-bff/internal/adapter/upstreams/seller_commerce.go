package upstreams

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
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

// NewOffers returns the offers client for sellers: PUT /v1/offers/stock/{sellerSku}, GET /v1/offers and PATCH
// /v1/offers/{id} (fulfilment and pickup location, S255).
func NewOffers(c httpclient.Client) port.OffersClient { return &offersImpl{c: c} }

func (o *offersImpl) SetStock(ctx context.Context, bearer, key, sku string, quantity int, reason string) (domain.StockAdjustment, error) {
	var out domain.StockAdjustment
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPut, Path: "/v1/offers/stock/" + url.PathEscape(sku), Bearer: bearer,
		Header: idem(key), RetryUnsafe: key != "", JSON: map[string]any{"quantity": quantity, "reason": reason}}, &out)
	return out, err
}

func (o *offersImpl) List(ctx context.Context, bearer string, page, size int) (port.OfferPage, error) {
	var out port.OfferPage
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/offers", Bearer: bearer,
		Query: url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}}, &out)
	return out, err
}

func (o *offersImpl) Update(ctx context.Context, bearer, id string, body map[string]any) (port.SellerOfferUp, error) {
	var out port.SellerOfferUp
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPatch, Path: "/v1/offers/" + url.PathEscape(id), Bearer: bearer, JSON: body}, &out)
	return out, err
}

func (o *offersImpl) ListCoupons(ctx context.Context, bearer string) ([]port.SellerCouponUp, error) {
	var out []port.SellerCouponUp
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/coupons", Bearer: bearer, Query: url.Values{"funding": {"SELLER"}}}, &out)
	return out, err
}

func (o *offersImpl) CreateCoupon(ctx context.Context, bearer string, body map[string]any) (port.SellerCouponUp, error) {
	var out port.SellerCouponUp
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/coupons", Bearer: bearer, JSON: body}, &out)
	return out, err
}

func (o *offersImpl) SetCouponStatus(ctx context.Context, bearer, id, status string) (port.SellerCouponUp, error) {
	var out port.SellerCouponUp
	_, err := call(ctx, o.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/coupons/" + url.PathEscape(id) + "/status", Bearer: bearer,
		JSON: map[string]any{"status": status}}, &out)
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

func (m *mediaImpl) RequestLibraryUpload(ctx context.Context, bearer, fileName, contentType string, size int64) (port.MediaUpload, error) {
	var out port.MediaUpload
	_, err := call(ctx, m.c, httpclient.Request{Method: http.MethodPost, Path: "/v1/uploads", Bearer: bearer,
		JSON: map[string]any{"purpose": "product-image", "fileName": fileName, "contentType": contentType, "size": size, "library": true}}, &out)
	return out, err
}

func (m *mediaImpl) Library(ctx context.Context, bearer, q, status, matched string, page, size int) (port.MediaLibraryPage, error) {
	v := url.Values{"page": {strconv.Itoa(page)}, "size": {strconv.Itoa(size)}}
	if q != "" {
		v.Set("q", q)
	}
	if status != "" {
		v.Set("status", status)
	}
	if matched != "" {
		v.Set("matched", matched)
	}
	var out port.MediaLibraryPage
	_, err := call(ctx, m.c, httpclient.Request{Path: "/v1/library", Query: v, Bearer: bearer}, &out)
	return out, err
}

func (m *mediaImpl) RemoveFromLibrary(ctx context.Context, bearer, assetID string) error {
	_, err := call(ctx, m.c, httpclient.Request{Method: http.MethodDelete, Path: "/v1/library/" + url.PathEscape(assetID), Bearer: bearer}, nil)
	return err
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

func (o *sellerOrdersImpl) Split(ctx context.Context, bearer, number string) (port.OrderSplit, error) {
	var out port.OrderSplit
	_, err := call(ctx, o.c, httpclient.Request{Path: "/v1/orders/" + url.PathEscape(number) + "/payment-split", Bearer: bearer}, &out)
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

func (k *carrierImpl) LabelDocument(ctx context.Context, bearer, carrier, trackingNumber string) (domain.FileDownload, error) {
	res, err := call(ctx, k.c, httpclient.Request{Path: "/v1/labels/by-tracking/" + url.PathEscape(carrier) + "/" + url.PathEscape(trackingNumber) + "/document",
		Bearer: bearer, Header: http.Header{"Accept": {"*/*"}}}, nil)
	if err != nil {
		return domain.FileDownload{}, err
	}
	ct := res.Header.Get("Content-Type")
	if ct == "" {
		ct = "application/pdf"
	}
	ext := ".pdf"
	if strings.Contains(ct, "zpl") {
		ext = ".zpl"
	}
	return domain.FileDownload{FileName: "label-" + trackingNumber + ext, ContentType: ct, Content: res.Body}, nil
}

func (k *carrierImpl) ManagedPickupLocations(ctx context.Context, bearer, ownerOrg string) ([]port.PickupLocation, error) {
	var out struct {
		Items []port.PickupLocation `json:"items"`
	}
	_, err := call(ctx, k.c, httpclient.Request{Path: "/v1/pickup-locations", Bearer: bearer,
		Query: url.Values{"includeInactive": {"true"}, "ownerOrg": {ownerOrg}}}, &out)
	return out.Items, err
}

func (k *carrierImpl) SavePickupLocation(ctx context.Context, bearer string, l port.PickupLocation) (port.PickupLocation, error) {
	var out port.PickupLocation
	_, err := call(ctx, k.c, httpclient.Request{Method: http.MethodPut, Path: "/v1/pickup-locations/" + esc(l.ID), Bearer: bearer, RetryUnsafe: true, JSON: l}, &out)
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

func (p *payoutsImpl) PlanFeeWithholdings(ctx context.Context, bearer, orgID string) ([]port.PlanFeeWithholding, error) {
	res, err := call(ctx, p.c, httpclient.Request{Path: "/v1/orgs/" + esc(orgID) + "/plan-fee-withholdings", Query: url.Values{"limit": {"50"}},
		Bearer: bearer}, nil)
	if err != nil {
		return nil, err
	}
	items, _, err := itemsOf[port.PlanFeeWithholding](res.Body)
	return items, err
}

// ---------------------------------------------------------------- payments: fee schedules (contracts/openapi/payments.yaml)

// FeesScope is the client-credentials scope payments accepts for reading fee schedules on behalf of a service.
const FeesScope = "payments:fees.read"

type feesImpl struct {
	c      httpclient.Client
	tokens port.ServiceTokens
}

var _ port.FeesClient = (*feesImpl)(nil)

// NewFees returns the fee-schedule reader (BFF service token, scope payments:service).
func NewFees(c httpclient.Client, tokens port.ServiceTokens) port.FeesClient {
	return &feesImpl{c: c, tokens: tokens}
}

func (f *feesImpl) token(ctx context.Context) (string, error) {
	if f.tokens == nil {
		return "", errors.New("fees: no service token source")
	}
	t, err := f.tokens.Token(ctx, FeesScope)
	if err != nil {
		return "", fmt.Errorf("fees: %w", err)
	}
	return t, nil
}

func (f *feesImpl) Schedules(ctx context.Context) ([]port.FeeScheduleSummary, error) {
	t, err := f.token(ctx)
	if err != nil {
		return nil, err
	}
	var out []port.FeeScheduleSummary
	_, err = call(ctx, f.c, httpclient.Request{Path: "/v1/fee-schedules", Bearer: t}, &out)
	return out, err
}

func (f *feesImpl) Effective(ctx context.Context, category, orgID string, at time.Time) (port.EffectiveFeesResponse, error) {
	return f.EffectiveKind(ctx, "", category, orgID, at)
}

func (f *feesImpl) RateCard(ctx context.Context, orgID, kind string, at time.Time) (port.RateCardResponse, error) {
	t, err := f.token(ctx)
	if err != nil {
		return port.RateCardResponse{}, err
	}
	q := url.Values{}
	if kind != "" {
		q.Set("kind", kind)
	}
	if !at.IsZero() {
		q.Set("at", at.UTC().Format(time.RFC3339))
	}
	var out port.RateCardResponse
	_, err = call(ctx, f.c, httpclient.Request{Path: "/v1/orgs/" + url.PathEscape(orgID) + "/rate-card", Query: q, Bearer: t}, &out)
	return out, err
}

func (f *feesImpl) EffectiveKind(ctx context.Context, kind, category, orgID string, at time.Time) (port.EffectiveFeesResponse, error) {
	t, err := f.token(ctx)
	if err != nil {
		return port.EffectiveFeesResponse{}, err
	}
	q := url.Values{"category": {category}, "orgId": {orgID}}
	if kind != "" {
		q.Set("kind", kind)
	}
	if !at.IsZero() {
		q.Set("at", at.UTC().Format(time.RFC3339))
	}
	var out port.EffectiveFeesResponse
	_, err = call(ctx, f.c, httpclient.Request{Path: "/v1/fee-schedules/effective", Query: q, Bearer: t}, &out)
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
