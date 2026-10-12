package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// plansStaff: Finance (plans:manage + billing:adjust), an auditor (seller:billing, view only) and support (no access).
var plansStaff = map[string][]any{
	"finance": {[]string{"platform.finance"}, []string{"plans:manage", "billing:adjust", "seller:billing"}},
	"auditor": {[]string{"platform.auditor"}, []string{"seller:billing"}},
	"support": {[]string{"platform.customer_support"}, []string{"support:respond"}},
}

const (
	testRuleID     = "6d1c0b6a-0000-4000-8000-000000000001"
	testCampaignID = "5c0b9a59-0000-4000-8000-000000000002"
	testGrantID    = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
)

// fakeSellerBilling answers the seller service's Plans & billing API with contract-shaped fixtures and records calls.
type fakeSellerBilling struct {
	mu     sync.Mutex
	calls  []string
	bodies map[string]map[string]any
	idem   map[string]string
}

func samplePlan(code, status string) map[string]any {
	price := func(c string, amount int64, id any) map[string]any {
		return map[string]any{"cadence": c, "amountCents": amount, "lookupKey": code + "_" + strings.ToLower(c), "stripePriceId": id,
			"syncedAt": nil, "syncError": nil}
	}
	return map[string]any{"code": code, "kind": "SELLER", "name": "Seller Growth", "tagline": "Most popular", "features": []string{"Up to 1,000 listings"},
		"limits": map[string]string{"listings": "1000"}, "visibility": "PUBLIC", "status": status, "sortOrder": 20, "currentVersion": 2,
		"monthlyCents": 3900, "quarterlyCents": 11115, "annualCents": 39000, "trialDays": 30, "rowVersion": 4, "stripeProductId": "prod_1",
		"subscribers": 12, "invites": []string{},
		"versions": []any{
			map[string]any{"version": 2, "status": "PUBLISHED", "trialDays": 30, "note": nil, "createdBy": "stf-finance", "createdAt": "2026-10-01T10:00:00Z",
				"publishedAt": "2026-10-01T10:00:00Z", "subscribers": 10, "prices": []any{price("MONTHLY", 3900, "price_2m"), price("QUARTERLY", 11115, "price_2q"),
					price("ANNUAL", 39000, nil)}},
			map[string]any{"version": 1, "status": "SUPERSEDED", "trialDays": 30, "note": "Initial catalogue", "createdBy": "system",
				"createdAt": "2026-09-01T10:00:00Z", "publishedAt": "2026-09-01T10:00:00Z", "subscribers": 2, "prices": []any{price("MONTHLY", 3500, "price_1m")}},
		}}
}

func sampleGrant(typ string) map[string]any {
	return map[string]any{"id": testGrantID, "orgId": "org_1", "source": "MANUAL", "ruleId": nil, "type": typ, "creditCents": 2500, "freeDays": nil,
		"percentOff": nil, "durationInvoices": nil, "invoiceId": nil, "status": "APPLIED", "reasonCode": "GOODWILL", "note": nil, "summary": "$25 credit",
		"actorId": "stf-finance", "metric": nil, "metricValue": nil, "stripeCustomerId": "cus_1", "stripeSubscriptionId": "sub_1",
		"stripeBalanceTransactionId": "cbtxn_1", "stripeCouponId": nil, "previousTrialEnd": nil, "newTrialEnd": nil, "attempts": 1, "lastError": nil,
		"createdAt": "2026-10-10T10:00:00Z", "appliedAt": "2026-10-10T10:00:01Z"}
}

func sampleCampaign() map[string]any {
	return map[string]any{"id": testCampaignID, "name": "October 60", "promoCode": "OCT60", "trialDays": 60, "signupFrom": "2026-10-01T00:00:00Z",
		"signupTo": "2026-11-01T00:00:00Z", "kind": nil, "eligiblePlans": []string{}, "maxRedemptions": nil, "redemptions": 3, "status": "ACTIVE",
		"createdBy": "stf-finance", "createdAt": "2026-09-20T10:00:00Z", "updatedBy": "stf-finance", "updatedAt": "2026-09-20T10:00:00Z", "version": 0}
}

