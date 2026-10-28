package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

const contractPath = "../../../../../../contracts/openapi/gateway-bff.yaml"

// TestEmbeddedContractIsIdentical keeps api/openapi.yaml (served at /openapi.yaml and /docs) equal to the contract.
func TestEmbeddedContractIsIdentical(t *testing.T) {
	want, err := os.ReadFile(contractPath)
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(filepath.Join("..", "..", "..", "api", "openapi.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("api/openapi.yaml differs from contracts/openapi/gateway-bff.yaml; copy the contract")
	}
}

func loadRouter(t *testing.T) routers.Router {
	t.Helper()
	loader := openapi3.NewLoader()
	doc, err := loader.LoadFromFile(contractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	r, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatalf("router: %v", err)
	}
	return r
}

// TestContractConformance drives the main operations (success and documented error answers) through the real
// handlers, backed by fake upstreams, and validates every response against contracts/openapi/gateway-bff.yaml.
func TestContractConformance(t *testing.T) {
	router := loadRouter(t)
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := map[string]int{}
	b.onResponse = func(req *http.Request, rec *httptest.ResponseRecorder) {
		route, params, err := router.FindRoute(req)
		if err != nil {
			return // e.g. the registered identity redirect URI, which is not part of the contract
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document: %s", req.Method, req.URL.Path, rec.Code, rec.Body.Bytes())
			return
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", req.Method, req.URL.Path, rec.Code, err, rec.Body.Bytes())
			return
		}
		validated[route.Operation.OperationID]++
	}

	// Site, location, search, catalogue (guest).
	b.do("GET", "/v1/site-config", nil)
	b.do("GET", "/v1/home", nil)
	b.do("POST", "/v1/newsletter/subscriptions", map[string]any{"email": "ada@example.com"})
	b.do("POST", "/v1/newsletter/subscriptions", map[string]any{"email": "nope"})
	b.do("GET", "/v1/delivery-location", nil)
	b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "77042"})
	b.do("PUT", "/v1/delivery-location", map[string]any{"zip": "99999"})
	b.do("GET", "/v1/geo/address-suggestions?q=9800", nil)
	b.do("GET", "/v1/geo/address-suggestions?q=9800&sessionToken=no%20spaces", nil)
	b.do("GET", "/v1/geo/address-suggestions/g%3AChIJpeckham?sessionToken=tok_12345678", nil)
	b.do("GET", "/v1/geo/address-suggestions/o%3A1?sessionToken=x", nil)
	b.do("GET", "/v1/search?q=shea&sort=price_asc&page=0&size=12", nil)
	b.do("GET", "/v1/search?sort=nope", nil)
	b.do("GET", "/v1/search/suggestions?q=jol", nil)
	b.do("GET", "/v1/products/ATSHEA0001", nil)
	b.do("GET", "/v1/products/ATGARI0001", nil)
	b.do("GET", "/v1/products/ATNOPE0000", nil)
	b.do("POST", "/v1/products/ATNOPE0000/stock-alerts", map[string]any{"email": "ada@example.com"})
	b.do("POST", "/v1/products/ATGARI0001/stock-alerts", map[string]any{})
	b.do("POST", "/v1/products/ATGARI0001/stock-alerts", map[string]any{"email": "ada@example.com"})
	b.do("GET", "/v1/products/ATSHEAV001", nil)
	b.do("PUT", "/v1/delivery-location", map[string]any{"lat": 29.7376, "lon": -95.5134})
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": testkit.OfferAccraSheaID, "quantity": 1})
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": testkit.OfferPausedID, "quantity": 1})
	// Cart.
	b.do("GET", "/v1/cart", nil)
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEA0001", "quantity": 2})
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 2})
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 2})
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATGARIV001", "quantity": 1})
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATZZZZZZZZ", "quantity": 1})
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 0})
	b.do("PATCH", "/v1/cart/items/ATSHEAV001", map[string]any{"selected": false})
	b.do("PATCH", "/v1/cart/items/ATJOLLOFV1", map[string]any{"quantity": 9})
	b.do("PATCH", "/v1/cart/items/ATNOPE0000", map[string]any{"quantity": 1})
	b.do("PUT", "/v1/cart/selection", map[string]any{"selected": true})
	b.do("POST", "/v1/cart/items/ATSHEAV001/save-for-later", nil)
	b.do("POST", "/v1/cart/items/ATNOPE0000/save-for-later", nil)
	b.do("POST", "/v1/cart/saved/ATSHEAV001/move-to-cart", nil)
	b.do("POST", "/v1/cart/saved/ATNOPE0000/move-to-cart", nil)
	b.do("DELETE", "/v1/cart/saved/ATNOPE0000", nil)
	b.do("DELETE", "/v1/cart/items/ATJOLLOFV1", nil)
	b.do("DELETE", "/v1/cart/items/ATJOLLOFV1", nil)
	// Auth.
	b.do("GET", "/v1/me", nil)
	b.do("GET", "/v1/auth/login?returnTo=/cart", nil)
	b.do("GET", "/v1/auth/oidc/google/authorize", nil)
	b.do("GET", "/v1/auth/login?returnTo=/account&prompt=create&login_hint=ada%40example.com&invitation=inv-1", nil)
	b.do("GET", "/v1/auth/login?returnTo="+url.QueryEscape(testkit.SellerCenterOrigin+"/orders"), nil)
	b.do("POST", "/v1/auth/sign-out", nil)
	b.do("POST", "/v1/auth/sign-out", map[string]any{"returnTo": "/deals"})
	b.do("GET", "/v1/auth/signed-out/storefront?state=%2Fdeals", nil)
	b.do("GET", "/v1/auth/signed-out/seller-center", nil)
	b.do("GET", "/v1/auth/signed-out/nope", nil)
	// Account (signed in).
	b.do("GET", "/v1/account/addresses", nil)
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	b.do("GET", "/v1/me", nil)
	b.do("POST", "/v1/account/addresses", map[string]any{"name": "Adaeze Nwosu", "phone": "+17135550142", "line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "zip": "77042"})
	b.do("POST", "/v1/account/addresses", map[string]any{"name": "Adaeze Nwosu", "phone": "+17135550142", "line1": "1 Nowhere Ln", "city": "Houston", "state": "TX", "zip": "77042"})
	b.do("GET", "/v1/account/addresses", nil)
	b.do("DELETE", "/v1/account/addresses/nope", nil)
	b.do("GET", "/v1/account/payment-methods", nil)
	b.do("POST", "/v1/account/phone/verification/confirm", map[string]any{"code": "12"})
	b.do("POST", "/v1/account/phone/verification", nil)
	b.do("POST", "/v1/account/phone/verification/confirm", map[string]any{"code": "000000"})
	b.do("POST", "/v1/account/phone/verification/confirm", map[string]any{"code": testkit.PhoneCode})
	b.do("POST", "/v1/account/phone/verification", nil)
	b.do("GET", "/v1/me", nil)
	b.do("POST", "/v1/products/ATGARI0001/stock-alerts", map[string]any{})
	b.do("GET", "/v1/account/stock-alerts", nil)
	b.do("DELETE", "/v1/account/stock-alerts/ATGARIV001", nil)
	b.do("DELETE", "/v1/account/stock-alerts/ATGARIV001", nil)
	b.do("POST", "/v1/account/payment-methods/setup-intents", nil)
	b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATSHEAV001", "quantity": 1})
	b.do("GET", "/v1/checkout/summary", nil)
	b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "", "deliverySelections": []any{}})
	// Careers.
	b.do("GET", "/v1/careers/jobs", nil)
	b.do("POST", "/v1/careers/uploads", map[string]any{"fileName": "cv.pdf", "contentType": "application/pdf", "size": 120000})
	b.do("POST", "/v1/careers/uploads", map[string]any{"fileName": "cv.docx", "contentType": "application/pdf", "size": 9000000})
	b.do("POST", "/v1/careers/uploads/0b0b0b0b-0000-4000-8000-000000000001/complete", nil)
	b.do("POST", "/v1/careers/jobs/eng-1/applications", map[string]any{"name": "Ada Obi", "email": "ada@example.com", "linkedin": "", "workAuthorization": "Yes", "resumeAssetId": "ast_123", "resumeFileName": "cv.pdf", "consent": true})
	b.do("POST", "/v1/careers/jobs/eng-9/applications", map[string]any{"name": "Ada Obi", "email": "ada@example.com", "linkedin": "", "workAuthorization": "Yes", "resumeAssetId": "ast_123", "resumeFileName": "cv.pdf", "consent": true})
	b.do("POST", "/v1/auth/sign-out", nil)

	for _, op := range []string{"getSiteConfig", "getHome", "subscribeNewsletter", "getDeliveryLocation", "setDeliveryLocation",
		"suggestAddresses", "search", "suggest", "getProduct", "createStockAlert", "getCart", "addToCart", "updateCartLine",
		"removeCartLine", "selectAllCartLines", "saveForLater", "moveToCart", "removeSaved", "getMe", "startHostedLogin",
		"startSocialLogin", "signOut", "signedOut", "listAddresses",
		"createAddress", "deleteAddress", "listPaymentMethods", "createSetupIntent", "getCheckoutSummary", "placeOrder",
		"listJobs", "applyToJob", "listStockAlerts", "removeStockAlert",
		"requestResumeUpload", "completeResumeUpload", "startPhoneVerification", "confirmPhoneVerification"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
	t.Logf("validated responses per operation: %v", validated)
}

// TestConformanceValidatorRejectsDrift proves the validator is strict: a cart without its required members fails.
func TestConformanceValidatorRejectsDrift(t *testing.T) {
	router := loadRouter(t)
	req := httptest.NewRequestWithContext(context.Background(), "GET", "http://bff.internal/v1/cart", nil)
	route, params, err := router.FindRoute(req)
	if err != nil {
		t.Fatal(err)
	}
	in := &openapi3filter.ResponseValidationInput{
		RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route},
		Status:                 200, Header: http.Header{"Content-Type": {"application/json"}},
		Body: io.NopCloser(bytes.NewBufferString(`{"cartId":"c","currency":"USD","packages":[],"saved":[]}`)),
	}
	if err := openapi3filter.ValidateResponse(context.Background(), in); err == nil {
		t.Fatal("an incomplete cart must not validate")
	}
}
