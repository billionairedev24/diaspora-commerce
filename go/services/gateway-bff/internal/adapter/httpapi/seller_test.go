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

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
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
		if strings.HasPrefix(rec.Header().Get("Content-Type"), "text/html") {
			validated[route.Operation.OperationID]++ // a printable document: the status is documented, there is no schema
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
	// Start selling (S286): an approved seller opens Seller Center instead; a customer starts, resumes and autosaves.
	mustStatus(t, b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "SELLER"}), 409)
	mustStatus(t, b.do("PUT", "/v1/seller/onboarding/business", map[string]any{"storeName": "Ade's"}), 404)
	memberOrgs := bff.Upstreams.MemberOrgs
	bff.Upstreams.MemberOrgs = nil
	mustStatus(t, b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "SELLER"}), 201)
	mustStatus(t, b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": "SELLER"}), 200)
	mustStatus(t, b.do("POST", "/v1/seller/onboarding", map[string]any{"kind": ""}), 422)
	mustStatus(t, b.do("PUT", "/v1/seller/onboarding/business", map[string]any{"offering": "PRODUCTS", "entityType": "SOLE_PROPRIETOR", "storeName": "Ade's"}), 200)
	mustStatus(t, b.do("PUT", "/v1/seller/onboarding/business", map[string]any{"storeName": "A"}), 422)
	mustStatus(t, b.do("PUT", "/v1/seller/onboarding/business-details", map[string]any{"legalFirstName": "Adaeze", "legalLastName": "Nwosu",
		"address": map[string]any{"line1": "1 Main St", "city": "Houston", "state": "TX", "postalCode": "77002"}}), 200)
	mustStatus(t, b.do("GET", "/v1/seller/session", nil), 200)
	bff.Upstreams.MemberOrgs, bff.Upstreams.Application = memberOrgs, nil
	// Orders: queue, sheet, status flow, rates and label.
	mustStatus(t, b.do("GET", "/v1/seller/orders?limit=20", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/orders?status=SHIPPED_NOPE", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/orders/AT-2026-000123", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/orders/AT-0000", nil), 404)
	mustStatus(t, b.do("POST", orderPath+"/status", map[string]any{"status": "DELIVERED", "handoverCode": "12"}), 422) // a code, when given, has 4 digits
	mustStatus(t, b.do("POST", orderPath+"/status", map[string]any{"status": "DELIVERED"}), 409)                  // no code: orders decides (placed → delivered is not a step)
	mustStatus(t, b.do("POST", orderPath+"/status", map[string]any{"status": "PACKED"}), 409)
	mustStatus(t, b.do("POST", orderPath+"/status", map[string]any{"status": "CONFIRMED"}), 200)
	mustStatus(t, b.do("POST", orderPath+"/rates", map[string]any{"parcel": parcel, "shipFrom": from}), 200)
	rateRes := b.do("POST", orderPath+"/rates", map[string]any{"carrier": "fedex", "parcel": parcel, "shipFrom": from})
	mustStatus(t, rateRes, 200)
	if quotes, ok := rateRes.json(t)["quotes"].([]any); !ok || len(quotes) == 0 {
		t.Fatalf("expected fedex quotes, got %v", rateRes.body)
	} else {
		for _, q := range quotes {
			if q.(map[string]any)["carrier"] != "fedex" {
				t.Fatalf("expected only fedex quotes, got %v", q)
			}
		}
	}
	mustStatus(t, b.do("POST", orderPath+"/rates", map[string]any{"parcel": map[string]any{"weightLb": 0}, "shipFrom": from}), 422)
	mustStatus(t, b.do("POST", orderPath+"/labels", map[string]any{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "parcel": parcel, "shipFrom": from}), 422)
	b.headers["Idempotency-Key"] = "label-0123456789"
	mustStatus(t, b.do("POST", orderPath+"/labels", map[string]any{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "parcel": parcel, "shipFrom": from}), 201)
	mustStatus(t, b.do("POST", orderPath+"/labels", map[string]any{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "parcel": parcel, "shipFrom": from}), 409)
	delete(b.headers, "Idempotency-Key")
	// Listings and the product builder.
	mustStatus(t, b.do("GET", "/v1/seller/listings?page=0&size=25", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/listings?status=NOPE", nil), 422)
	// One search over every column (buyer, total as text, status label …) and any sort; the BFF pages the result.
	for q, want := range map[string]int{"adaeze": 1, "54.37": 1, "you+ship": 1, "ofada+houston": 1, "zzz": 0} {
		r := b.do("GET", "/v1/seller/orders?q="+q+"&limit=20", nil)
		if r.status != 200 || len(r.json(t)["items"].([]any)) != want || num(r.json(t), "total") != want {
			t.Fatalf("orders q=%s = %d %s", q, r.status, r.body)
		}
	}
	if r := b.do("GET", "/v1/seller/orders?sort=totalMinor,desc&sort=placedAt&limit=20", nil); r.status != 200 ||
		fmt.Sprint(r.json(t)["sort"]) != "[totalMinor,desc placedAt,asc]" || r.json(t)["partial"] != false {
		t.Fatalf("sorted orders = %d %s", r.status, r.body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/orders?sort=nope,asc", nil), 422)
	if r := b.do("GET", "/v1/seller/listings?q=ofada&sort=priceMinor,desc", nil); r.status != 200 || num(r.json(t), "total") != 1 {
		t.Fatalf("listings q = %d %s", r.status, r.body)
	}
	// Exports of the active organisation: Excel with charts, PDF, CSV; client-side tables send their rows.
	job := b.do("POST", "/v1/seller/exports", map[string]any{"table": "orders", "format": "xlsx", "q": "adaeze", "sort": []string{"totalMinor,desc"},
		"columns": []string{"orderNumber", "buyerName", "totalMinor", "placedAt", "status"}})
	if job.status != 201 || job.json(t)["status"] != "READY" || num(job.json(t), "rows") != 1 || job.json(t)["title"] != "Orders" {
		t.Fatalf("orders export = %d %s", job.status, job.body)
	}
	exportID := job.json(t)["id"].(string)
	mustStatus(t, b.do("GET", "/v1/seller/exports/"+exportID, nil), 200)
	if r := b.do("GET", "/v1/seller/exports", nil); r.status != 200 || len(r.json(t)["items"].([]any)) != 1 {
		t.Fatalf("seller exports = %d %s", r.status, r.body)
	}
	dl := b.do("GET", "/v1/seller/exports/"+exportID+"/download", nil)
	if dl.status != 302 || !strings.HasPrefix(dl.header.Get("Location"), "/api/bff/v1/seller/export-files/"+exportID+"?") {
		t.Fatalf("download = %d %v", dl.status, dl.header)
	}
	if f := b.do("GET", strings.TrimPrefix(dl.header.Get("Location"), "/api/bff"), nil); f.status != 200 || !bytes.HasPrefix(f.body, []byte("PK")) ||
		!strings.Contains(f.header.Get("Content-Disposition"), "orders-") {
		t.Fatalf("export file = %d %v", f.status, f.header)
	}
	mustStatus(t, b.do("GET", "/v1/seller/export-files/"+exportID+"?expires=9999999999&sig=bad", nil), 403)
	mustStatus(t, b.do("GET", "/v1/seller/exports/exp_unknown", nil), 404)
	if r := b.do("POST", "/v1/seller/exports", map[string]any{"table": "listings", "format": "pdf", "ids": []string{"prod-1"}}); r.status != 201 || num(r.json(t), "rows") != 1 {
		t.Fatalf("listings pdf = %d %s", r.status, r.body)
	}
	inline := map[string]any{"format": "csv", "dataset": map[string]any{"table": "payouts", "title": "Payouts",
		"columns": []map[string]any{{"key": "id", "label": "Payout", "kind": "mono"}, {"key": "amount", "label": "Amount", "kind": "money"}},
		"rows":    [][]any{{"po-1", 120.5}, {"po-2", nil}}}}
	if r := b.do("POST", "/v1/seller/exports", inline); r.status != 201 || num(r.json(t), "rows") != 2 || r.json(t)["resource"] != "payouts" {
		t.Fatalf("dataset export = %d %s", r.status, r.body)
	}
	inline["format"] = "docx"
	mustStatus(t, b.do("POST", "/v1/seller/exports", inline), 422)
	mustStatus(t, b.do("POST", "/v1/seller/exports", map[string]any{"table": "team", "format": "csv"}), 422)
	mustStatus(t, b.do("POST", "/v1/seller/exports", map[string]any{"table": "orders", "format": "csv", "sort": []string{"nope"}}), 422)
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
	exerciseSellerFba(t, b)
	exerciseSellerListing(t, bff, b)
	// Money.
	// S298: plan invoices paid from sales are listed as "Plan fee (invoice …)" deductions; DECLINED ones took nothing.
	if r := b.do("GET", "/v1/seller/payouts", nil); r.status != 200 || len(r.json(t)["planFeeDeductions"].([]any)) != 1 ||
		r.json(t)["planFeeDeductions"].([]any)[0].(map[string]any)["label"] != "Plan fee (invoice AT-0003)" {
		t.Fatalf("payouts plan fees = %d %s", r.status, r.body)
	}
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
		if strings.Join(changes, ",") != "category:fashion,fbaPerUnitMinor,deliveryFee" {
			t.Fatalf("changes = %v", v["changes"])
		}
		// AfroTransact Delivery seller fee (S255): the default now, the organisation's own override next (never others').
		if df := cur["deliveryFee"].(map[string]any); df["source"] != "DEFAULT" || df["summary"] != "$2.00 per package" || num(df, "maxRadiusMiles") != 25 {
			t.Fatalf("delivery fee = %v", df)
		}
		if df := up["deliveryFee"].(map[string]any); df["source"] != "OVERRIDE" || df["summary"] != "$1.50 per package" {
			t.Fatalf("upcoming delivery fee = %v", df)
		}
	}
	billing := b.do("GET", "/v1/seller/billing", nil)
	mustStatus(t, billing, 200)
	// S298: how the plan is paid, and on each invoice what credit, sales and the card paid.
	bv := billing.json(t)
	inv0 := bv["invoices"].([]any)[0].(map[string]any)
	if bv["planFeeCollection"].(map[string]any)["mode"] != "EARNINGS_FIRST" ||
		bv["planFeeCollection"].(map[string]any)["canChange"] != true ||
		num(inv0, "paidFromCreditMinor") != 2000 || num(inv0, "paidFromSalesMinor") != 7000 || num(inv0, "paidByCardMinor") != 2115 ||
		inv0["canRetry"] != false || len(inv0["lines"].([]any)) != 1 {
		t.Fatalf("billing plan fees = %v / %v", bv["planFeeCollection"], bv["invoices"])
	}
	// Staff conditions on the charges and what the plan allows come through for the seller to read.
	sub := bv["subscription"].(map[string]any)
	if gate := sub["chargeGate"].(map[string]any); gate["mode"] != "START_AFTER" || num(gate, "progress") != 42000 ||
		!strings.Contains(gate["label"].(string), "Charging starts after $1,000") {
		t.Fatalf("charge gate = %v", sub["chargeGate"])
	}
	if ent := sub["entitlements"].(map[string]any); num(ent, "listings") != 1000 || ent["aiListingWriter"] != true || ent["lolaTier"] != "GROWTH" {
		t.Fatalf("entitlements = %v", sub["entitlements"])
	}
	if retry := b.do("POST", "/v1/seller/billing/invoices/inv_s3/retry", nil); retry.status != 200 || retry.json(t)["status"] != "PAID" {
		t.Fatalf("retry invoice = %d %s", retry.status, retry.body)
	}
	if doc := b.do("GET", "/v1/seller/billing/invoices/inv_s3/document", nil); doc.status != 200 || !strings.Contains(string(doc.body), "AT-INV-2026-1003") {
		t.Fatalf("invoice document = %d %s", doc.status, doc.body)
	}
	if pf := b.do("PUT", "/v1/seller/billing/plan-fee-collection", map[string]any{"mode": "CARD"}); pf.status != 200 || pf.json(t)["mode"] != "CARD" {
		t.Fatalf("plan fee collection = %d %s", pf.status, pf.body)
	}
	mustStatus(t, b.do("PUT", "/v1/seller/billing/plan-fee-collection", map[string]any{"mode": "SOMETIMES"}), 422)
	bff.Upstreams.PlanFeeCollectionOff = true
	mustStatus(t, b.do("PUT", "/v1/seller/billing/plan-fee-collection", map[string]any{"mode": "EARNINGS_FIRST"}), 409)
	bff.Upstreams.PlanFeeCollectionOff = false
	mustStatus(t, b.do("POST", "/v1/seller/billing/plan", map[string]any{"planCode": "seller_pro", "cadence": "ANNUAL"}), 200)
	mustStatus(t, b.do("POST", "/v1/seller/billing/plan", map[string]any{"planCode": "seller_pro", "cadence": "WEEKLY"}), 422)
	mustStatus(t, b.do("DELETE", "/v1/seller/billing/payment-methods/aaaaaaaa-0000-0000-0000-000000000001", nil), 409)
	mustStatus(t, b.do("POST", "/v1/seller/billing/payment-methods/aaaaaaaa-0000-0000-0000-000000000002/default", nil), 200)
	mustStatus(t, b.do("DELETE", "/v1/seller/billing/payment-methods/aaaaaaaa-0000-0000-0000-000000000001", nil), 200)
	// S292–S297: rewards, the proration preview and adding a payment method with a SetupIntent.
	rewards := b.do("GET", "/v1/seller/billing/rewards", nil)
	mustStatus(t, rewards, 200)
	// S299: low-sales relief is pending until its check and, once applied, says when Stripe charges next.
	if body := string(rewards.body); !strings.Contains(body, `"comparison":"LESS_THAN","windowAnchor":"SUBSCRIPTION_START","windowDays":30,"pending":true,"checkAt":"2026-11-08T10:00:00Z"`) ||
		!strings.Contains(body, `"comparison":"LESS_THAN","nextChargeAt":"2026-11-08T10:00:00Z"`) ||
		!strings.Contains(body, `"comparison":"AT_LEAST","nextChargeAt":null`) {
		t.Errorf("rewards = %s", body)
	}
	mustStatus(t, b.do("GET", "/v1/seller/billing/plan/preview?planCode=seller_pro&cadence=QUARTERLY", nil), 200)
	mustStatus(t, b.do("GET", "/v1/seller/billing/plan/preview?planCode=nope", nil), 422)
	mustStatus(t, b.do("GET", "/v1/seller/billing/plan/preview?planCode=seller_pro&cadence=WEEKLY", nil), 422)
	mustStatus(t, b.do("POST", "/v1/seller/billing/setup-intents", nil), 201)
	mustStatus(t, b.do("POST", "/v1/seller/billing/payment-methods", map[string]any{"stripePaymentMethodId": "pm_card_visa", "kind": "CARD",
		"brand": "Visa", "last4": "4242", "expMonth": 12, "expYear": 2030}), 201)
	mustStatus(t, b.do("POST", "/v1/seller/billing/payment-methods", map[string]any{"stripePaymentMethodId": "card_1", "kind": "CASH"}), 422)
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
	// AfroTransact pickup from the store (S255): hours, geocoded position and the delivery area.
	firstID := b.do("GET", "/v1/seller/ship-from-addresses", nil).json(t)["items"].([]any)[0].(map[string]any)["id"].(string)
	pickup := func(id string, body map[string]any, want int) map[string]any {
		t.Helper()
		r := b.do("PUT", "/v1/seller/ship-from-addresses/"+id+"/afrotransact-pickup", body)
		mustStatus(t, r, want)
		return r.json(t)
	}
	hours := []map[string]any{{"day": "MONDAY", "opens": "09:00", "closes": "17:00"}, {"day": "SATURDAY", "opens": "10:00", "closes": "14:00"}}
	// Customer pickup at the store: a carrier-gateway SELLER pickup location with the address's id.
	if r := b.do("PUT", "/v1/seller/ship-from-addresses/"+firstID+"/customer-pickup", map[string]any{"enabled": true, "hours": []any{}}); r.status != 422 ||
		r.json(t)["code"] != domain.CodePickupInvalidHours {
		t.Fatalf("customer pickup without hours = %d %s", r.status, r.body)
	}
	cp := b.do("PUT", "/v1/seller/ship-from-addresses/"+firstID+"/customer-pickup", map[string]any{"enabled": true, "hours": hours, "readyHours": 3})
	mustStatus(t, cp, 200)
	if c := cp.json(t)["customerPickup"].(map[string]any); c["enabled"] != true || c["readyHours"] != float64(3) || len(c["hours"].([]any)) != 2 {
		t.Fatalf("customer pickup on = %s", cp.body)
	}
	listed := b.do("GET", "/v1/seller/ship-from-addresses", nil).json(t)["items"].([]any)
	for _, it := range listed {
		a := it.(map[string]any)
		if want := a["id"] == firstID; a["customerPickup"].(map[string]any)["enabled"] != want {
			t.Fatalf("listed customer pickup of %v = %v", a["id"], a["customerPickup"])
		}
	}
	mustStatus(t, b.do("PUT", "/v1/seller/ship-from-addresses/nope/customer-pickup", map[string]any{"enabled": false, "hours": []any{}}), 404)
	if p := pickup(firstID, map[string]any{"enabled": true, "hours": []any{}}, 422); p["code"] != domain.CodePickupInvalidHours {
		t.Fatalf("no hours = %v", p)
	}
	overlap := []map[string]any{{"day": "MONDAY", "opens": "09:00", "closes": "12:00"}, {"day": "monday", "opens": "11:00", "closes": "13:00"}}
	if p := pickup(firstID, map[string]any{"enabled": true, "hours": overlap}, 422); p["errors"].(map[string]any)["hours.1.opens"] == nil {
		t.Fatalf("overlap = %v", p)
	}
	on := pickup(firstID, map[string]any{"enabled": true, "hours": hours}, 200)
	if ap := on["afrotransactPickup"].(map[string]any); ap["enabled"] != true || ap["zoneName"] != "Houston Metro" || len(ap["hours"].([]any)) != 2 ||
		on["position"].(map[string]any)["precision"] != "ADDRESS" {
		t.Fatalf("pickup on = %v", on)
	}
	home["postalCode"], home["label"] = "78701", "Austin"
	austin := b.do("POST", "/v1/seller/ship-from-addresses", home).json(t)["id"].(string)
	if p := pickup(austin, map[string]any{"enabled": true, "hours": hours}, 422); p["code"] != domain.CodePickupOutsideDeliveryArea {
		t.Fatalf("outside = %v", p)
	}
	home["postalCode"], home["label"] = "00000", "Unlocatable"
	zipOnly := b.do("POST", "/v1/seller/ship-from-addresses", home).json(t)["id"].(string)
	if p := pickup(zipOnly, map[string]any{"enabled": true, "hours": hours}, 422); p["code"] != domain.CodePickupNotGeocoded {
		t.Fatalf("not geocoded = %v", p)
	}
	pickup("nope", map[string]any{"enabled": false, "hours": []any{}}, 404)
	// Lola for sellers: status and one question (answered from the scripted model).
	mustStatus(t, b.do("GET", "/v1/seller/assistant", nil), 200)
	mustStatus(t, b.do("POST", "/v1/seller/assistant/chat", map[string]any{"message": "hello"}), 200)
	mustStatus(t, b.do("POST", "/v1/seller/assistant/chat", map[string]any{"message": ""}), 422)

	// Offers: the fulfilment choice per listing, with the pickup location for AfroTransact Delivery.
	mustStatus(t, b.do("GET", "/v1/seller/offers", nil), 422)
	if items := b.do("GET", "/v1/seller/offers?productId=p-1", nil).json(t)["items"].([]any); len(items) != 1 ||
		items[0].(map[string]any)["fulfilment"] != "SELLER" || items[0].(map[string]any)["shipsFrom"] != "Houston, TX" {
		t.Fatalf("offers = %v", items)
	}
	mustStatus(t, b.do("PUT", "/v1/seller/offers/of-1/fulfilment", map[string]any{"fulfilment": "AFROTRANSACT_DELIVERY"}), 422)
	if e := b.do("PUT", "/v1/seller/offers/of-1/fulfilment", map[string]any{"fulfilment": "AFROTRANSACT_DELIVERY", "shipFromLocationId": "loc-2"}).json(t); e["errors"].(map[string]any)["shipFromLocationId"] == nil {
		t.Fatalf("location refused = %v", e)
	}
	if o := b.do("PUT", "/v1/seller/offers/of-1/fulfilment", map[string]any{"fulfilment": "AFROTRANSACT_DELIVERY", "shipFromLocationId": "loc-1"}).json(t); o["fulfilment"] != "AFROTRANSACT_DELIVERY" || o["shipFromLocationId"] != "loc-1" {
		t.Fatalf("offer = %v", o)
	}
	mustStatus(t, b.do("PUT", "/v1/seller/offers/of-404/fulfilment", map[string]any{"fulfilment": "SELLER"}), 404)
	// The AfroTransact Delivery shipment shows the seller delivery fee; the order sheet the payout breakdown.
	order := b.do("GET", "/v1/seller/orders/AT-2026-000123", nil).json(t)
	atd := order["shipments"].([]any)[1].(map[string]any)
	if atd["fulfilment"] != "AFROTRANSACT_DELIVERY" || atd["canBuyLabel"] != false || num(atd["localDelivery"].(map[string]any), "sellerFeeMinor") != 200 ||
		len(atd["nextActions"].([]any)) != 1 {
		t.Fatalf("AfroTransact Delivery shipment = %v", atd)
	}
	if e := order["earnings"].(map[string]any); num(e, "deliveryFeesMinor") != 200 || num(e, "netMinor") != 5588 {
		t.Fatalf("earnings = %v", e)
	}

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

	// Onboarding wizard (S289): each step's save, embedded Stripe, plans, documents and submit, with their failures.
	wup, w := startedApplicant(t, "pk_test_x")
	w.onResponse = check
	addr := map[string]any{"line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "postalCode": "77042"}
	mustStatus(t, w.do("PUT", "/v1/seller/onboarding/owners", map[string]any{"owners": []map[string]any{
		{"firstName": "Kofi", "lastName": "Mensah", "title": "CEO", "email": "kofi@example.com", "ownershipPercent": 60, "significantControl": true,
			"dateOfBirth": "1980-02-02", "address": addr}}}), 200)
	mustStatus(t, w.do("PUT", "/v1/seller/onboarding/owners", map[string]any{"owners": []map[string]any{
		{"firstName": "Kofi", "lastName": "Mensah", "ownershipPercent": 70}, {"firstName": "Ama", "lastName": "Mensah", "ownershipPercent": 40}}}), 422)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/stripe/session", nil), 200)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/stripe/refresh", nil), 200)
	mustStatus(t, w.do("GET", "/v1/seller/onboarding/plans", nil), 200)
	mustStatus(t, w.do("PUT", "/v1/seller/onboarding/plan", map[string]any{"planCode": "seller_growth", "cadence": "monthly", "paymentMethodKind": "card",
		"stripePaymentMethodId": "pm_1", "cardBrand": "visa", "cardLast4": "4242", "cardExpMonth": 8, "cardExpYear": 2029}), 200)
	mustStatus(t, w.do("PUT", "/v1/seller/onboarding/plan", map[string]any{"cadence": "monthly"}), 422)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/uploads", map[string]any{"fileName": "permit.pdf", "contentType": "application/pdf", "size": 120000}), 201)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/uploads", map[string]any{"fileName": "permit.exe", "contentType": "application/x-msdownload", "size": 10}), 422)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/uploads/asset-1/complete", nil), 200)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/uploads/"+testkit.MissingAssetID+"/complete", nil), 404)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/submit", nil), 422)
	mustStatus(t, w.do("PUT", "/v1/seller/onboarding/catalog-compliance", map[string]any{"categories": []string{"groceries"},
		"foodPermit": map[string]any{"permitType": "liquor", "permitNumber": "L-1", "expiresOn": "2027-06-30"}}), 422)
	mustStatus(t, w.do("PUT", "/v1/seller/onboarding/catalog-compliance", map[string]any{"categories": []string{"groceries"},
		"foodPermit":         map[string]any{"permitType": "cottage_food", "permitNumber": "CF-1", "expiresOn": "2027-06-30", "documentAssetId": "asset-1"},
		"agreementsAccepted": true, "w9Certified": true, "w9SignerName": "Kofi Mensah"}), 200)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/submit", nil), 200)
	// Without an application the wizard's routes answer 404; without a Stripe key the embedded session can't load.
	wup.Application = nil
	mustStatus(t, w.do("GET", "/v1/seller/onboarding/plans", nil), 404)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/stripe/refresh", nil), 404)
	mustStatus(t, w.do("POST", "/v1/seller/onboarding/submit", nil), 404)
	_, nokey := startedApplicant(t, "")
	nokey.onResponse = check
	mustStatus(t, nokey.do("POST", "/v1/seller/onboarding/stripe/session", nil), 503)
	// Account health: an Account Session for Stripe's components (owner / finance of the active organisation).
	mustStatus(t, b.do("POST", "/v1/seller/stripe/session", nil), 503)
	keyed, kb := sellerSetup(t, testkit.Options{SellerPublishableKey: "pk_test_health"})
	kb.onResponse = check
	mustStatus(t, kb.do("POST", "/v1/seller/stripe/session", nil), 200)
	keyed.Upstreams.AccountHealthForbidden = true
	mustStatus(t, kb.do("POST", "/v1/seller/stripe/session", nil), 403)

	for _, op := range []string{"getSellerSession", "setActiveOrg", "getOnboarding", "getCompliance", "getDashboard", "listSellerOrders", "getSellerOrder",
		"updateShipmentStatus", "quoteShippingRates", "buyShippingLabel", "listListings", "setListingActive", "submitProduct", "getCategoryTree",
		"getProductType", "checkGtin", "listCategoryRequests", "requestCategory", "requestUpload", "completeUpload", "getInventory", "adjustStock",
		"getPayouts", "requestPayout", "getSellerFees", "getBilling", "changePlan", "makeDefaultPaymentMethod", "removePaymentMethod", "getTeam", "inviteMember",
		"assignRole", "removeMember", "revokeInvitation", "lookupInvitation", "acceptInvitation", "getCalendar", "listBookingRequests", "actOnBooking", "getWorkingHours", "saveWorkingHours", "listServices", "createService",
		"updateService", "getCancellationPolicy", "saveCancellationPolicy", "getEarnings", "getPublicPage", "savePublicPage", "publishPublicPage",
		"saveOnboardingOwners", "createSellerStripeSession", "saveOnboardingPlan", "saveOnboardingCompliance", "getOnboardingPlans", "createOnboardingStripeSession",
		"refreshOnboardingStripe", "submitOnboarding", "requestOnboardingUpload", "completeOnboardingUpload"} {
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
			r.json(t)["type"] != "https://developers.afrotransact.com/problems/bff.upstream-unavailable" {
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

// Account health is for the owner and finance: other members get an empty compliance summary, not a failed call on
// every page of the shell.
func TestComplianceForMembersWhoMayNotSeeAccountHealth(t *testing.T) {
	bff, b := sellerSetup(t, testkit.Options{})
	bff.Upstreams.AccountHealthForbidden = true
	r := b.do("GET", "/v1/seller/compliance", nil)
	mustStatus(t, r, 200)
	got := r.json(t)
	if items, _ := got["items"].([]any); len(items) != 0 {
		t.Fatalf("items = %v, want none", got["items"])
	}
}
