package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// feeRatesPlatform adds payments' negotiated rates and effective fees and the seller service's plan catalogue to the
// fake staff platform (fee schedules come from fakePlatform: v1 in force without plan rates).
func feeRatesPlatform(f *fakePlatform) {
	until := time.Now().UTC().Add(60 * 24 * time.Hour).Format(time.RFC3339)
	rate := func(id, status string) map[string]any {
		return map[string]any{"id": id, "orgId": "org-1", "type": "COMMISSION", "category": "groceries", "rateBps": 600,
			"startsAt": "2026-10-01T00:00:00Z", "endsAt": until, "status": status, "reason": "Wholesale partner", "negotiatedBy": "Ada",
			"reference": "SF-1001", "createdBy": "stf-1", "createdAt": "2026-10-01T00:00:00Z", "endedAt": nil, "endedBy": nil, "endReason": nil,
			"expiredAt": nil, "rowVersion": 0, "audit": []any{map[string]any{"action": "CREATED", "actor": "stf-1", "reason": "Wholesale partner", "at": "2026-10-01T00:00:00Z"}}}
	}
	f.override = func(w http.ResponseWriter, r *http.Request, body map[string]any) bool {
		p := r.URL.Path
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		switch {
		case p == "/v1/me" && token == "plans-finance":
			writeJ(w, 200, map[string]any{"id": "stf-3", "firstName": "Bola", "lastName": "Ade", "email": "bola@afrotransact.com", "principalType": "STAFF",
				"roles": []string{"platform.super_admin"}, "permissions": append(append([]string{}, adminPerms...), "plans:manage", "billing:adjust")})
		case p == "/v1/me" && token == "plans-only":
			writeJ(w, 200, map[string]any{"id": "stf-4", "firstName": "Chi", "lastName": "Eze", "email": "chi@afrotransact.com", "principalType": "STAFF",
				"roles": []string{"platform.super_admin"}, "permissions": append(append([]string{}, adminPerms...), "plans:manage")})
		case p == "/v1/orgs/org-1/billing/staff-plan" && r.Method == http.MethodPost:
			writeJ(w, 200, map[string]any{"applied": "AT_RENEWAL", "billing": map[string]any{"orgId": "org-1"}})
		case p == "/v1/negotiated-rates" && r.Method == http.MethodGet:
			writeJ(w, 200, []any{rate("nr-1", "ACTIVE"), rate("nr-0", "EXPIRED")})
		case p == "/v1/negotiated-rates" && r.Method == http.MethodPost:
			if body["reason"] == "overlap" {
				problemDetail(w, 409, "payments.negotiated-rate-overlap", "A negotiated commission for groceries already applies")
				return true
			}
			out := rate("nr-2", "ACTIVE")
			out["orgId"], out["type"], out["rateBps"] = body["orgId"], body["type"], body["rateBps"]
			writeJ(w, 201, out)
		case strings.HasPrefix(p, "/v1/negotiated-rates/") && strings.HasSuffix(p, "/end"):
			out := rate("nr-1", "ENDED")
			out["endedAt"], out["endedBy"], out["endReason"] = time.Now().UTC().Format(time.RFC3339), "stf-1", body["reason"]
			writeJ(w, 200, out)
		case p == "/v1/fee-schedules/effective":
			writeJ(w, 200, map[string]any{"feeScheduleId": "fee-1", "version": 1, "effectiveFrom": "2020-01-01T00:00:00Z", "currency": "USD",
				"category": r.URL.Query().Get("category"), "orgId": r.URL.Query().Get("orgId"), "commissionBps": 800, "commissionSource": "PLAN",
				"cardPctBps": 290, "cardFixedMinor": 30, "fbaPerUnitMinor": 0, "reverseCommissionOnRefund": true, "kind": r.URL.Query().Get("kind"),
				"planCode": "provider_studio", "negotiatedRateId": nil, "negotiatedUntil": nil})
		case p == "/v1/billing/plans" && r.Method == http.MethodGet:
			writeJ(w, 200, []any{
				map[string]any{"code": "seller_essentials", "kind": "SELLER", "name": "Seller Essentials", "status": "ACTIVE", "visibility": "PUBLIC",
					"features": []string{"Up to 50 listings", "15% referral fee"}},
				map[string]any{"code": "provider_studio", "kind": "PROVIDER", "name": "Provider Studio", "status": "ACTIVE", "visibility": "PUBLIC",
					"features": []string{"8% booking fee"}}})
		case p == "/v1/billing/plans/seller_growth" && r.Method == http.MethodGet:
			writeJ(w, 200, map[string]any{"code": "seller_growth", "kind": "SELLER", "name": "Seller Growth", "features": []string{"3 users"},
				"limits": map[string]any{}, "trialDays": 30, "sortOrder": 20})
		case p == "/v1/billing/plans" && r.Method == http.MethodPost:
			writeJ(w, 201, map[string]any{"code": body["code"]})
		case strings.HasSuffix(p, "/invites") || strings.HasSuffix(p, "/publish"):
			writeJ(w, 200, map[string]any{"code": "x"})
		default:
			return false
		}
		return true
	}
}