func sampleRule() map[string]any {
	return map[string]any{"id": testRuleID, "name": "First $1,000", "description": nil, "status": "ACTIVE", "kind": "SELLER", "metric": "GROSS_SALES",
		"threshold": 100000, "windowDays": nil, "withinDaysOfApproval": 60, "comparison": "AT_LEAST", "windowAnchor": "APPROVAL",
		"checkAtWindowEnd": false, "checkLeadHours": 24, "rewardType": "FREE_DAYS", "freeDays": 30, "creditCents": nil,
		"percentOff": nil, "durationInvoices": nil, "eligiblePlans": []string{"seller_growth"}, "startsAt": nil, "endsAt": nil, "maxGrants": 100,
		"grantsCount": 4, "rewardLabel": "1 month free", "conditionLabel": "$1,000 in sales within 60 days of approval", "createdBy": "stf-finance",
		"createdAt": "2026-10-01T10:00:00Z", "updatedBy": "stf-finance", "updatedAt": "2026-10-01T10:00:00Z", "version": 1}
}

func samplePanel() map[string]any {
	return map[string]any{"orgId": "org_1", "orgName": "Mama Put", "kind": "SELLER", "orgStatus": "APPROVED",
		"billing": map[string]any{"orgId": "org_1", "planCode": "seller_growth", "planName": "Seller Growth", "cadence": "MONTHLY", "status": "TRIALING",
			"trialEndsAt": "2026-11-09T10:00:00Z", "currentPeriodEnd": "2026-11-09T10:00:00Z", "nextChargeAt": "2026-11-09T10:00:00Z",
			"nextChargeCents": 3900, "nextChargeLabel": "Free trial ends Nov 9, 2026", "cancelAtPeriodEnd": false, "dunning": "NONE",
			"failedPaymentAttempts": 0, "planVersion": 2, "creditBalanceCents": 2500, "trialSource": "CAMPAIGN", "trialDaysOverride": nil,
			"planFeeCollection": "EARNINGS_FIRST", "price": map[string]any{"cadence": "MONTHLY"}, "paymentMethods": []any{}},
		"invoices": []any{map[string]any{"id": "in_1", "number": "AT-0001", "periodStart": nil, "periodEnd": nil, "amountDueCents": 3900,
			"amountPaidCents": 0, "currency": "usd", "status": "open", "billingReason": "subscription_cycle", "hostedInvoiceUrl": nil,
			"invoicePdf": nil, "createdAt": "2026-10-09T10:00:00Z", "lines": []any{}, "meteredAmountCents": 0, "paidFromSalesCents": 2500,
			"paidByCardCents": 1400}},
		"grants": []any{sampleGrant("CREDIT")}, "collections": []any{map[string]any{"invoiceId": "in_1", "invoiceNumber": "AT-0001",
			"invoiceAmountCents": 3900, "amountCents": 3900, "withheldCents": 2500, "creditedCents": 2500, "releasedCents": 0,
			"paidFromSalesCents": 2500, "status": "CREDITED", "paymentsReference": "wh_1", "stripeBalanceTransactionId": "cbtxn_9",
			"reason": "Only $25.00 was available", "attempts": 1, "lastError": nil, "createdAt": "2026-10-09T10:00:00Z",
			"updatedAt": "2026-10-09T10:01:00Z"}},
		"earningsCollectionEnabled": true, "planFeeCollection": samplePlanFeeCollection("EARNINGS_FIRST", "DEFAULT")}
}

// samplePlanFeeCollection is seller's PlanFeeCollection (S298).
func samplePlanFeeCollection(mode, source string) map[string]any {
	history := []any{}
	if source == "STAFF" {
		history = append(history, map[string]any{"id": "0f0e0d0c-0000-4000-8000-000000000001", "previous": "EARNINGS_FIRST", "mode": mode,
			"source": "STAFF", "actorId": "stf-finance", "reasonCode": "SELLER_REQUEST", "note": "Asked by phone",
			"appliesFrom": "2026-11-09T10:00:00Z", "createdAt": "2026-10-12T10:00:00Z"})
	}
	return map[string]any{"orgId": "org_1", "available": true, "mode": mode, "effective": mode, "source": source, "changedAt": nil,
		"changedBy": nil, "platformDefault": "EARNINGS_FIRST", "appliesFromInvoiceAt": "2026-11-09T10:00:00Z",
		"appliesFromLabel": "Applies from your next invoice, on Nov 9, 2026", "inProgressInvoiceId": nil, "history": history}
}

