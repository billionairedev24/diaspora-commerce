package httpapi_test

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

type verifier struct{}

func (verifier) Verify(_ context.Context, token string) (auth.Actor, error) {
	switch token {
	case "ops":
		return auth.Actor{Subject: "svc-orders", Type: "SERVICE", OrgID: "org_ops", Permissions: []string{app.PermRate, app.PermLabel}}, nil
	case "viewer":
		return auth.Actor{Subject: "u", Type: "CUSTOMER"}, nil
	default:
		return auth.Actor{}, auth.ErrUnauthenticated
	}
}

type fakeRates struct{ got app.RateRequest }

func (f *fakeRates) Shop(_ context.Context, r app.RateRequest) (app.RateResult, error) {
	f.got = r
	if r.Shipment.OriginNode == "down" {
		return app.RateResult{}, &domain.CarrierError{Carrier: domain.UPS, Status: 503, Kind: domain.ErrCarrierUnavailable}
	}
	return app.RateResult{Quotes: []domain.Quote{{Carrier: domain.USPS, Service: "PRIORITY_MAIL", AmountMinor: 1260}}, Partial: true}, nil
}

type fakeLabels struct{ got app.BuyRequest }

func (f *fakeLabels) Buy(_ context.Context, r app.BuyRequest) (app.LabelView, error) {
	f.got = r
	if r.IdempotencyKey == "" {
		return app.LabelView{}, domain.ErrInvalid
	}
	if r.Service == "BAD" {
		return app.LabelView{}, &domain.CarrierError{Carrier: domain.UPS, Code: "111210", Message: "service unavailable", Kind: domain.ErrCarrierRejected}
	}
	return app.LabelView{LabelRecord: domain.LabelRecord{ID: "l1", TrackingNumber: "1Z", Status: domain.LabelPurchased}}, nil
}

func (f *fakeLabels) Get(context.Context, string) (app.LabelView, error) {
	return app.LabelView{}, domain.ErrNotFound
}

func (f *fakeLabels) Document(context.Context, string) ([]byte, string, error) {
	return []byte("^XA^XZ"), "application/x-zpl", nil
}

func (f *fakeLabels) Void(context.Context, string) (app.LabelView, error) {
	return app.LabelView{}, domain.ErrConflict
}

type fakeTracking struct {
	app.TrackingService
	body []byte
}

func (f *fakeTracking) HandleWebhook(_ context.Context, c domain.CarrierCode, h http.Header, body []byte) (app.IngestResult, error) {
	f.body = body
	if h.Get("fdx-signature") != "good" {
		return app.IngestResult{}, domain.ErrSignature
	}
	return app.IngestResult{Accepted: 2}, nil
}

func server(t *testing.T) (*httptest.Server, *fakeRates, *fakeLabels, *fakeTracking) {
	t.Helper()
	rates, labels, tracking := &fakeRates{}, &fakeLabels{}, &fakeTracking{}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Rates: rates, Labels: labels, Tracking: tracking}).
		Register(mux, auth.Middleware(verifier{}, true), auth.Middleware(verifier{}, false))
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, rates, labels, tracking
}

type resp struct {
	StatusCode int
	Header     http.Header
}

func call(t *testing.T, srv *httptest.Server, method, path, token, body string, hdr map[string]string) (resp, map[string]any) {
	t.Helper()
	req, _ := http.NewRequestWithContext(context.Background(), method, srv.URL+path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	res, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = res.Body.Close() }()
	raw, _ := io.ReadAll(res.Body)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return resp{StatusCode: res.StatusCode, Header: res.Header}, out
}

const rateBody = `{"cartId":"c1","shipment":{"originNode":"fc_houston_tx","shipDate":"2026-10-12",
 "from":{"line1":"1 Main","city":"Houston","postalCode":"77002","country":"US"},
 "to":{"line1":"2 Court","city":"Brooklyn","postalCode":"11201","country":"US"},"parcels":[{"weightLb":2}]}}`