// TestStaffFeeRatesConformance drives plan rates, connection fees, negotiated rates, the rate calculator and the
// negotiated plan price through the real handlers and validates every answer against gateway-bff-staff.yaml.
func TestStaffFeeRatesConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakePlatform{payoutSt: "REQUESTED"}
	feeRatesPlatform(f)
	raw := staffBFF(t, f)
	validated := map[string]int{}
	do := func(token, method, path string, body any, want int) map[string]any {
		t.Helper()
		rec := raw(token, method, path, body)
		if rec.Code != want {
			t.Fatalf("%s %s = %d, want %d: %s", method, path, rec.Code, want, rec.Body.String())
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
		return jsonOf(t, rec)
	}

	// Overview: the plan matrix with the feature-text check (no plan rates configured in v1 → warnings).
	ov := do("admin", "GET", "/v1/staff/fees", nil, 200)
	if ov["plansAvailable"] != true || len(ov["plans"].([]any)) != 2 || len(ov["planWarnings"].([]any)) != 2 {
		t.Fatalf("plans = %v / %v", ov["plans"], ov["planWarnings"])
	}
	essentials := ov["plans"].([]any)[0].(map[string]any)
	if essentials["code"] != "seller_essentials" || essentials["advertised"].(map[string]any)["minBps"] != float64(1500) {
		t.Fatalf("essentials = %v", essentials)
	}

	// A version with plan rates and a connection-fee section.
	draft := map[string]any{"effectiveFrom": time.Now().UTC().Add(20 * 24 * time.Hour).Format(time.RFC3339), "defaultCommissionBps": 1000,
		"categoryRates": []any{}, "overrides": []any{}, "cardPctBps": 290, "cardFixedMinor": 30, "fbaPerUnitMinor": 325, "reverseCommissionOnRefund": true,
		"note": "Plan rates", "planRates": []any{map[string]any{"planCode": "seller_essentials", "category": "*", "bps": 1500}},
		"connectionFee": map[string]any{"defaultBps": 1000, "categoryRates": []any{map[string]any{"category": "Hair & Beauty", "bps": 900}},
			"planRates": []any{map[string]any{"planCode": "provider_studio", "category": "", "bps": 800}}}}
	created := do("admin", "POST", "/v1/staff/fees/schedules", draft, 201)
	if cf := created["connectionFee"].(map[string]any); cf["defaultBps"] != float64(1000) || len(cf["planRates"].([]any)) != 1 {
		t.Fatalf("connection fee = %v", created["connectionFee"])
	}
	if pr := created["planRates"].([]any); len(pr) != 1 || pr[0].(map[string]any)["planName"] != "Seller Essentials" {
		t.Fatalf("plan rates = %v", pr)
	}
	if b := f.bodies["POST /v1/fee-schedules"]; b["planBps"].(map[string]any)["seller_essentials"].(map[string]any)["*"] != float64(1500) ||
		b["connectionFee"].(map[string]any)["categoryBps"].(map[string]any)["hair & beauty"] != float64(900) {
		t.Fatalf("payments body = %v", b)
	}
	bad := map[string]any{}
	for k, v := range draft {
		bad[k] = v
	}
	bad["planRates"] = []any{map[string]any{"planCode": "Not A Code!", "category": "*", "bps": 9000}}
	if e := do("admin", "POST", "/v1/staff/fees/schedules", bad, 422)["errors"].(map[string]any); e["planRates.0.planCode"] == nil || e["planRates.0.bps"] == nil {
		t.Fatalf("plan rate errors = %v", e)
	}

	// Negotiated rates: list (filtered, searched), create with a required reason, overlap, end.
	page := do("admin", "GET", "/v1/staff/fees/negotiated-rates?orgId=org-1&q=wholesale&size=1", nil, 200)
	if page["total"] != float64(2) || len(page["items"].([]any)) != 1 || page["canConfigure"] != true {
		t.Fatalf("page = %v", page)
	}
	first := page["items"].([]any)[0].(map[string]any)
	if first["orgName"] != "Kente Naturals" || first["canEnd"] != true || len(first["audit"].([]any)) != 1 {
		t.Fatalf("first = %v", first)
	}
	do("admin", "GET", "/v1/staff/fees/negotiated-rates?status=NOPE", nil, 422)
	do("support", "GET", "/v1/staff/fees/negotiated-rates", nil, 403)
	nr := map[string]any{"orgId": "org-1", "type": "COMMISSION", "category": "groceries", "rateBps": 600, "reason": "Wholesale partner",
		"endsAt": time.Now().UTC().Add(90 * 24 * time.Hour).Format(time.RFC3339), "negotiatedBy": "Ada", "reference": "SF-1001"}
	if v := do("admin", "POST", "/v1/staff/fees/negotiated-rates", nr, 201); v["id"] != "nr-2" || v["orgName"] != "Kente Naturals" {
		t.Fatalf("created = %v", v)
	}
	if b := f.bodies["POST /v1/negotiated-rates"]; b["reason"] != "Wholesale partner" || b["reference"] != "SF-1001" || b["category"] != "groceries" {
		t.Fatalf("payments body = %v", b)
	}
	noReason := map[string]any{"orgId": "org-retail", "type": "OTHER", "rateBps": 9000, "reason": ""}
	if e := do("admin", "POST", "/v1/staff/fees/negotiated-rates", noReason, 422)["errors"].(map[string]any); e["reason"] == nil || e["orgId"] == nil || e["type"] == nil || e["rateBps"] == nil {
		t.Fatalf("errors = %v", e)
	}
	nr["reason"] = "overlap"
	do("admin", "POST", "/v1/staff/fees/negotiated-rates", nr, 409)
	do("auditor", "POST", "/v1/staff/fees/negotiated-rates", nr, 403)
	if v := do("admin", "POST", "/v1/staff/fees/negotiated-rates/nr-1/end", map[string]any{"reason": "Partner left"}, 200); v["status"] != "ENDED" || v["canEnd"] != false {
		t.Fatalf("ended = %v", v)
	}
	do("admin", "POST", "/v1/staff/fees/negotiated-rates/nr-1/end", map[string]any{"reason": ""}, 422)

	// Rate calculator.
	q := do("auditor", "GET", "/v1/staff/fees/calculator?orgId=org-1&category=Massage&kind=SERVICE", nil, 200)
	if q["bps"] != float64(800) || q["source"] != "PLAN" || q["planName"] != "Provider Studio" || q["exampleFeeMinor"] != float64(800) ||
		q["explanation"] != "Plan rate: Provider Studio" || q["category"] != "massage" {
		t.Fatalf("quote = %v", q)
	}
	do("auditor", "GET", "/v1/staff/fees/calculator?orgId=org-1&category=x&kind=RENTAL", nil, 422)

	// Negotiated plan price: a private invite-only plan for one organisation (plans:manage).
	np := map[string]any{"basePlanCode": "seller_growth", "monthlyCents": 2900, "reason": "Annual contract with Kente", "reference": "SF-1002"}
	do("admin", "POST", "/v1/staff/fees/orgs/org-1/negotiated-plan", np, 403)
	plan := do("plans-finance", "POST", "/v1/staff/fees/orgs/org-1/negotiated-plan", np, 201)
	if plan["planCode"] != "seller_growth_org_1" || plan["quarterlyCents"] != float64(8265) || plan["annualCents"] != float64(29000) || plan["published"] != true {
		t.Fatalf("plan = %v", plan)
	}
	if b := f.bodies["POST /v1/billing/plans"]; b["visibility"] != "INVITE_ONLY" || b["name"] != "Seller Growth · Kente Naturals" || b["kind"] != "SELLER" {
		t.Fatalf("create plan = %v", b)
	}
	if b := f.bodies["PUT /v1/billing/plans/seller_growth_org_1/invites"]; b["orgIds"].([]any)[0] != "org-1" {
		t.Fatalf("invites = %v", b)
	}
	// S306: the same step can also move the organisation onto the plan (billing:adjust).
	np["assign"] = true
	do("plans-only", "POST", "/v1/staff/fees/orgs/org-1/negotiated-plan", np, 403)
	assigned := do("plans-finance", "POST", "/v1/staff/fees/orgs/org-1/negotiated-plan", np, 201)
	if assigned["assigned"] != true || assigned["assignedAs"] != "AT_RENEWAL" {
		t.Fatalf("assigned plan = %v", assigned)
	}
	if b := f.bodies["POST /v1/orgs/org-1/billing/staff-plan"]; b["planCode"] != "seller_growth_org_1" || b["reason"] != "Annual contract with Kente" {
		t.Fatalf("staff-plan = %v", b)
	}
	if e := do("plans-finance", "POST", "/v1/staff/fees/orgs/org-retail/negotiated-plan", map[string]any{"basePlanCode": "seller_growth"}, 422)["errors"].(map[string]any); e["orgId"] == nil || e["monthlyCents"] == nil || e["reason"] == nil {
		t.Fatalf("errors = %v", e)
	}

	for _, op := range []string{"getStaffFees", "createFeeSchedule", "listNegotiatedRates", "createNegotiatedRate", "endNegotiatedRate",
		"calculateFeeRate", "negotiatePlanPrice"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not exercised", op)
		}
	}
}