func (f *fakeSellerBilling) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	f.calls = append(f.calls, r.Method+" "+p+"?"+r.URL.RawQuery)
	f.idem[r.Method+" "+p] = r.Header.Get("Idempotency-Key")
	body := map[string]any{}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	f.bodies[r.Method+" "+p] = body
	key := r.Method + " " + p
	switch {
	case p == "/v1/me":
		who, ok := plansStaff[token]
		if !ok {
			writeJ(w, 200, map[string]any{"id": "cus-1", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
			return
		}
		writeJ(w, 200, map[string]any{"id": "stf-" + token, "firstName": "Test", "lastName": token, "email": token + "@afrotransact.test",
			"principalType": "STAFF", "roles": who[0], "permissions": who[1]})
	case p == "/v1/admin/module-access":
		problemJ(w, 403, "identity.forbidden")
	case key == "GET /v1/billing/plans":
		writeJ(w, 200, []any{samplePlan("seller_growth", "ACTIVE")})
	case key == "POST /v1/billing/plans":
		if body["code"] == "Bad Code" {
			writeJ(w, 422, map[string]any{"status": 422, "code": "seller.invalid-plan", "detail": "Check the plan: code",
				"fieldErrors": map[string]string{"code": "3–40 lower-case letters"}})
			return
		}
		writeJ(w, 201, samplePlan("seller_new", "DRAFT"))
	case strings.HasPrefix(p, "/v1/billing/plans/") && strings.HasSuffix(p, "/migration") && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"planCode": "seller_growth", "toVersion": 2, "rows": []any{map[string]any{"orgId": "org_1", "orgName": "Mama Put",
			"fromVersion": 1, "cadence": "MONTHLY", "fromCents": 3500, "toCents": 3900, "status": "ACTIVE", "effectiveAt": "2026-11-01T00:00:00Z"}}})
	case strings.HasPrefix(p, "/v1/billing/plans/") && strings.HasSuffix(p, "/migration"):
		writeJ(w, 200, map[string]any{"planCode": "seller_growth", "toVersion": 2, "migrated": []string{"org_1"}, "failed": []any{}})
	case p == "/v1/billing/plans/nope":
		problemJ(w, 404, "seller.plan-not-found")
	case strings.HasPrefix(p, "/v1/billing/plans/seller_growth"):
		if strings.HasSuffix(p, "/publish") && r.URL.Query().Get("x") == "" && body["conflict"] != nil {
			problemJ(w, 409, "seller.nothing-to-publish")
			return
		}
		writeJ(w, 200, samplePlan("seller_growth", "ACTIVE"))
	case p == "/v1/billing/stripe-sync":
		writeJ(w, 200, map[string]any{"checkedAt": "2026-10-11T12:00:00Z", "applied": r.Method == http.MethodPost, "drifted": 1, "lines": []any{
			map[string]any{"planCode": "seller_growth", "planName": "Seller Growth", "version": 2, "cadence": "ANNUAL", "amountCents": 39000,
				"lookupKey": "seller_growth_annual", "state": "MISSING", "action": "NONE", "stripePriceId": nil, "stripeAmountCents": nil, "error": nil}}})
	case key == "GET /v1/billing/trial-campaigns":
		writeJ(w, 200, []any{sampleCampaign()})
	case key == "POST /v1/billing/trial-campaigns":
		writeJ(w, 201, sampleCampaign())
	case strings.HasPrefix(p, "/v1/billing/trial-campaigns/"):
		writeJ(w, 200, sampleCampaign())
	case key == "GET /v1/billing/reward-rules":
		writeJ(w, 200, []any{sampleRule()})
	case key == "POST /v1/billing/reward-rules":
		writeJ(w, 201, sampleRule())
	case p == "/v1/billing/reward-rules/preview":
		writeJ(w, 200, map[string]any{"evaluated": 3, "qualifying": 1, "pending": 1, "rows": []any{
			map[string]any{"orgId": "org_1", "orgName": "Mama Put", "planCode": "seller_growth", "value": 120000, "threshold": 100000, "percent": 100,
				"qualifies": true, "alreadyGranted": false, "reason": nil, "status": "QUALIFIES", "checkAt": nil},
			// S299: in the window of a rule checked at window end.
			map[string]any{"orgId": "org_3", "orgName": "Bisi's", "planCode": "seller_growth", "value": 20000, "threshold": 100000, "percent": 20,
				"qualifies": false, "alreadyGranted": false, "reason": nil, "status": "PENDING", "checkAt": "2026-11-08T10:00:00Z"},
			map[string]any{"orgId": "org_2", "orgName": "Ade's", "planCode": "seller_growth", "value": 64000, "threshold": 100000, "percent": 64,
				"qualifies": false, "alreadyGranted": false, "reason": nil, "status": "NOT_YET", "checkAt": nil}}})
	case strings.HasPrefix(p, "/v1/billing/reward-rules/"):
		writeJ(w, 200, sampleRule())
	case p == "/v1/billing/grants":
		writeJ(w, 200, map[string]any{"items": []any{sampleGrant("CREDIT")}, "page": 0, "size": 25, "total": 1})
	case p == "/v1/billing/reason-codes":
		writeJ(w, 200, []any{map[string]any{"code": "GOODWILL", "label": "Goodwill gesture"}, map[string]any{"code": "OTHER", "label": "Other (see note)"}})
	case p == "/v1/orgs/directory":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"id": "org_1", "displayName": "Mama Put", "kind": "seller", "status": "approved"},
			map[string]any{"id": "org_2", "displayName": "Ade's Kitchen", "kind": "seller", "status": "approved"}}, "total": 2})
	case p == "/v1/orgs/org_1/billing/panel":
		writeJ(w, 200, samplePanel())
	case key == "PUT /v1/orgs/org_1/billing/plan-fee-collection/override":
		switch {
		case body["reasonCode"] == nil:
			writeJ(w, 422, map[string]any{"status": 422, "code": "seller.invalid-plan-fee-collection", "detail": "Choose a reason",
				"fieldErrors": map[string]string{"reasonCode": "Choose a reason"}})
		case body["mode"] == "EARNINGS_FIRST" && body["note"] == "off":
			problemJ(w, 409, "seller.earnings-collection-off")
		default:
			writeJ(w, 200, samplePlanFeeCollection(body["mode"].(string), "STAFF"))
		}
	case p == "/v1/billing/settings":
		def := "EARNINGS_FIRST"
		if r.Method == http.MethodPut {
			if body["planFeeCollectionDefault"] != "CARD" && body["planFeeCollectionDefault"] != "EARNINGS_FIRST" {
				writeJ(w, 422, map[string]any{"status": 422, "code": "seller.invalid-plan-fee-collection", "detail": "Choose EARNINGS_FIRST or CARD",
					"fieldErrors": map[string]string{"planFeeCollectionDefault": "EARNINGS_FIRST or CARD"}})
				return
			}
			def = body["planFeeCollectionDefault"].(string)
		}
		writeJ(w, 200, map[string]any{"earningsCollectionEnabled": true, "planFeeCollectionDefault": def, "updatedBy": "stf-finance",
			"updatedAt": "2026-10-12T10:00:00Z"})
	case p == "/v1/orgs/org_404/billing/panel":
		problemJ(w, 404, "seller.org-not-found")
	case strings.HasPrefix(p, "/v1/orgs/org_1/billing/"):
		if body["reasonCode"] == nil {
			writeJ(w, 422, map[string]any{"status": 422, "code": "seller.invalid-adjustment", "detail": "Choose a reason",
				"fieldErrors": map[string]string{"reasonCode": "Choose a reason"}})
			return
		}
		typ := map[string]string{"credits": "CREDIT", "free-days": "FREE_DAYS", "trial": "TRIAL_END"}[p[strings.LastIndex(p, "/")+1:]]
		if strings.HasSuffix(p, "/waive") {
			typ = "WAIVE_INVOICE"
		}
		writeJ(w, 201, sampleGrant(typ))
	default:
		problemJ(w, 404, "not-found")
	}
}