func TestRatesRequiresPermission(t *testing.T) {
	srv, rates, _, _ := server(t)
	if res, _ := call(t, srv, "POST", "/v1/rates", "", rateBody, nil); res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("anonymous: %d", res.StatusCode)
	}
	if res, body := call(t, srv, "POST", "/v1/rates", "viewer", rateBody, nil); res.StatusCode != http.StatusForbidden || body["code"] != "auth.missing-permission" {
		t.Fatalf("no permission: %d %v", res.StatusCode, body)
	}
	res, body := call(t, srv, "POST", "/v1/rates", "ops", rateBody, nil)
	if res.StatusCode != http.StatusOK || body["partial"] != true || rates.got.CartID != "c1" || rates.got.Shipment.ShipDate.Day() != 12 {
		t.Fatalf("%d %v %+v", res.StatusCode, body, rates.got)
	}
	bad := strings.Replace(rateBody, "2026-10-12", "12/10/2026", 1)
	if res, _ := call(t, srv, "POST", "/v1/rates", "ops", bad, nil); res.StatusCode != http.StatusBadRequest {
		t.Fatalf("bad date: %d", res.StatusCode)
	}
	down := strings.Replace(rateBody, "fc_houston_tx", "down", 1)
	res, body = call(t, srv, "POST", "/v1/rates", "ops", down, nil)
	if res.StatusCode != http.StatusServiceUnavailable || res.Header.Get("Retry-After") == "" || body["code"] != "carrier-gateway.carrier-unavailable" {
		t.Fatalf("unavailable: %d %v", res.StatusCode, body)
	}
}

func TestLabelEndpoints(t *testing.T) {
	srv, _, labels, _ := server(t)
	body := `{"carrier":"ups","service":"03","shipment":` + strings.TrimSuffix(strings.SplitN(rateBody, `"shipment":`, 2)[1], "}") + `}`
	res, out := call(t, srv, "POST", "/v1/labels", "ops", body, map[string]string{"Idempotency-Key": "k-1"})
	if res.StatusCode != http.StatusCreated || out["trackingNumber"] != "1Z" || labels.got.IdempotencyKey != "k-1" || labels.got.Caller != "org_ops" {
		t.Fatalf("%d %v %+v", res.StatusCode, out, labels.got)
	}
	if res, _ := call(t, srv, "POST", "/v1/labels", "ops", body, nil); res.StatusCode != http.StatusBadRequest {
		t.Fatalf("missing key: %d", res.StatusCode)
	}
	rejected := strings.Replace(body, `"service":"03"`, `"service":"BAD"`, 1)
	res, out = call(t, srv, "POST", "/v1/labels", "ops", rejected, map[string]string{"Idempotency-Key": "k-2"})
	if res.StatusCode != http.StatusUnprocessableEntity || out["code"] != "carrier-gateway.carrier-rejected" || !strings.Contains(out["detail"].(string), "111210") {
		t.Fatalf("rejected: %d %v", res.StatusCode, out)
	}
	if res, _ := call(t, srv, "GET", "/v1/labels/nope", "ops", "", nil); res.StatusCode != http.StatusNotFound {
		t.Fatalf("not found: %d", res.StatusCode)
	}
	if res, _ := call(t, srv, "POST", "/v1/labels/l1/void", "ops", "", nil); res.StatusCode != http.StatusForbidden {
		t.Fatalf("void needs shipping:void: %d", res.StatusCode)
	}
	if res, _ := call(t, srv, "GET", "/v1/labels/l1/document", "ops", "", nil); res.StatusCode != http.StatusOK || res.Header.Get("Content-Type") != "application/x-zpl" {
		t.Fatalf("document: %d %s", res.StatusCode, res.Header.Get("Content-Type"))
	}
}

func TestWebhookUsesSignatureNotBearer(t *testing.T) {
	srv, _, _, tracking := server(t)
	payload := `{"output":{"completeTrackResults":[]}}`
	res, out := call(t, srv, "POST", "/v1/webhooks/fedex", "", payload, map[string]string{"fdx-signature": "bad"})
	if res.StatusCode != http.StatusUnauthorized || out["code"] != "carrier-gateway.webhook-unverified" {
		t.Fatalf("invalid signature: %d %v", res.StatusCode, out)
	}
	res, out = call(t, srv, "POST", "/v1/webhooks/fedex", "", payload, map[string]string{"fdx-signature": "good"})
	if res.StatusCode != http.StatusOK || out["accepted"] != 2.0 || string(tracking.body) != payload {
		t.Fatalf("valid: %d %v raw=%q", res.StatusCode, out, tracking.body)
	}
	if res, _ := call(t, srv, "POST", "/v1/webhooks/acme", "", payload, nil); res.StatusCode != http.StatusNotFound {
		t.Fatalf("unknown carrier: %d", res.StatusCode)
	}
}