// TestSellerFeesShowPlanNegotiatedAndConnectionFees: Seller Center's fees page names the plan and the negotiated rates,
// Provider Hub shows the connection fee before a booking is accepted and on earnings.
func TestSellerFeesShowPlanNegotiatedAndConnectionFees(t *testing.T) {
	_, b := sellerSetup(t, testkit.Options{SellerPending: true})
	fees := b.do("GET", "/v1/seller/fees", nil).json(t)
	if fees["planName"] != "Seller Growth" || len(fees["negotiated"].([]any)) != 1 {
		t.Fatalf("fees = %v", fees)
	}
	cf := fees["connectionFees"].(map[string]any)
	if cf["kind"] != "SERVICE" || cf["other"].(map[string]any)["bps"] != float64(800) || cf["planName"] != "Provider Studio" {
		t.Fatalf("connection fees = %v", cf)
	}
	for _, c := range fees["current"].(map[string]any)["categories"].([]any) {
		cat := c.(map[string]any)
		if cat["category"] == "groceries" && cat["source"] != "OVERRIDE" {
			t.Fatalf("groceries = %v", cat)
		}
	}

	mustStatus(t, b.do("PUT", "/v1/seller/session/active-org", map[string]any{"orgId": testkit.ProviderOrgID}), 200)
	r := b.do("GET", "/v1/seller/bookings/requests", nil)
	if r.status != 200 {
		t.Fatalf("requests = %d %s", r.status, r.body)
	}
	if !strings.Contains(string(r.body), `"takeHome":{"connectionFeeBps":800`) {
		t.Fatalf("requests carry no take-home: %s", r.body)
	}
	earn := b.do("GET", "/v1/seller/provider/earnings", nil).json(t)
	if c, ok := earn["connectionFee"].(map[string]any); !ok || c["planCode"] != "provider_studio" || len(c["categories"].([]any)) != 1 {
		t.Fatalf("earnings connection fee = %v", earn["connectionFee"])
	}
}
