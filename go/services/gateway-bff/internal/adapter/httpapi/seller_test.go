package httpapi_test

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

const sellerContractPath = "../../../../../../contracts/openapi/gateway-bff-seller.yaml"

// TestEmbeddedSellerContractIsIdentical keeps api/openapi-seller.yaml (served at /openapi-seller.yaml) equal to the contract.
func TestEmbeddedSellerContractIsIdentical(t *testing.T) {
	want, err := os.ReadFile(sellerContractPath)
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(filepath.Join("..", "..", "..", "api", "openapi-seller.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("api/openapi-seller.yaml differs from contracts/openapi/gateway-bff-seller.yaml; copy the contract")
	}
}

func sellerSetup(t *testing.T, o testkit.Options) (*testkit.BFF, *browser) {
	t.Helper()
	bff, b := setup(t, o)
	b.origin = testkit.SellerCenterOrigin
	mustStatus(t, signIn(t, bff, b, "/"), 302)
	return bff, b
}

func moduleKeys(t *testing.T, session map[string]any) []string {
	t.Helper()
	var keys []string
	for _, g := range session["navGroups"].([]any) {
		for _, m := range g.(map[string]any)["modules"].([]any) {
			keys = append(keys, m.(map[string]any)["key"].(string))
		}
	}
	return keys
}

// TestSellerContractConformance drives every seller operation through the real handlers (fake upstreams, including
// the seller routes of services still being built) and validates each answer against gateway-bff-seller.yaml.
func TestSellerContractConformance(t *testing.T) {
	loader := openapi3.NewLoader()
	doc, err := loader.LoadFromFile(sellerContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	if err := doc.Validate(context.Background()); err != nil {
		t.Fatalf("contract is invalid: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	validated := map[string]int{}
	check := func(req *http.Request, rec *httptest.ResponseRecorder) {
		route, params, err := router.FindRoute(req)
		if err != nil {
			return
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

	// A guest first: every seller route needs a signed-in member.
	_, guest := setup(t, testkit.Options{SellerPending: true})
	guest.onResponse = check
	mustStatus(t, guest.do("GET", "/v1/seller/session", nil), 401)

	bff, b := sellerSetup(t, testkit.Options{SellerPending: true})
	b.onResponse = check
	orderPath := "/v1/seller/orders/AT-2026-000123/shipments/sh-1"
	from := map[string]any{"name": "Ade's Kitchen", "line1": "1 Bellaire Blvd", "city": "Houston", "state": "TX", "postalCode": "77036", "country": "US"}
	parcel := map[string]any{"weightLb": 5.5, "lengthIn": 12, "widthIn": 10, "heightIn": 6}

	// Shell (seller org).
	mustStatus(t, b.do("GET", "/v1/seller/session", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/compliance", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/onboarding", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/dashboard?rangeDays=30", nil), 200)
	// Orders: queue, sheet, status flow, rates and label.
	mustStatus(t, b.do("GET", "/v1/seller/orders?limit=20", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/orders?status=SHIPPED_NOPE", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/orders/AT-2026-000123", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/orders/AT-0000", nil), 404)
	mustStatus(t, b.do("POST", orderPath+"/status", map[string]any{"status": "DELIVERED"}), 422)
	mustStatus(t, b.do("POST", orderPath+"/status", map[string]any{"status": "PACKED"}), 409)
	mustStatus(t, b.do("POST", orderPath+"/status", map[string]any{"status": "CONFIRMED"}), 200)
	mustStatus(t, b.do("POST", orderPath+"/rates", map[string]any{"parcel": parcel, "shipFrom": from}), 200)
	mustStatus(t, b.do("POST", orderPath+"/rates", map[string]any{"parcel": map[string]any{"weightLb": 0}, "shipFrom": from}), 422)
	mustStatus(t, b.do("POST", orderPath+"/labels", map[string]any{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "parcel": parcel, "shipFrom": from}), 422)
	b.headers["Idempotency-Key"] = "label-0123456789"
	mustStatus(t, b.do("POST", orderPath+"/labels", map[string]any{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "parcel": parcel, "shipFrom": from}), 201)
	mustStatus(t, b.do("POST", orderPath+"/labels", map[string]any{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "parcel": parcel, "shipFrom": from}), 409)
	delete(b.headers, "Idempotency-Key")
	// Listings and the product builder.
	mustStatus(t, b.do("GET", "/v1/seller/listings?page=0&size=25", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/listings?status=NOPE", nil), 422)
	mustStatus(t, b.do("POST", "/v1/seller/listings/prod-1/status", map[string]any{"active": false}), 200)
	mustStatus(t, b.do("GET", "/v1/seller/catalog/categories", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/catalog/product-types/grocery", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/catalog/product-types/nope", nil), 404)
	mustStatus(t, b.do("GET", "/v1/seller/catalog/gtin/4006381333931", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/category-requests", nil), 200)
	mustStatus(t, b.do("POST", "/v1/seller/category-requests", map[string]any{"parentPath": "Fashion › Women", "name": "Bridal aso-ebi sets",
		"exampleProduct": "6-piece aso-ebi set", "detailsNeeded": "Pieces, colour, fabric"}), 201)
	mustStatus(t, b.do("POST", "/v1/seller/category-requests", map[string]any{"parentPath": "", "name": "x", "exampleProduct": "", "detailsNeeded": ""}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/uploads", map[string]any{"purpose": "product-image", "fileName": "ofada.webp", "contentType": "image/webp", "size": 182000}), 201)
	mustStatus(t, b.do("POST", "/v1/seller/uploads", map[string]any{"purpose": "product-image", "fileName": "ofada.gif", "contentType": "image/gif", "size": 182000}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/uploads/asset-1/complete", nil), 200)
	draft := map[string]any{"categoryId": "cat-grocery", "title": "Ofada Rice, Destoned · 5 lb", "attributes": map[string]string{"brand": "Ade's"},
		"images":         []map[string]any{{"url": "https://img.example/asset-1.webp", "contentType": "image/webp", "width": 1600, "height": 1600}},
		"variationTheme": "Net weight", "variants": []map[string]any{{"options": map[string]string{"Net weight": "5 lb"}, "sellerSku": "OFADA-5LB", "gtin": "4006381333931", "priceMinor": 2499, "quantity": 40}}}
	mustStatus(t, b.do("POST", "/v1/seller/products", draft), 201)
	delete(draft, "categoryId")
	draft["categoryRequestId"] = "cr-2"
	if r := b.do("POST", "/v1/seller/products", draft); r.status != 201 || r.json(t)["status"] != "PENDING_CATEGORY_APPROVAL" {
		t.Fatalf("submit pending category = %d %s", r.status, r.body)
	}
	draft["title"] = "short"
	mustStatus(t, b.do("POST", "/v1/seller/products", draft), 422)
	mustStatus(t, b.do("GET", "/v1/seller/inventory", nil), 200)
	mustStatus(t, b.do("POST", "/v1/seller/inventory/adjustments", map[string]any{"sku": "OFADA-5LB", "quantity": 30, "reason": "STOCK_TAKE"}), 503)
	mustStatus(t, b.do("POST", "/v1/seller/inventory/adjustments", map[string]any{"sku": "", "quantity": -1, "reason": "?"}), 422)
	// Money.
	if r := b.do("GET", "/v1/seller/payouts", nil); r.status != 200 || r.json(t)["historyAvailable"] != false {
		t.Fatalf("payouts = %d %s", r.status, r.body)
	} else if bal := r.json(t)["balance"].(map[string]any); bal["payoutsPaused"] != true || bal["payoutsPausedReason"] != "AUTO_PAST_DUE" ||
		bal["canRequestToday"] != false || num(bal, "providerFeesMinor") != 1500 || bal["payoutsPausedMessage"] == nil {
		t.Fatalf("paused balance = %v", bal)
	}
	mustStatus(t, b.do("POST", "/v1/seller/payouts", map[string]any{"all": true}), 201)
	mustStatus(t, b.do("POST", "/v1/seller/payouts", map[string]any{"amountMinor": 100}), 422)
	// Fees (read-only): commission per category with the org's own overrides, FBA and card fees, the upcoming change.
	if r := b.do("GET", "/v1/seller/fees", nil); r.status != 200 {
		t.Fatalf("fees = %d %s", r.status, r.body)
	} else {
		v := r.json(t)
		cur := v["current"].(map[string]any)
		cats := map[string]string{}
		for _, c := range cur["categories"].([]any) {
			m := c.(map[string]any)
			cats[m["category"].(string)] = fmt.Sprintf("%v/%v", m["commissionBps"], m["source"])
		}
		if cats["groceries"] != "600/OVERRIDE" || cats["fashion"] != "1200/CATEGORY" || cats["services"] != "1000/DEFAULT" || cats["beauty"] != "" ||
			num(cur, "fbaPerUnitMinor") != 325 || num(cur, "cardPctBps") != 290 || v["retail"] != false {
			t.Fatalf("current fees = %v", cur)
		}
		up := v["upcoming"].(map[string]any)
		if up["effectiveFrom"] != testkit.FeeScheduleUpcoming || num(up, "version") != 2 {
			t.Fatalf("upcoming = %v", up)
		}
		changes := []string{}
		for _, c := range v["changes"].([]any) {
			changes = append(changes, c.(map[string]any)["field"].(string))
		}
		if strings.Join(changes, ",") != "category:fashion,fbaPerUnitMinor" {
			t.Fatalf("changes = %v", v["changes"])
		}
	}
	mustStatus(t, b.do("GET", "/v1/seller/billing", nil), 200)
	mustStatus(t, b.do("POST", "/v1/seller/billing/plan", map[string]any{"planCode": "seller_pro", "cadence": "ANNUAL"}), 200)
	mustStatus(t, b.do("POST", "/v1/seller/billing/plan", map[string]any{"planCode": "seller_pro", "cadence": "WEEKLY"}), 422)
	mustStatus(t, b.do("DELETE", "/v1/seller/billing/payment-methods/aaaaaaaa-0000-0000-0000-000000000001", nil), 409)
	mustStatus(t, b.do("POST", "/v1/seller/billing/payment-methods/aaaaaaaa-0000-0000-0000-000000000002/default", nil), 200)
	mustStatus(t, b.do("DELETE", "/v1/seller/billing/payment-methods/aaaaaaaa-0000-0000-0000-000000000001", nil), 200)
	// Team.
	mustStatus(t, b.do("GET", "/v1/seller/team", nil), 200)
	invited := b.do("POST", "/v1/seller/team/invitations", map[string]any{"email": "kemi@example.com", "roleKey": "seller.fulfilment"})
	mustStatus(t, invited, 201)
	mustStatus(t, b.do("POST", "/v1/seller/team/invitations", map[string]any{"email": testkit.AlreadyMemberEmail, "roleKey": "seller.fulfilment"}), 409)
	mustStatus(t, b.do("POST", "/v1/seller/team/invitations", map[string]any{"email": "nope", "roleKey": "seller.fulfilment"}), 422)
	mustStatus(t, b.do("PUT", "/v1/seller/team/members/"+testkit.TeamMemberID, map[string]any{"roleKey": "seller.catalog"}), 200)
	mustStatus(t, b.do("PUT", "/v1/seller/team/members/"+testkit.TeamMemberID, map[string]any{"roleKey": "provider.manager"}), 422)
	mustStatus(t, b.do("DELETE", "/v1/seller/team/invitations/"+invited.json(t)["invitationId"].(string), nil), 200)
	mustStatus(t, b.do("DELETE", "/v1/seller/team/invitations/00000000-0000-4000-8000-000000000000", nil), 404)
	mustStatus(t, b.do("DELETE", "/v1/seller/team/members/"+testkit.TeamMemberID, nil), 200)
	mustStatus(t, b.do("POST", "/v1/seller/invitations/lookup", map[string]any{"token": testkit.InviteTokenValid}), 200)
	mustStatus(t, b.do("POST", "/v1/seller/invitations/lookup", map[string]any{"token": "nope"}), 404)
	mustStatus(t, b.do("POST", "/v1/seller/invitations/lookup", map[string]any{"token": ""}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/invitations/accept", map[string]any{"token": testkit.InviteTokenExpired}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/invitations/accept", map[string]any{"token": testkit.InviteTokenValid}), 200)
	// Storefront (seller).
	mustStatus(t, b.do("GET", "/v1/seller/storefront", nil), 200)
	// Ship-from addresses live in the seller service (no more browser storage).
	if r := b.do("GET", "/v1/seller/ship-from-addresses", nil); r.status != 200 || len(r.json(t)["items"].([]any)) != 0 {
		t.Fatalf("ship-from list = %d %s", r.status, r.body)
	}
	home := map[string]any{"label": "Houston", "name": "Ade Okafor", "line1": "4521 Main St", "city": "Houston", "state": "tx", "postalCode": "77002"}
	first := b.do("POST", "/v1/seller/ship-from-addresses", home)
	mustStatus(t, first, 201)
	if a := first.json(t); a["isDefault"] != true || a["state"] != "TX" || a["country"] != "US" {
		t.Fatalf("ship-from = %v", a)
	}
	home["postalCode"] = "7700"
	mustStatus(t, b.do("POST", "/v1/seller/ship-from-addresses", home), 422)
	home["postalCode"], home["label"] = "77494", "Katy"
	second := b.do("POST", "/v1/seller/ship-from-addresses", home).json(t)
	secondID := second["id"].(string)
	home["version"] = 7
	mustStatus(t, b.do("PUT", "/v1/seller/ship-from-addresses/"+secondID, home), 409)
	home["version"] = 0
	mustStatus(t, b.do("PUT", "/v1/seller/ship-from-addresses/"+secondID, home), 200)
	if r := b.do("POST", "/v1/seller/ship-from-addresses/"+secondID+"/default", nil); r.status != 200 ||
		r.json(t)["items"].([]any)[0].(map[string]any)["id"] != secondID {
		t.Fatalf("default = %d %s", r.status, r.body)
	}
	if r := b.do("DELETE", "/v1/seller/ship-from-addresses/"+secondID, nil); r.status != 200 || len(r.json(t)["items"].([]any)) != 1 {
		t.Fatalf("delete = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("DELETE", "/v1/seller/ship-from-addresses/nope", nil), 404)

	// Provider Hub needs a provider organisation.
	mustStatus(t, b.do("GET", "/v1/seller/bookings/requests", nil), 403)
	mustStatus(t, b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": "nope"}), 403)
	mustStatus(t, b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": ""}), 422)
	mustStatus(t, b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": testkit.ProviderOrgID}), 200)
	mustStatus(t, b.do("GET", "/v1/seller/dashboard?rangeDays=7", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/dashboard?rangeDays=12", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/bookings/calendar?from=2026-10-12&to=2026-10-18", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/bookings/calendar?from=2026-10-18&to=2026-10-12", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/bookings/requests", nil), 200)
	mustStatus(t, b.do("POST", "/v1/seller/bookings/24018/actions", map[string]any{"action": "CONFIRM"}), 200)
	mustStatus(t, b.do("POST", "/v1/seller/bookings/24018/actions", map[string]any{"action": "DECLINE"}), 422)
	mustStatus(t, b.do("GET", "/v1/seller/provider/hours", nil), 200)
	week := []map[string]any{}
	for _, d := range []string{"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"} {
		week = append(week, map[string]any{"day": d, "ranges": []map[string]string{{"start": "10:00", "end": "14:00"}, {"start": "14:30", "end": "18:00"}}})
	}
	rules := map[string]any{"leadMinutes": 720, "horizonDays": 30, "bufferMinutes": 15, "stepMinutes": 30, "maxPerDay": 6, "instantBooking": false, "autoAcceptReschedule": true}
	mustStatus(t, b.do("PUT", "/v1/seller/provider/hours", map[string]any{"timezone": "America/Chicago", "weekly": week, "blockedDates": []any{}, "rules": rules}), 200)
	week[0]["ranges"] = []map[string]string{{"start": "10:00", "end": "14:00"}, {"start": "13:00", "end": "18:00"}}
	mustStatus(t, b.do("PUT", "/v1/seller/provider/hours", map[string]any{"timezone": "America/Chicago", "weekly": week, "blockedDates": []any{}, "rules": rules}), 422)
	mustStatus(t, b.do("GET", "/v1/seller/provider/services", nil), 200)
	svc := map[string]any{"name": "Cornrows · 8–10 rows", "description": "", "durationMinutes": 120, "priceMinor": 7500, "depositMinor": 2000, "active": true}
	mustStatus(t, b.do("POST", "/v1/seller/provider/services", svc), 201)
	mustStatus(t, b.do("PATCH", "/v1/seller/provider/services/s1a", svc), 200)
	svc["depositMinor"] = 9000
	mustStatus(t, b.do("POST", "/v1/seller/provider/services", svc), 422)
	mustStatus(t, b.do("GET", "/v1/seller/provider/cancellation-policy", nil), 200)
	mustStatus(t, b.do("PUT", "/v1/seller/provider/cancellation-policy", map[string]any{"kind": "CUSTOM", "freeHours": 48, "partialRefund": true,
		"partialFromHours": 24, "partialPercent": 50, "noShow": "FULL", "rescheduleHours": 24, "maxReschedules": 2}), 200)
	mustStatus(t, b.do("PUT", "/v1/seller/provider/cancellation-policy", map[string]any{"kind": "CUSTOM", "freeHours": 24, "partialRefund": true,
		"partialFromHours": 48, "partialPercent": 50, "noShow": "DEPOSIT", "rescheduleHours": 24, "maxReschedules": 2}), 422)
	if r := b.do("GET", "/v1/seller/provider/earnings", nil); r.status != 200 {
		t.Fatalf("earnings = %d %s", r.status, r.body)
	} else if fees := r.json(t)["providerFees"].(map[string]any); fees["available"] != true || num(fees, "outstandingMinor") != 1500 || num(fees, "collectedMinor") != 1500 {
		t.Fatalf("provider fees = %v", fees)
	}
	mustStatus(t, b.do("GET", "/v1/seller/provider/earnings?from=bad", nil), 422)
	// Provider routes map onto bookings' /v1/providers/{orgId}/… with the active organisation.
	for _, call := range []string{"GET /v1/providers/" + testkit.ProviderOrgID + "/calendar", "GET /v1/providers/" + testkit.ProviderOrgID + "/bookings",
		"GET /v1/providers/" + testkit.ProviderOrgID + "/settings", "PUT /v1/providers/" + testkit.ProviderOrgID + "/team/m-1/hours",
		"GET /v1/providers/" + testkit.ProviderOrgID + "/earnings", "POST /v1/bookings/24018/confirm"} {
		if bff.Upstreams.CallCount(call) == 0 {
			t.Errorf("bookings was not called with %s", call)
		}
	}
	mustStatus(t, b.do("GET", "/v1/seller/storefront", nil), 200)
	mustStatus(t, b.do("PUT", "/v1/seller/storefront", map[string]any{"tagline": "Neat, long-lasting braids", "layout": "GALLERY_FIRST",
		"social": []map[string]any{{"network": "instagram", "url": "https://instagram.com/adeskitchen"}, {"network": "tiktok", "url": "https://tiktok.com/@ama"}}}), 200)
	mustStatus(t, b.do("PUT", "/v1/seller/storefront", map[string]any{"primaryColor": "gold", "social": []map[string]any{{"network": "tiktok", "url": "https://instagram.com/x"}}}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/storefront/publish", nil), 200)

	for _, op := range []string{"getSellerSession", "setActiveOrg", "getOnboarding", "getCompliance", "getDashboard", "listSellerOrders", "getSellerOrder",
		"updateShipmentStatus", "quoteShippingRates", "buyShippingLabel", "listListings", "setListingActive", "submitProduct", "getCategoryTree",
		"getProductType", "checkGtin", "listCategoryRequests", "requestCategory", "requestUpload", "completeUpload", "getInventory", "adjustStock",
		"getPayouts", "requestPayout", "getSellerFees", "getBilling", "changePlan", "makeDefaultPaymentMethod", "removePaymentMethod", "getTeam", "inviteMember",
		"assignRole", "removeMember", "revokeInvitation", "lookupInvitation", "acceptInvitation", "getCalendar", "listBookingRequests", "actOnBooking", "getWorkingHours", "saveWorkingHours", "listServices", "createService",
		"updateService", "getCancellationPolicy", "saveCancellationPolicy", "getEarnings", "getPublicPage", "savePublicPage", "publishPublicPage"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
	for path, item := range doc.Paths.Map() {
		for _, op := range item.Operations() {
			if validated[op.OperationID] == 0 {
				t.Errorf("contract operation %s (%s) is not exercised", op.OperationID, path)
			}
		}
	}
}

func TestSellerNavigationIsRoleAwareAndGatedByApproval(t *testing.T) {
	bff, b := sellerSetup(t, testkit.Options{SellerPending: true})
	s := b.do("GET", "/v1/seller/session", nil).json(t)
	if s["portal"] != "SELLER_CENTER" || s["approved"] != true || s["landing"] != "home" {
		t.Fatalf("seller session = %v", s)
	}
	keys := moduleKeys(t, s)
	for _, want := range []string{"home", "orders", "listings", "inventory", "payouts", "billing", "account-health", "team", "storefront"} {
		if !slices.Contains(keys, want) {
			t.Fatalf("owner nav lacks %s: %v", want, keys)
		}
	}
	if slices.Contains(keys, "onboarding") || slices.Contains(keys, "calendar") {
		t.Fatalf("approved seller nav must not show onboarding or provider tools: %v", keys)
	}
	// Orders badge (to ship) and open compliance items are counted.
	groups := s["navGroups"].([]any)
	labels := []string{}
	for _, g := range groups {
		labels = append(labels, g.(map[string]any)["label"].(string))
	}
	if !slices.Equal(labels, []string{"Overview", "Commerce", "Growth", "Finance", "Trust & compliance", "Settings"}) {
		t.Fatalf("groups = %v", labels)
	}

	// Provider organisation → Provider Hub groups.
	p := b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": testkit.ProviderOrgID}).json(t)
	pk := moduleKeys(t, p)
	if p["portal"] != "PROVIDER_HUB" || !slices.Contains(pk, "calendar") || !slices.Contains(pk, "earnings") || slices.Contains(pk, "orders") {
		t.Fatalf("provider session = %v", p)
	}

	// Organisation still in review → only Onboarding; back-office routes refuse with NOT_APPROVED.
	g := b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": testkit.PendingOrgID}).json(t)
	if g["approved"] != false || !slices.Equal(moduleKeys(t, g), []string{"onboarding"}) || g["landing"] != "onboarding" {
		t.Fatalf("pending session = %v", g)
	}
	if r := b.do("GET", "/v1/seller/listings", nil); r.status != 403 || problemCode(t, r) != "NOT_APPROVED" {
		t.Fatalf("listings before approval = %d %s", r.status, r.body)
	}
	if on := b.do("GET", "/v1/seller/onboarding", nil).json(t); on["status"] != "INFO_REQUESTED" || on["canResume"] != true {
		t.Fatalf("onboarding = %v", on)
	}

	// A fulfilment member sees orders and inventory but not billing, payouts or team.
	bff.Upstreams.MemberOrgs[0].Role = "seller.fulfilment"
	b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": testkit.SellerOrgID})
	fk := moduleKeys(t, b.do("GET", "/v1/seller/session", nil).json(t))
	if !slices.Contains(fk, "orders") || slices.Contains(fk, "billing") || slices.Contains(fk, "team") || slices.Contains(fk, "payouts") {
		t.Fatalf("fulfilment nav = %v", fk)
	}
	if r := b.do("GET", "/v1/seller/billing", nil); r.status != 403 || problemCode(t, r) != "FORBIDDEN" {
		t.Fatalf("billing as fulfilment = %d %s", r.status, r.body)
	}
}

func TestSellerOrderFlowAndCapabilityCards(t *testing.T) {
	_, b := sellerSetup(t, testkit.Options{SellerPending: true})
	row := b.do("GET", "/v1/seller/orders", nil).json(t)["items"].([]any)[0].(map[string]any)
	if row["status"] != "PLACED" || row["buyerName"] != "Adaeze Nwosu" || row["shipTo"] != "Houston, TX" || row["canBuyLabel"] != false {
		t.Fatalf("row = %v", row)
	}
	if acts := row["nextActions"].([]any); len(acts) != 1 || acts[0] != "CONFIRMED" {
		t.Fatalf("a new order can only be confirmed by the seller (cancel is not a seller action): %v", acts)
	}
	path := "/v1/seller/orders/AT-2026-000123/shipments/sh-1/status"
	for _, st := range []string{"CONFIRMED", "PACKED", "READY"} {
		r := b.do("POST", path, map[string]any{"status": st})
		mustStatus(t, r, 200)
		if got := r.json(t)["shipments"].([]any)[0].(map[string]any)["status"]; got != st {
			t.Fatalf("after %s status = %v", st, got)
		}
	}
	if r := b.do("POST", path, map[string]any{"status": "SHIPPED"}); r.status != 422 {
		t.Fatalf("sellers cannot mark shipped: %d", r.status)
	}

	team := b.do("GET", "/v1/seller/team", nil).json(t)
	if team["yourRoleKey"] != "seller.owner" || team["canManage"] != true {
		t.Fatalf("team = %v", team)
	}
	roles := team["roles"].([]any)
	var fulfil map[string]any
	keys := []string{}
	for _, r := range roles {
		m := r.(map[string]any)
		keys = append(keys, m["key"].(string))
		if m["key"] == "seller.fulfilment" {
			fulfil = m
		}
	}
	if len(roles) != 7 || fulfil == nil {
		t.Fatalf("seller roles = %v", keys)
	}
	can := strings.Join(toStrings(fulfil["can"]), "|")
	cannot := strings.Join(toStrings(fulfil["cannot"]), "|")
	if !strings.Contains(can, "orders, shipping labels and refunds") || !strings.Contains(cannot, "payouts and balances") {
		t.Fatalf("fulfilment card can=%s cannot=%s", can, cannot)
	}
}

func toStrings(v any) []string {
	out := []string{}
	for _, x := range v.([]any) {
		out = append(out, x.(string))
	}
	return out
}

func TestSellerPendingUpstreamsAnswer503(t *testing.T) {
	_, b := sellerSetup(t, testkit.Options{})
	for _, path := range []string{"/v1/seller/orders", "/v1/seller/orders/AT-2026-000123"} {
		r := b.do("GET", path, nil)
		if r.status != 503 || problemCode(t, r) != "UPSTREAM_UNAVAILABLE" || r.header.Get("Retry-After") == "" ||
			r.json(t)["type"] != "https://errors.afrotransact.com/bff.upstream-unavailable" {
			t.Fatalf("%s = %d %s", path, r.status, r.body)
		}
	}
	// The dashboard degrades per chart instead of failing.
	d := b.do("GET", "/v1/seller/dashboard", nil).json(t)
	avail := map[string]bool{}
	for _, c := range d["charts"].([]any) {
		m := c.(map[string]any)
		avail[m["id"].(string)] = m["available"].(bool)
	}
	if avail["sales"] || !avail["stock"] {
		t.Fatalf("charts availability = %v", avail)
	}
	b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": testkit.ProviderOrgID})
	if r := b.do("GET", "/v1/seller/bookings/calendar?from=2026-10-12&to=2026-10-18", nil); r.status != 503 {
		t.Fatalf("calendar without bookings = %d", r.status)
	}
}