func plansBFF(t *testing.T) (*fakeSellerBilling, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder) {
	t.Helper()
	f := &fakeSellerBilling{bodies: map[string]map[string]any{}, idem: map[string]string{}}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Seller: up("seller")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Log:    log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Plans: app.NewStaffPlansService(d)}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Sessions: staffSessions{}, Staff: staff}, nil, httpapi.SecurityOptions{AllowedOrigins: []string{"http://localhost:3002"}}, log).Register(mux)
	return f, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder {
		var rd io.Reader
		if body != nil {
			raw, _ := json.Marshal(body)
			rd = bytes.NewReader(raw)
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, rd)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		for i := 0; i+1 < len(headers); i += 2 {
			req.Header.Set(headers[i], headers[i+1])
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}
}

// TestStaffPlansAndBilling drives every Plans & billing operation through the BFF (S292–S297): the module's access
// (Finance edits, an auditor reads, support has none), the permission each change needs, the relayed bodies and
// Idempotency-Keys, validation from the seller service, and every answer checked against gateway-bff-staff.yaml.
func TestStaffPlansAndBilling(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f, raw := plansBFF(t)
	validated := map[string]int{}
	do := func(token, method, path string, body any, want int, headers ...string) map[string]any {
		t.Helper()
		rec := raw(token, method, path, body, headers...)
		if rec.Code != want {
			t.Fatalf("%s %s as %s = %d, want %d: %s", method, path, token, rec.Code, want, rec.Body.String())
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, nil)
		route, params, err := router.FindRoute(req)
		if err != nil {
			t.Fatalf("%s %s is not in the contract: %v", method, path, err)
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document", method, path, rec.Code)
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
		}
		validated[route.Operation.OperationID]++
		out := map[string]any{}
		_ = json.Unmarshal(rec.Body.Bytes(), &out)
		return out
	}

	// The sidebar shows Plans & billing to Finance (EDIT) and the auditor (VIEW), not to support.
	for token, want := range map[string]string{"finance": "EDIT", "auditor": "VIEW", "support": ""} {
		level := ""
		for _, g := range jsonOf(t, raw(token, "GET", "/v1/staff/session", nil))["navGroups"].([]any) {
			for _, m := range g.(map[string]any)["modules"].([]any) {
				if mm := m.(map[string]any); mm["key"] == "plans" {
					level = mm["level"].(string)
				}
			}
		}
		if level != want {
			t.Errorf("%s sees Plans & billing at %q, want %q", token, level, want)
		}
	}

	// Catalogue.
	do("finance", "GET", "/v1/staff/billing/plans", nil, 200)
	do("auditor", "GET", "/v1/staff/billing/plans", nil, 200)
	do("support", "GET", "/v1/staff/billing/plans", nil, 403)
	do("auditor", "POST", "/v1/staff/billing/plans", map[string]any{"code": "seller_new"}, 403)
	created := do("finance", "POST", "/v1/staff/billing/plans", map[string]any{"code": "seller_new", "kind": "SELLER", "name": "New",
		"prices": map[string]any{"monthlyCents": 2500, "quarterlyCents": 7125, "annualCents": 25000, "trialDays": 14}}, 201)
	if created["status"] != "DRAFT" {
		t.Errorf("created = %v", created)
	}
	if b := f.bodies["POST /v1/billing/plans"]; b["code"] != "seller_new" || b["prices"].(map[string]any)["trialDays"] != float64(14) {
		t.Errorf("body relayed to seller = %v", b)
	}
	bad := do("finance", "POST", "/v1/staff/billing/plans", map[string]any{"code": "Bad Code", "kind": "SELLER", "name": "x"}, 422)
	if !strings.Contains(mustJSON(t, bad), "lower-case") {
		t.Errorf("validation from seller not relayed: %v", bad)
	}
	do("finance", "GET", "/v1/staff/billing/plans/seller_growth", nil, 200)
	do("finance", "GET", "/v1/staff/billing/plans/nope", nil, 404)
	do("finance", "PUT", "/v1/staff/billing/plans/seller_growth", map[string]any{"name": "Seller Growth", "expectedVersion": 4}, 200)
	do("finance", "PUT", "/v1/staff/billing/plans/seller_growth/draft", map[string]any{"monthlyCents": 4900, "quarterlyCents": 13965,
		"annualCents": 49000, "trialDays": 30}, 200)
	do("finance", "POST", "/v1/staff/billing/plans/seller_growth/publish", nil, 200)
	do("finance", "POST", "/v1/staff/billing/plans/seller_growth/retire", nil, 200)
	do("finance", "PUT", "/v1/staff/billing/plans/seller_growth/invites", map[string]any{"orgIds": []string{"org_1"}}, 200)
	do("auditor", "GET", "/v1/staff/billing/plans/seller_growth/migration", nil, 200)
	do("auditor", "POST", "/v1/staff/billing/plans/seller_growth/migration", map[string]any{}, 403)
	do("finance", "POST", "/v1/staff/billing/plans/seller_growth/migration", map[string]any{"orgIds": []string{"org_1"}}, 200)
	do("auditor", "GET", "/v1/staff/billing/stripe-sync", nil, 200)
	do("auditor", "POST", "/v1/staff/billing/stripe-sync", nil, 403)
	if synced := do("finance", "POST", "/v1/staff/billing/stripe-sync", nil, 200); synced["applied"] != true {
		t.Errorf("sync = %v", synced)
	}

	// Trials and rewards.
	do("auditor", "GET", "/v1/staff/billing/trial-campaigns", nil, 200)
	do("finance", "POST", "/v1/staff/billing/trial-campaigns", map[string]any{"name": "October 60", "promoCode": "OCT60", "trialDays": 60}, 201)
	do("finance", "PUT", "/v1/staff/billing/trial-campaigns/"+testCampaignID, map[string]any{"name": "October 60", "trialDays": 60,
		"expectedVersion": 0}, 200)
	do("finance", "POST", "/v1/staff/billing/trial-campaigns/"+testCampaignID+"/status", map[string]any{"status": "PAUSED"}, 200)
	do("auditor", "GET", "/v1/staff/billing/reward-rules", nil, 200)
	rule := map[string]any{"name": "First $1,000", "metric": "GROSS_SALES", "threshold": 100000, "withinDaysOfApproval": 60,
		"rewardType": "FREE_DAYS", "freeDays": 30}
	do("finance", "POST", "/v1/staff/billing/reward-rules", rule, 201)
	preview := do("auditor", "POST", "/v1/staff/billing/reward-rules/preview", rule, 200)
	if preview["qualifying"] != float64(1) || preview["pending"] != float64(1) {
		t.Errorf("preview = %v", preview)
	}
	// S299: low-sales relief - less than $1,000 in the first 30 days of the subscription, checked 24 h before the trial ends.
	relief := map[string]any{"name": "Low-sales relief", "metric": "GROSS_SALES", "threshold": 100000, "comparison": "LESS_THAN",
		"windowAnchor": "SUBSCRIPTION_START", "withinDaysOfApproval": 30, "checkLeadHours": 24, "rewardType": "FREE_DAYS", "freeDays": 30}
	do("finance", "POST", "/v1/staff/billing/reward-rules/preview", relief, 200)
	do("finance", "POST", "/v1/staff/billing/reward-rules", relief, 201)
	do("finance", "PUT", "/v1/staff/billing/reward-rules/"+testRuleID, rule, 200)
	do("finance", "POST", "/v1/staff/billing/reward-rules/"+testRuleID+"/status", map[string]any{"status": "ENDED"}, 200)

	// Grants log (server-paged; filters relayed).
	do("auditor", "GET", "/v1/staff/billing/grants?type=CREDIT&page=1&size=25&q=mama&ignored=1", nil, 200)
	if last := f.calls[len(f.calls)-1]; !strings.Contains(last, "type=CREDIT") || !strings.Contains(last, "q=mama") || strings.Contains(last, "ignored") {
		t.Errorf("grants query relayed = %s", last)
	}
	do("auditor", "GET", "/v1/staff/billing/grants?size=500", nil, 422)
	do("auditor", "GET", "/v1/staff/billing/reason-codes", nil, 200)

	// Per-seller panel and adjustments.
	hits := do("auditor", "GET", "/v1/staff/billing/orgs?q=mama", nil, 200)
	if items := hits["items"].([]any); len(items) != 1 || items[0].(map[string]any)["orgId"] != "org_1" {
		t.Errorf("org search = %v", hits)
	}
	do("auditor", "GET", "/v1/staff/billing/orgs?q=m", nil, 422)
	do("auditor", "GET", "/v1/staff/billing/orgs/org_1", nil, 200)
	do("auditor", "GET", "/v1/staff/billing/orgs/org_404", nil, 404)
	do("auditor", "POST", "/v1/staff/billing/orgs/org_1/credits", map[string]any{"amountCents": 2500, "reasonCode": "GOODWILL"}, 403)
	do("finance", "POST", "/v1/staff/billing/orgs/org_1/credits", map[string]any{"amountCents": 2500, "reasonCode": "GOODWILL"}, 201,
		"Idempotency-Key", "adj-0123456789")
	if got := f.idem["POST /v1/orgs/org_1/billing/credits"]; got != "adj-0123456789" {
		t.Errorf("Idempotency-Key relayed = %q", got)
	}
	do("finance", "POST", "/v1/staff/billing/orgs/org_1/credits", map[string]any{"amountCents": 2500}, 422)
	do("finance", "POST", "/v1/staff/billing/orgs/org_1/free-days", map[string]any{"days": 30, "reasonCode": "RETENTION"}, 201)
	do("finance", "POST", "/v1/staff/billing/orgs/org_1/trial", map[string]any{"days": 45, "reasonCode": "ONBOARDING_DELAY"}, 201)
	do("finance", "POST", "/v1/staff/billing/orgs/org_1/invoices/in_1/waive", map[string]any{"reasonCode": "BILLING_ERROR"}, 201)

	// How the seller pays its plan (S298): a staff override with a reason, and the platform default.
	panel := do("auditor", "GET", "/v1/staff/billing/orgs/org_1", nil, 200)
	if pfc := panel["planFeeCollection"].(map[string]any); pfc["mode"] != "EARNINGS_FIRST" {
		t.Errorf("panel plan fee collection = %v", pfc)
	}
	do("auditor", "PUT", "/v1/staff/billing/orgs/org_1/plan-fee-collection", map[string]any{"mode": "CARD", "reasonCode": "SELLER_REQUEST"}, 403)
	do("finance", "PUT", "/v1/staff/billing/orgs/org_1/plan-fee-collection", map[string]any{"mode": "CARD"}, 422)
	do("finance", "PUT", "/v1/staff/billing/orgs/org_1/plan-fee-collection", map[string]any{"mode": "EARNINGS_FIRST", "reasonCode": "OTHER",
		"note": "off"}, 409)
	overridden := do("finance", "PUT", "/v1/staff/billing/orgs/org_1/plan-fee-collection", map[string]any{"mode": "CARD",
		"reasonCode": "SELLER_REQUEST", "note": "Asked by phone"}, 200)
	if overridden["source"] != "STAFF" || f.bodies["PUT /v1/orgs/org_1/billing/plan-fee-collection/override"]["reasonCode"] != "SELLER_REQUEST" {
		t.Errorf("override = %v, relayed %v", overridden, f.bodies["PUT /v1/orgs/org_1/billing/plan-fee-collection/override"])
	}
	do("auditor", "GET", "/v1/staff/billing/settings", nil, 200)
	do("support", "GET", "/v1/staff/billing/settings", nil, 403)
	do("auditor", "PUT", "/v1/staff/billing/settings", map[string]any{"planFeeCollectionDefault": "CARD"}, 403)
	do("finance", "PUT", "/v1/staff/billing/settings", map[string]any{"planFeeCollectionDefault": "SOMETIMES"}, 422)
	if s := do("finance", "PUT", "/v1/staff/billing/settings", map[string]any{"planFeeCollectionDefault": "CARD"}, 200); s["planFeeCollectionDefault"] != "CARD" {
		t.Errorf("settings = %v", s)
	}

	// Every Plans & billing operation of the contract was exercised.
	for path, item := range doc.Paths.Map() {
		for _, op := range item.Operations() {
			if len(op.Tags) > 0 && op.Tags[0] == "Plans & billing" && validated[op.OperationID] == 0 {
				t.Errorf("contract operation %s (%s) is not exercised", op.OperationID, path)
			}
		}
	}
	if len(app.StaffPlanOps)+1 != countTagged(doc, "Plans & billing") {
		t.Errorf("app.StaffPlanOps (%d + org search) and the contract's Plans & billing operations (%d) differ", len(app.StaffPlanOps),
			countTagged(doc, "Plans & billing"))
	}
}

func countTagged(doc *openapi3.T, tag string) int {
	n := 0
	for _, item := range doc.Paths.Map() {
		for _, op := range item.Operations() {
			if len(op.Tags) > 0 && op.Tags[0] == tag {
				n++
			}
		}
	}
	return n
}
