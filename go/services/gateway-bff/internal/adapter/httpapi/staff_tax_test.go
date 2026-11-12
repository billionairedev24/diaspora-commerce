package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
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

// taxStaff: Super Admin (everything), Finance (fees FULL, reads and configures tax but cannot classify), Auditor (reads
// tax only), and a Catalog Manager (no fees module, even with tax:read).
var taxStaff = map[string][]any{
	"admin":   {[]string{"platform.super_admin"}, []string{"tax:read", "tax:classify-admin", "tax:configure"}},
	"finance": {[]string{"platform.finance"}, []string{"tax:read", "tax:configure"}},
	"auditor": {[]string{"platform.auditor"}, []string{"tax:read"}},
	"noperm":  {[]string{"platform.auditor"}, []string{}},
	"catalog": {[]string{"platform.catalog_manager"}, []string{"tax:read", "tax:classify-admin"}},
}

// taxData is the real dataset (tools/taxonomy/category_tax_map.json): 73 categories, 105 decision rows.
type taxData struct {
	Departments []struct{ Code, Name string }
	Facts       []map[string]any
	Categories  []map[string]any
}

func loadTaxData(t *testing.T) taxData {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "..", "..", "tools", "taxonomy", "category_tax_map.json"))
	if err != nil {
		t.Fatal(err)
	}
	var d taxData
	if err := json.Unmarshal(raw, &d); err != nil {
		t.Fatal(err)
	}
	return d
}

// fakeTax is tax-returns plus the identity and seller lookups the names need, on one server.
type fakeTax struct {
	mu       sync.Mutex
	data     taxData
	status   map[string]string
	codes    map[string]map[string]map[string]any // provider > internal code > entry
	settings map[string]any
	records  map[string]map[string]any
	calls    []string
	bodies   map[string]map[string]any
	reqInfo  bool
	// liveWithout is what catalog's audit of live listings without a tax decision returns (empty when the gate holds).
	liveWithout []map[string]any
	liveCalls   int
	shadow      map[string]any
	review      map[string]any
}

func newFakeTax(t *testing.T) *fakeTax {
	d := loadTaxData(t)
	f := &fakeTax{data: d, status: map[string]string{}, codes: map[string]map[string]map[string]any{"STRIPE": {}, "AVALARA": {}},
		bodies: map[string]map[string]any{}, reqInfo: true,
		settings: map[string]any{"method": "RULES", "configuredDefault": "RULES", "stripeAvailable": true, "shadowProvider": nil, "changedBy": "stf-admin",
			"changedAt": "2026-11-01T10:00:00Z", "history": []any{map[string]any{"from": "STRIPE_TAX", "to": "RULES", "reason": "Back to our own rules while codes are mapped", "changedBy": "stf-admin", "changedAt": "2026-11-01T10:00:00Z"}}}}
	n := 0
	for _, c := range d.Categories {
		f.status[c["code"].(string)] = c["status"].(string)
		for _, r := range c["decisionTable"].([]any) {
			row := r.(map[string]any)
			code := row["internalTaxCode"].(string)
			pc := row["providerCodes"].(map[string]any)
			for _, p := range []string{"STRIPE", "AVALARA"} {
				var v any
				if s, ok := pc[p].(string); ok && s != "" && p == "STRIPE" {
					v = s
				}
				n++
				f.codes[p][code] = map[string]any{"internalTaxCode": code, "categoryCode": c["code"], "outcome": row["outcome"], "provider": p, "providerCode": v,
					"verified": v != nil && n%3 == 0, "mapVersion": 1, "changedBy": "stf-admin", "changedAt": "2026-11-02T09:00:00Z"}
			}
		}
	}
	f.records = map[string]map[string]any{
		"AT-NUTS": {"atin": "AT-NUTS", "gtin": "00812345678905", "title": "Roasted cashews 6 oz", "categoryCode": "nuts-seeds", "status": "PENDING_REVIEW",
			"facts": map[string]any{"is_candy": false, "candy_coated": false, "net_weight_oz": 6.0, "servings_per_package": 4.0, "individual_serving": false},
			"evidence": []any{map[string]any{"factId": "label_evidence", "fileName": "label.jpg", "url": "/media/label.jpg", "uploadedBy": "usr-seller", "uploadedAt": "2026-11-08T12:00:00Z"},
				map[string]any{"factId": "label_evidence", "fileName": "spec.pdf", "url": "/media/spec.pdf", "uploadedBy": "usr-seller", "uploadedAt": "2026-11-08T12:05:00Z"}},
			"resolution": map[string]any{"status": "RESOLVED", "outcome": "EXEMPT", "internalTaxCode": "AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS", "rowId": "S007",
				"rateDisplay": "0%", "missingFacts": []any{}, "ruleVersion": "tx-2026.11.09-draft.1"},
			"decisions":    []any{map[string]any{"sellerOrgId": "org-1", "sellerBehaviour": map[string]any{}, "resolution": map[string]any{"status": "RESOLVED", "outcome": "EXEMPT", "missingFacts": []any{}, "ruleVersion": "x"}}},
			"classifiedBy": "usr-seller", "classifiedAt": "2026-11-08T12:10:00Z", "ruleVersion": "tx-2026.11.09-draft.1",
			"audit": []any{map[string]any{"at": "2026-11-08T12:10:00Z", "actor": "usr-seller", "action": "SUBMITTED", "ruleVersion": "tx-2026.11.09-draft.1"}}},
		"AT-EBOOK": {"atin": "AT-EBOOK", "title": "Yoruba for beginners (ebook)", "categoryCode": "downloadable-ebooks", "status": "PENDING_REVIEW", "facts": map[string]any{},
			"resolution":   map[string]any{"status": "NEEDS_APPROVAL", "missingFacts": []any{}, "ruleVersion": "tx-2026.11.09-draft.1"},
			"decisions":    []any{map[string]any{"sellerOrgId": "org-2", "resolution": map[string]any{"status": "NEEDS_APPROVAL", "missingFacts": []any{}, "ruleVersion": "x"}}},
			"classifiedBy": "usr-seller", "classifiedAt": "2026-11-08T13:00:00Z", "ordersAffected": 0, "audit": []any{}},
		"AT-DONE": {"atin": "AT-DONE", "title": "Plantain chips 2 oz", "categoryCode": "packaged-snacks", "status": "APPROVED", "facts": map[string]any{},
			"resolution": map[string]any{"status": "RESOLVED", "outcome": "TAXABLE", "rowId": "", "missingFacts": []any{}, "ruleVersion": "tx-2026.11.09-draft.1"},
			"decisions":  []any{}, "reviewedBy": "stf-admin", "reviewedAt": "2026-11-07T08:00:00Z", "ruleVersion": "tx-2026.11.09-draft.1",
			"audit": []any{map[string]any{"at": "2026-11-07T08:00:00Z", "actor": "stf-admin", "action": "APPROVED", "reason": "Label matches", "ruleVersion": "tx-2026.11.09-draft.1"}}},
	}
	f.records["AT-AUDIT"] = map[string]any{"atin": "AT-AUDIT", "title": "Shea butter 8 oz", "categoryCode": "skincare-cosmetics", "status": "APPROVED", "facts": map[string]any{},
		"resolution":   map[string]any{"status": "RESOLVED", "outcome": "TAXABLE", "rowId": "S041", "missingFacts": []any{}, "ruleVersion": "tx-2026.11.09-draft.1"},
		"decisions":    []any{map[string]any{"sellerOrgId": "org-1", "resolution": map[string]any{"status": "RESOLVED", "outcome": "TAXABLE", "missingFacts": []any{}, "ruleVersion": "x"}}},
		"classifiedBy": "auto", "classifiedAt": "2026-11-09T09:00:00Z", "reviewedBy": "auto", "reviewedAt": "2026-11-09T09:00:00Z", "ruleVersion": "tx-2026.11.09-draft.1",
		"auditSample": true, "auditSampledAt": "2026-11-09T09:00:00Z",
		"audit": []any{map[string]any{"at": "2026-11-09T09:00:00Z", "actor": "usr-seller", "action": "SUBMITTED", "ruleVersion": "tx-2026.11.09-draft.1"},
			map[string]any{"at": "2026-11-09T09:00:00Z", "actor": "auto", "action": "APPROVED", "reason": "Risk-based review: the category is ACTIVE", "ruleVersion": "tx-2026.11.09-draft.1"},
			map[string]any{"at": "2026-11-09T09:00:00Z", "actor": "auto", "action": "AUDIT_SAMPLED", "reason": "Placed in the audit sample", "ruleVersion": "tx-2026.11.09-draft.1"}}}
	f.review = map[string]any{"mode": "ALL", "alwaysReview": []any{"baby-food-formula", "dietary-supplements", "gift-cards", "medical-devices", "medicated-skincare", "otc-medicines"},
		"alwaysReviewStatuses": []any{"NEEDS_APPROVAL", "REFINE_CODE", "CONFLICT_OPEN", "BLOCKED_BUNDLE"}, "auditPercent": 5, "changedBy": nil, "changedAt": nil, "history": []any{}}
	diff := func(i int, kind string, inO, prO string, in, pr float64) map[string]any {
		return map[string]any{"orderId": "ord-1", "lineId": "l-" + string(rune('a'+i)), "atin": "AT-NUTS", "internalTaxCode": "AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS",
			"providerCode": "txcd_40040016", "kind": kind, "inHouse": map[string]any{"outcome": inO, "rate": in, "taxMinor": 0},
			"provider": map[string]any{"outcome": prO, "rate": pr, "taxMinor": 83}, "differenceMinor": 83, "quotedAt": "2026-11-05T10:00:00Z", "shipToState": "TX"}
	}
	f.shadow = map[string]any{"linesCompared": 300, "linesDiffering": 3, "absoluteDifferenceMinor": 249, "netDifferenceMinor": 249,
		"byInternalTaxCode": []any{map[string]any{"internalTaxCode": "AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS", "providerCode": "txcd_40040016", "lines": 40, "differing": 3, "absoluteDifferenceMinor": 249},
			map[string]any{"internalTaxCode": "AT.BABY_FOOD_FORMULA.STANDARD", "providerCode": "txcd_40090005", "lines": 3, "differing": 0, "absoluteDifferenceMinor": 0}},
		"differences": []any{diff(0, "OUTCOME", "EXEMPT", "TAXABLE", 0, 0.0825), diff(1, "OUTCOME", "EXEMPT", "TAXABLE", 0, 0.0825), diff(2, "RATE", "TAXABLE", "TAXABLE", 0.0825, 0.08)}}
	return f
}

func (f *fakeTax) profile(code string) map[string]any {
	factDefs := map[string]map[string]any{}
	for _, fd := range f.data.Facts {
		factDefs[fd["id"].(string)] = fd
	}
	for _, c := range f.data.Categories {
		if c["code"] != code {
			continue
		}
		facts := []any{}
		for _, fr := range c["facts"].([]any) {
			ref := fr.(map[string]any)
			m := map[string]any{}
			for k, v := range factDefs[ref["fact"].(string)] {
				m[k] = v
			}
			m["required"], m["requiredWhen"] = ref["required"], ref["requiredWhen"]
			facts = append(facts, m)
		}
		out := map[string]any{"categoryCode": code, "name": c["name"], "department": c["department"], "status": f.status[code], "defaultOutcome": c["defaultOutcome"],
			"scenarioCount": len(c["decisionTable"].([]any)), "ruleVersion": "tx-2026.11.09-draft.1", "stripeCodes": c["stripeCodes"], "conflictingCodes": c["conflictingCodes"],
			"statusReasons": c["statusReasons"], "facts": facts, "decisionTable": c["decisionTable"], "changedBy": "stf-admin", "changedAt": "2026-11-01T10:00:00Z"}
		if n, ok := c["note"].(string); ok {
			out["note"] = n
		}
		return out
	}
	return nil
}

func (f *fakeTax) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p, token := r.URL.Path, strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	f.calls = append(f.calls, r.Method+" "+p+"?"+r.URL.RawQuery)
	body := map[string]any{}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	f.bodies[r.Method+" "+p] = body
	switch {
	case p == "/v1/me":
		who, ok := taxStaff[token]
		if !ok {
			writeJ(w, 200, map[string]any{"id": "cus-1", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
			return
		}
		writeJ(w, 200, map[string]any{"id": "stf-" + token, "firstName": "Test", "lastName": token, "email": token + "@afrotransact.test", "principalType": "STAFF",
			"roles": who[0], "permissions": who[1]})
	case p == "/v1/admin/module-access":
		problemJ(w, 403, "identity.forbidden")
	case p == "/v1/admin/users/stf-admin":
		writeJ(w, 200, map[string]any{"name": "Ada Okafor"})
	case p == "/v1/admin/users/usr-seller":
		writeJ(w, 200, map[string]any{"name": "Chidi Eze"})
	case p == "/v1/orgs/directory":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"id": "org-1", "displayName": "Mama's Pantry"}, map[string]any{"id": "org-2", "displayName": "Lagos Books"}}})
	case p == "/v1/orders/ord-1":
		writeJ(w, 200, map[string]any{"id": "ord-1", "orderNumber": "AT-000231"})
	case p == "/v1/tax-gate/live-without-decision":
		f.liveCalls++
		items := []any{}
		for _, it := range f.liveWithout {
			items = append(items, it)
		}
		writeJ(w, 200, map[string]any{"items": items, "total": len(items), "page": 0, "size": 25})
	default:
		f.tax(w, r, p, body)
	}
}

func (f *fakeTax) tax(w http.ResponseWriter, r *http.Request, p string, body map[string]any) {
	switch {
	case p == "/v1/tax/profiles":
		out := []any{}
		for _, c := range f.data.Categories {
			code := c["code"].(string)
			out = append(out, map[string]any{"categoryCode": code, "name": c["name"], "department": c["department"], "status": f.status[code],
				"defaultOutcome": c["defaultOutcome"], "scenarioCount": len(c["decisionTable"].([]any)), "ruleVersion": "tx-2026.11.09-draft.1"})
		}
		writeJ(w, 200, out)
	case strings.HasPrefix(p, "/v1/tax/profiles/") && strings.HasSuffix(p, "/status"):
		code := strings.TrimSuffix(strings.TrimPrefix(p, "/v1/tax/profiles/"), "/status")
		if f.profile(code) == nil {
			problemJ(w, 404, "tax.profile-not-found")
			return
		}
		f.status[code] = body["status"].(string)
		writeJ(w, 200, f.profile(code))
	case strings.HasPrefix(p, "/v1/tax/profiles/"):
		if pr := f.profile(strings.TrimPrefix(p, "/v1/tax/profiles/")); pr != nil {
			writeJ(w, 200, pr)
			return
		}
		problemJ(w, 404, "tax.profile-not-found")
	case p == "/v1/tax/review-settings" && r.Method == http.MethodPut:
		if r.Header.Get("Authorization") != "Bearer admin" && r.Header.Get("Authorization") != "Bearer catalog" {
			problemJ(w, 403, "forbidden")
			return
		}
		before := map[string]any{"mode": f.review["mode"], "alwaysReview": f.review["alwaysReview"], "auditPercent": f.review["auditPercent"]}
		f.review["mode"] = body["mode"]
		if v, ok := body["alwaysReview"]; ok {
			f.review["alwaysReview"] = v
		}
		if v, ok := body["auditPercent"]; ok {
			f.review["auditPercent"] = v
		}
		f.review["changedBy"], f.review["changedAt"] = "stf-admin", "2026-11-09T12:00:00Z"
		hist := f.review["history"].([]any)
		f.review["history"] = append([]any{map[string]any{"from": before, "to": map[string]any{"mode": f.review["mode"], "alwaysReview": f.review["alwaysReview"],
			"auditPercent": f.review["auditPercent"]}, "reason": body["reason"], "changedBy": "stf-admin", "changedAt": "2026-11-09T12:00:00Z"}}, hist...)
		writeJ(w, 200, f.review)
	case p == "/v1/tax/review-settings":
		writeJ(w, 200, f.review)
	case p == "/v1/tax/variant-classifications":
		status := r.URL.Query().Get("status")
		items := []any{}
		if r.URL.Query().Get("auditSample") == "true" {
			items = append(items, f.records["AT-AUDIT"])
		} else {
			for _, id := range []string{"AT-NUTS", "AT-EBOOK", "AT-DONE"} {
				if f.records[id]["status"] == status {
					items = append(items, f.records[id])
				}
			}
		}
		writeJ(w, 200, map[string]any{"items": items, "page": 0, "size": 25, "total": len(items)})
	case strings.HasPrefix(p, "/v1/tax/variant-classifications/"):
		rest := strings.TrimPrefix(p, "/v1/tax/variant-classifications/")
		atin, action, _ := strings.Cut(rest, "/")
		rec, ok := f.records[atin]
		if !ok {
			problemJ(w, 404, "tax.classification-not-found")
			return
		}
		switch action {
		case "approve":
			if rec["auditSample"] == true {
				rec["auditSample"], rec["reviewedBy"] = false, "stf-admin"
				writeJ(w, 200, rec)
				return
			}
			if rec["resolution"].(map[string]any)["status"] != "RESOLVED" {
				problemJ(w, 422, "tax.classification-not-resolvable")
				return
			}
			rec["status"], rec["reviewedBy"] = "APPROVED", "stf-admin"
		case "reclassify":
			rec["categoryCode"], rec["status"], rec["auditSample"] = body["categoryCode"], "PENDING_REVIEW", false
		case "request-information":
			if !f.reqInfo {
				http.NotFound(w, r)
				return
			}
			rec["status"] = "REJECTED"
		}
		writeJ(w, 200, rec)
	case p == "/v1/tax/provider-code-map" && r.Method == http.MethodGet:
		out := []any{}
		for _, c := range f.data.Categories {
			for _, row := range c["decisionTable"].([]any) {
				out = append(out, f.codes[r.URL.Query().Get("provider")][row.(map[string]any)["internalTaxCode"].(string)])
			}
		}
		writeJ(w, 200, out)
	case strings.HasPrefix(p, "/v1/tax/provider-code-map/") && r.Method == http.MethodPut:
		e := f.codes[body["provider"].(string)][strings.TrimPrefix(p, "/v1/tax/provider-code-map/")]
		if e == nil {
			problemJ(w, 404, "tax.profile-not-found")
			return
		}
		e["providerCode"], e["verified"], e["mapVersion"], e["changedBy"] = body["providerCode"], body["verified"], e["mapVersion"].(int)+1, "stf-admin"
		writeJ(w, 200, e)
	case p == "/v1/tax/settings" && r.Method == http.MethodPut:
		f.settings["method"], f.settings["shadowProvider"] = body["method"], body["shadowProvider"]
		writeJ(w, 200, f.settings)
	case p == "/v1/tax/settings":
		writeJ(w, 200, f.settings)
	case p == "/v1/tax/shadow/report":
		writeJ(w, 200, f.shadow)
	default:
		problemJ(w, 404, "not-found")
	}
}

type taxCall func(token, method, path string, body any, want int) map[string]any

func taxBFF(t *testing.T) (*fakeTax, taxCall, func(token, method, path string, body any) *httptest.ResponseRecorder) {
	t.Helper()
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f := newFakeTax(t)
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), TaxReturns: up("tax-returns"), Seller: up("seller"), Orders: up("orders"), Catalog: up("catalog")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Log:    log, Background: func(fn func()) { fn() }, Clock: func() time.Time { return time.Date(2026, 11, 9, 12, 0, 0, 0, time.UTC) }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Tax: app.NewStaffTaxService(d)}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Sessions: staffSessions{}, Staff: staff}, nil, httpapi.SecurityOptions{AllowedOrigins: []string{"http://localhost:3002"}}, log).Register(mux)
	raw := func(token, method, path string, body any) *httptest.ResponseRecorder {
		var rd io.Reader
		if body != nil {
			b, _ := json.Marshal(body)
			rd = bytes.NewReader(b)
		}
		req := httptest.NewRequestWithContext(context.Background(), method, "http://bff.internal"+path, rd)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}
	do := func(token, method, path string, body any, want int) map[string]any {
		t.Helper()
		rec := raw(token, method, path, body)
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
		return jsonOf(t, rec)
	}
	return f, do, raw
}

func items(t *testing.T, m map[string]any) []map[string]any {
	t.Helper()
	list, _ := m["items"].([]any)
	out := make([]map[string]any, 0, len(list))
	for _, it := range list {
		out = append(out, it.(map[string]any))
	}
	return out
}

func fieldErr(t *testing.T, m map[string]any, field string) string {
	t.Helper()
	errs, _ := m["errors"].(map[string]any)
	s, _ := errs[field].(string)
	return s
}

func TestStaffTaxProfiles(t *testing.T) {
	f, do, raw := taxBFF(t)
	all := do("admin", "GET", "/v1/staff/tax/profiles?size=100", nil, 200)
	rows := items(t, all)
	if len(rows) != 73 || all["total"] != float64(73) {
		t.Fatalf("profiles = %d (total %v), want the 73 categories", len(rows), all["total"])
	}
	counts := map[string]float64{}
	for _, s := range all["statuses"].([]any) {
		m := s.(map[string]any)
		counts[m["value"].(string)] = m["count"].(float64)
	}
	if counts["ACTIVE"] != 57 || counts["NEEDS_APPROVAL"] != 7 || counts["REFINE_CODE"] != 6 || counts["CONFLICT_OPEN"] != 2 || counts["BLOCKED_BUNDLE"] != 1 {
		t.Errorf("status counts = %v", counts)
	}
	if len(all["departments"].([]any)) != 13 {
		t.Errorf("departments = %d", len(all["departments"].([]any)))
	}
	var nuts map[string]any
	for _, r := range rows {
		if r["categoryCode"] == "nuts-seeds" {
			nuts = r
		}
	}
	if nuts["statusLabel"] != "Ready" || nuts["departmentName"] != "Groceries & Food" || nuts["stripeCodeList"] == "" {
		t.Errorf("nuts row = %v", nuts)
	}
	// Plain labels, never the enum.
	for _, r := range rows {
		if r["statusLabel"] == r["status"] {
			t.Errorf("%v shows the raw enum %v", r["categoryCode"], r["status"])
		}
	}

	byStatus := do("admin", "GET", "/v1/staff/tax/profiles?status=NEEDS_APPROVAL", nil, 200)
	if len(items(t, byStatus)) != 7 {
		t.Errorf("NEEDS_APPROVAL = %d", len(items(t, byStatus)))
	}
	byDept := do("admin", "GET", "/v1/staff/tax/profiles?department=digital-products", nil, 200)
	for _, r := range items(t, byDept) {
		if r["department"] != "digital-products" {
			t.Errorf("department filter leaked %v", r["department"])
		}
	}
	both := do("admin", "GET", "/v1/staff/tax/profiles?department=digital-products&status=NEEDS_APPROVAL&q=ebook", nil, 200)
	if len(items(t, both)) != 1 {
		t.Errorf("combined filter = %d rows, want the e-books category", len(items(t, both)))
	}
	paged := do("admin", "GET", "/v1/staff/tax/profiles?size=10&page=1&sort=name,desc", nil, 200)
	if len(items(t, paged)) != 10 || paged["total"] != float64(73) || items(t, paged)[0]["name"].(string) < items(t, paged)[9]["name"].(string) {
		t.Errorf("paging/sort = %v", paged["total"])
	}
	raw("admin", "GET", "/v1/staff/tax/profiles", nil)
	do("admin", "GET", "/v1/staff/tax/profiles?status=BOGUS", nil, 422)
	do("admin", "GET", "/v1/staff/tax/profiles?sort=password,asc", nil, 422)

	// One profile read per category for the Stripe codes; the list carries no codes itself.
	n := 0
	for _, c := range f.calls {
		if strings.HasPrefix(c, "GET /v1/tax/profiles/") {
			n++
		}
	}
	if n < 73 {
		t.Errorf("profile reads = %d", n)
	}
}

func TestStaffTaxProfileSheet(t *testing.T) {
	f, do, _ := taxBFF(t)
	nuts := do("admin", "GET", "/v1/staff/tax/profiles/nuts-seeds", nil, 200)
	var exempt map[string]any
	for _, r := range nuts["decisions"].([]any) {
		if m := r.(map[string]any); m["id"] == "S007" {
			exempt = m
		}
	}
	want := "If"
	got, _ := exempt["sentence"].(string)
	for _, part := range []string{"net weight is at least 2.5 oz", "servings per package is more than 1", ", then exempt"} {
		if !strings.Contains(got, part) {
			t.Errorf("S007 sentence %q is missing %q", got, part)
		}
	}
	if !strings.HasPrefix(got, want) || strings.Contains(got, "{") || strings.Contains(got, "isTrue") {
		t.Errorf("sentence is not plain words: %q", got)
	}
	if len(nuts["decisions"].([]any)) != 3 {
		t.Errorf("decision rows = %d", len(nuts["decisions"].([]any)))
	}
	if notes := nuts["notes"].([]any); len(notes) == 0 || notes[0].(map[string]any)["kind"] != "RULE" {
		t.Errorf("notes for the tax professional = %v", nuts["notes"])
	}
	needed := map[string]string{}
	for _, fr := range nuts["facts"].([]any) {
		m := fr.(map[string]any)
		needed[m["id"].(string)] = m["needed"].(string)
	}
	if needed["is_candy"] != "Always needed" || !strings.HasPrefix(needed["net_weight_oz"], "Needed when ") {
		t.Errorf("fact requirements = %v", needed)
	}
	if nuts["changedByName"] != "Ada Okafor" {
		t.Errorf("changed by = %v, want the person's name", nuts["changedByName"])
	}

	// An active category can be reopened; one waiting on sign-off can be approved; the engine owns REFINE_CODE.
	actions := func(code string) map[string]bool {
		p := do("admin", "GET", "/v1/staff/tax/profiles/"+code, nil, 200)
		out := map[string]bool{}
		for _, a := range p["actions"].([]any) {
			m := a.(map[string]any)
			out[m["key"].(string)] = m["available"].(bool)
		}
		return out
	}
	if a := actions("nuts-seeds"); a["approve"] || !a["reopen"] {
		t.Errorf("active actions = %v", a)
	}
	if a := actions("downloadable-ebooks"); !a["approve"] || a["reopen"] {
		t.Errorf("needs-approval actions = %v", a)
	}
	var refine string
	for _, c := range f.data.Categories {
		if c["status"] == "REFINE_CODE" {
			refine = c["code"].(string)
			break
		}
	}
	if a := actions(refine); a["approve"] || a["reopen"] {
		t.Errorf("refine-code actions = %v", a)
	}
	if p := do("admin", "GET", "/v1/staff/tax/profiles/"+refine, nil, 200); p["readOnlyReason"] == "" {
		t.Error("a REFINE_CODE profile says nothing about why it is read-only")
	}
	do("admin", "GET", "/v1/staff/tax/profiles/nope", nil, 404)
}

func TestStaffTaxProfileStatusChange(t *testing.T) {
	f, do, _ := taxBFF(t)
	path := "/v1/staff/tax/profiles/downloadable-ebooks/status"
	short := do("admin", "POST", path, map[string]any{"status": "ACTIVE", "reason": "ok", "approvedBy": "Tex CPA", "decisionRef": "OD-4"}, 422)
	if !strings.Contains(fieldErr(t, short, "reason"), "at least 10") {
		t.Errorf("reason error = %v", short)
	}
	nine := do("admin", "POST", path, map[string]any{"status": "ACTIVE", "reason": "123456789", "approvedBy": "x", "decisionRef": "y"}, 422)
	if fieldErr(t, nine, "reason") == "" {
		t.Error("nine characters passed the reason rule")
	}
	noSign := do("admin", "POST", path, map[string]any{"status": "ACTIVE", "reason": "Opinion letter received"}, 422)
	if fieldErr(t, noSign, "approvedBy") == "" || fieldErr(t, noSign, "decisionRef") == "" {
		t.Errorf("lifting a gate without the sign-off name = %v", noSign)
	}
	if f.status["downloadable-ebooks"] != "NEEDS_APPROVAL" {
		t.Fatal("a refused change reached tax-returns")
	}
	ok := do("admin", "POST", path, map[string]any{"status": "ACTIVE", "reason": "Opinion letter received", "approvedBy": "Dana Whitfield, CPA", "decisionRef": "OD-4 letter 2026-11-09"}, 200)
	if ok["status"] != "ACTIVE" || ok["statusLabel"] != "Ready" {
		t.Errorf("after approval = %v", ok["status"])
	}
	body := f.bodies["PUT /v1/tax/profiles/downloadable-ebooks/status"]
	if body["approvedBy"] != "Dana Whitfield, CPA" || body["decisionRef"] != "OD-4 letter 2026-11-09" || body["reason"] != "Opinion letter received" {
		t.Errorf("upstream body = %v", body)
	}
	// Reopening needs no sign-off, only a reason.
	do("admin", "POST", path, map[string]any{"status": "NEEDS_APPROVAL", "reason": "Rule wording changed"}, 200)
	do("admin", "POST", path, map[string]any{"status": "BLOCKED_BUNDLE", "reason": "Not an option here"}, 422)
	var refine string
	for _, c := range f.data.Categories {
		if c["status"] == "REFINE_CODE" {
			refine = c["code"].(string)
			break
		}
	}
	do("admin", "POST", "/v1/staff/tax/profiles/"+refine+"/status", map[string]any{"status": "ACTIVE", "reason": "Trying to lift it", "approvedBy": "x", "decisionRef": "y"}, 409)
	do("admin", "POST", "/v1/staff/tax/profiles/nope/status", map[string]any{"status": "NEEDS_APPROVAL", "reason": "Does not exist"}, 404)
}

func TestStaffTaxReviewQueue(t *testing.T) {
	f, do, _ := taxBFF(t)
	queue := do("admin", "GET", "/v1/staff/tax/reviews", nil, 200)
	rows := items(t, queue)
	if len(rows) != 2 || queue["status"] != "PENDING_REVIEW" || queue["canDecide"] != true {
		t.Fatalf("queue = %v", queue)
	}
	nuts, ebook := rows[0], rows[1]
	if nuts["title"] != "Roasted cashews 6 oz" || nuts["sellers"].([]any)[0] != "Mama's Pantry" || nuts["categoryName"] != "Nuts & seeds" && nuts["categoryName"] == nuts["categoryCode"] {
		t.Errorf("nuts row (names, not ids) = %v", nuts)
	}
	if strings.Contains(nuts["sellers"].([]any)[0].(string), "org-") {
		t.Errorf("seller shown by id: %v", nuts["sellers"])
	}
	if !strings.Contains(nuts["why"].(string), "exemption") || !strings.Contains(ebook["why"].(string), "sign-off") {
		t.Errorf("why = %q / %q", nuts["why"], ebook["why"])
	}
	if p := nuts["proposed"].(map[string]any); p["outcomeLabel"] != "Exempt (no tax)" || p["internalTaxCode"] == "" {
		t.Errorf("proposed = %v", p)
	}
	decided := do("admin", "GET", "/v1/staff/tax/reviews?status=APPROVED", nil, 200)
	d := items(t, decided)[0]
	if d["reviewedByName"] != "Ada Okafor" || d["ruleVersion"] != "tx-2026.11.09-draft.1" || d["reviewedAt"] == "" {
		t.Errorf("decision = who %v when %v rule %v", d["reviewedByName"], d["reviewedAt"], d["ruleVersion"])
	}
	do("admin", "GET", "/v1/staff/tax/reviews?status=NOPE", nil, 422)

	sheet := do("admin", "GET", "/v1/staff/tax/reviews/AT-NUTS", nil, 200)
	facts := map[string]string{}
	for _, fr := range sheet["facts"].([]any) {
		m := fr.(map[string]any)
		facts[m["label"].(string)] = m["value"].(string)
	}
	if facts["Net weight (oz)"] != "6 oz" || facts["Sold as a single serving"] != "no" {
		t.Errorf("facts as entered = %v", facts)
	}
	ev := sheet["evidence"].([]any)
	if len(ev) != 2 || ev[0].(map[string]any)["isImage"] != true || ev[1].(map[string]any)["isImage"] != false || ev[0].(map[string]any)["uploadedByName"] != "Chidi Eze" {
		t.Errorf("evidence = %v", ev)
	}
	if because := sheet["proposed"].(map[string]any)["because"].(string); !strings.Contains(because, "then exempt") {
		t.Errorf("proposed decision in words = %q", because)
	}
	if sheet["canApprove"] != true || sheet["audit"].([]any)[0].(map[string]any)["actorName"] != "Chidi Eze" {
		t.Errorf("sheet flags/audit = %v", sheet["audit"])
	}
	do("admin", "GET", "/v1/staff/tax/reviews/NOPE", nil, 404)

	// Approve: the reason rule, the upstream refusal in plain words, then the decision.
	do("admin", "POST", "/v1/staff/tax/reviews/AT-NUTS/approve", map[string]any{"reason": "short"}, 422)
	blocked := do("admin", "POST", "/v1/staff/tax/reviews/AT-EBOOK/approve", map[string]any{"reason": "Looks right to me"}, 422)
	if !strings.Contains(blocked["detail"].(string), "can't be approved yet") {
		t.Errorf("refusal = %v", blocked["detail"])
	}
	done := do("admin", "POST", "/v1/staff/tax/reviews/AT-NUTS/approve", map[string]any{"reason": "Label and weight match the listing"}, 200)
	if done["status"] != "APPROVED" || done["canApprove"] != false {
		t.Errorf("after approve = %v", done["status"])
	}
	if f.bodies["POST /v1/tax/variant-classifications/AT-NUTS/approve"]["reason"] != "Label and weight match the listing" {
		t.Error("the reason did not reach tax-returns")
	}

	// Reclassify needs a category and a reason.
	do("admin", "POST", "/v1/staff/tax/reviews/AT-EBOOK/reclassify", map[string]any{"reason": "It is really a printed book"}, 422)
	do("admin", "POST", "/v1/staff/tax/reviews/AT-EBOOK/reclassify", map[string]any{"categoryCode": "books", "reason": "short"}, 422)
	moved := do("admin", "POST", "/v1/staff/tax/reviews/AT-EBOOK/reclassify", map[string]any{"categoryCode": "nuts-seeds", "reason": "It is really a snack box"}, 200)
	if moved["categoryCode"] != "nuts-seeds" || moved["status"] != "PENDING_REVIEW" {
		t.Errorf("after reclassify = %v", moved["categoryCode"])
	}
	if _, ok := do("admin", "GET", "/v1/staff/tax/reviews/AT-EBOOK", nil, 200)["ordersAffected"]; !ok {
		t.Error("orders affected is dropped when tax-returns reports it")
	}

	// Request more information: relayed to tax-returns; without that route it is "unavailable", never silently dropped.
	do("admin", "POST", "/v1/staff/tax/reviews/AT-NUTS/request-information", map[string]any{"message": "no"}, 422)
	do("admin", "POST", "/v1/staff/tax/reviews/AT-NUTS/request-information", map[string]any{"message": "Please upload a clearer label photo"}, 200)
	f.reqInfo = false
	do("admin", "POST", "/v1/staff/tax/reviews/AT-NUTS/request-information", map[string]any{"message": "Please upload a clearer label photo"}, 503)
}

func TestStaffTaxCodeMap(t *testing.T) {
	f, do, raw := taxBFF(t)
	m := do("admin", "GET", "/v1/staff/tax/code-map?provider=STRIPE&size=200", nil, 200)
	comp := m["completeness"].(map[string]any)
	if m["provider"] != "STRIPE" || comp["needed"] != float64(104) || comp["unmapped"] != float64(0) || comp["complete"] != false {
		t.Errorf("stripe completeness = %v (blocked bundle outcome needs no code)", comp)
	}
	if comp["unverified"].(float64) < 50 {
		t.Errorf("unverified = %v", comp["unverified"])
	}
	av := do("admin", "GET", "/v1/staff/tax/code-map?provider=AVALARA&size=10", nil, 200)
	if c := av["completeness"].(map[string]any); c["unmapped"] != float64(104) || c["percent"] != float64(0) {
		t.Errorf("avalara completeness = %v", c)
	}
	un := do("admin", "GET", "/v1/staff/tax/code-map?provider=AVALARA&unmappedOnly=true&size=200", nil, 200)
	if un["total"] != float64(104) {
		t.Errorf("unmapped only = %v", un["total"])
	}
	if e := items(t, m)[0]; e["categoryName"] == e["categoryCode"] || e["outcomeLabel"] == e["outcome"] || e["stateLabel"] == e["state"] {
		t.Errorf("entry not in plain words: %v", e)
	}
	do("admin", "GET", "/v1/staff/tax/code-map?provider=PAYPAL", nil, 422)

	internal := "AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS"
	put := "/v1/staff/tax/code-map/" + internal
	base := map[string]any{"provider": "STRIPE", "providerCode": "txcd_40040016", "verified": true, "reason": "Checked against Stripe's list"}
	with := func(k string, v any) map[string]any {
		c := map[string]any{}
		for kk, vv := range base {
			c[kk] = vv
		}
		c[k] = v
		return c
	}
	do("admin", "PUT", put, with("reason", "short"), 422)
	do("admin", "PUT", put, with("providerCode", "40040016"), 422)
	do("admin", "PUT", put, with("providerCode", ""), 422) // verified without a code
	do("admin", "PUT", put, with("provider", "AVALARA"), 200)
	ok := do("admin", "PUT", put, base, 200)
	if ok["verified"] != true || ok["state"] != "VERIFIED" || f.bodies["PUT /v1/tax/provider-code-map/"+internal]["reason"] != "Checked against Stripe's list" {
		t.Errorf("update = %v", ok)
	}
	cleared := do("admin", "PUT", put, map[string]any{"provider": "STRIPE", "providerCode": "", "verified": false, "reason": "Wrong code, clearing it"}, 200)
	if cleared["state"] != "UNMAPPED" || f.bodies["PUT /v1/tax/provider-code-map/"+internal]["providerCode"] != nil {
		t.Errorf("cleared = %v / %v", cleared["state"], f.bodies["PUT /v1/tax/provider-code-map/"+internal])
	}

	// Export, then import it back: a dry run changes nothing, a bad row blocks the whole file.
	exp := do("admin", "GET", "/v1/staff/tax/code-map/export?provider=STRIPE", nil, 200)
	csv := exp["csv"].(string)
	if exp["rows"] != float64(105) || !strings.HasPrefix(csv, "internal_tax_code,category,outcome,provider,provider_code,verified\n") || exp["filename"] != "tax-code-map-stripe.csv" {
		t.Errorf("export = %v rows, header %q", exp["rows"], strings.SplitN(csv, "\n", 2)[0])
	}
	csvIn := "internal_tax_code,provider_code,verified\n" + internal + ",txcd_40040016,true\nAT.BABY_FOOD_FORMULA.STANDARD,txcd_40090006,false\n"
	puts := func() int {
		n := 0
		for _, c := range f.calls {
			if strings.HasPrefix(c, "PUT /v1/tax/provider-code-map/") {
				n++
			}
		}
		return n
	}
	before := puts()
	dry := do("admin", "POST", "/v1/staff/tax/code-map/import", map[string]any{"provider": "STRIPE", "csv": csvIn, "dryRun": true}, 200)
	if dry["dryRun"] != true || dry["changed"] != float64(2) || dry["applied"] != float64(0) || puts() != before {
		t.Errorf("dry run = %v (puts %d→%d)", dry, before, puts())
	}
	do("admin", "POST", "/v1/staff/tax/code-map/import", map[string]any{"provider": "STRIPE", "csv": csvIn}, 422) // no reason
	bad := do("admin", "POST", "/v1/staff/tax/code-map/import", map[string]any{"provider": "STRIPE", "reason": "Import checked list",
		"csv": csvIn + "AT.NO.SUCH_CODE,txcd_40040016,false\nAT.PET_FOOD.STANDARD,not-a-code,false\n"}, 200)
	if bad["applied"] != float64(0) || bad["blocked"] != true || len(bad["problems"].([]any)) != 2 || puts() != before {
		t.Errorf("a file with bad rows was applied: %v", bad)
	}
	good := do("admin", "POST", "/v1/staff/tax/code-map/import", map[string]any{"provider": "STRIPE", "csv": csvIn, "reason": "Import of the checked list"}, 200)
	if good["applied"] != float64(2) || puts() != before+2 {
		t.Errorf("import = %v", good)
	}
	do("admin", "POST", "/v1/staff/tax/code-map/import", map[string]any{"provider": "STRIPE", "csv": "just,one\nrow,here", "dryRun": true}, 422)
	if rec := raw("admin", "POST", "/v1/staff/tax/code-map/import", map[string]any{"provider": "STRIPE", "csv": "internal_tax_code,provider_code\n=cmd," + "txcd_40040016", "dryRun": true}); rec.Code != 200 {
		t.Errorf("formula cell = %d", rec.Code)
	}
}

func TestStaffTaxMethod(t *testing.T) {
	f, do, _ := taxBFF(t)
	m := do("admin", "GET", "/v1/staff/tax/method", nil, 200)
	if m["method"] != "RULES" || m["methodLabel"] != "Our own rules" || m["runbookPath"] != "docs/runbooks/tax-provider-cutover.md" || m["canConfigure"] != true {
		t.Errorf("method = %v", m)
	}
	opts := map[string]map[string]any{}
	for _, o := range m["options"].([]any) {
		om := o.(map[string]any)
		opts[om["method"].(string)] = om
	}
	if opts["RULES"]["available"] != true || opts["AVALARA"]["available"] != false || !strings.Contains(opts["AVALARA"]["blockedReason"].(string), "isn't set up") {
		t.Errorf("options = %v", opts)
	}
	if opts["STRIPE_TAX"]["available"] != false || !strings.Contains(opts["STRIPE_TAX"]["blockedReason"].(string), "Finish the Provider codes") {
		t.Errorf("stripe is configured but its codes are not all verified: %v", opts["STRIPE_TAX"])
	}
	if h := m["history"].([]any)[0].(map[string]any); h["changedByName"] != "Ada Okafor" || h["fromLabel"] != "Stripe Tax" {
		t.Errorf("history = %v", h)
	}
	// Refusals.
	do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "RULES", "reason": "short"}, 422)
	do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "PAYPAL", "reason": "A reason that is long enough"}, 422)
	avalara := do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "AVALARA", "reason": "A reason that is long enough"}, 422)
	if !strings.Contains(fieldErr(t, avalara, "method"), "Avalara isn't set up") {
		t.Errorf("unconfigured provider = %v", avalara)
	}
	stripe := do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "STRIPE_TAX", "reason": "A reason that is long enough"}, 422)
	if !strings.Contains(fieldErr(t, stripe, "method"), "not verified") {
		t.Errorf("incomplete codes = %v", stripe)
	}
	do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "RULES", "shadowProvider": "AVALARA", "reason": "A reason that is long enough"}, 422)
	do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "STRIPE_TAX", "shadowProvider": "STRIPE", "reason": "A reason that is long enough"}, 422)
	if f.settings["method"] != "RULES" {
		t.Fatal("a refused change reached tax-returns")
	}
	on := do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "RULES", "shadowProvider": "STRIPE", "reason": "Start comparing Stripe on real orders"}, 200)
	if on["shadowProvider"] != "STRIPE" || on["shadowLabel"] != "Stripe Tax" {
		t.Errorf("shadow on = %v", on)
	}
	if b := f.bodies["PUT /v1/tax/settings"]; b["shadowProvider"] != "STRIPE" || b["reason"] != "Start comparing Stripe on real orders" {
		t.Errorf("upstream body = %v", b)
	}
	off := do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "RULES", "shadowProvider": "", "reason": "Comparison finished for now"}, 200)
	if off["shadowLabel"] != "Off" || f.bodies["PUT /v1/tax/settings"]["shadowProvider"] != nil {
		t.Errorf("shadow off = %v / %v", off["shadowLabel"], f.bodies["PUT /v1/tax/settings"])
	}
	// Once every Stripe outcome is verified, Stripe Tax can be chosen.
	for _, e := range f.codes["STRIPE"] {
		if e["outcome"] != "BLOCKED" {
			e["providerCode"], e["verified"] = "txcd_40040016", true
		}
	}
	do("admin", "PUT", "/v1/staff/tax/method", map[string]any{"method": "STRIPE_TAX", "reason": "All codes verified, adviser signed off"}, 200)
}

func TestStaffTaxShadowReport(t *testing.T) {
	f, do, _ := taxBFF(t)
	off := do("admin", "GET", "/v1/staff/tax/shadow", nil, 200)
	if off["shadowOff"] != true || !strings.Contains(off["message"].(string), "Shadow mode is off") {
		t.Errorf("shadow off = %v", off)
	}
	f.settings["shadowProvider"] = "STRIPE"
	r := do("admin", "GET", "/v1/staff/tax/shadow?size=2", nil, 200)
	if r["provider"] != "STRIPE" || r["linesCompared"] != float64(300) || r["differingPct"] != float64(1) || r["total"] != float64(3) || len(items(t, r)) != 2 {
		t.Errorf("report = %v", r)
	}
	if r["from"] != "2026-10-10" || r["to"] != "2026-11-09" {
		t.Errorf("default window = %v to %v", r["from"], r["to"])
	}
	row := items(t, r)[0]
	if row["orderNumber"] != "AT-000231" || row["kindLabel"] == "OUTCOME" || !strings.Contains(row["inHouseText"].(string), "Exempt") || !strings.Contains(row["providerText"].(string), "Taxable at 8.25%") {
		t.Errorf("sample row = %v", row)
	}
	var nutsCat map[string]any
	for _, c := range r["byCategory"].([]any) {
		if m := c.(map[string]any); m["key"] == "nuts-seeds" {
			nutsCat = m
		}
	}
	if nutsCat == nil || nutsCat["differing"] != float64(3) || nutsCat["differingPct"] != float64(7.5) {
		t.Errorf("by category = %v", r["byCategory"])
	}
	if st := r["byState"].([]any); len(st) != 1 || st[0].(map[string]any)["key"] != "TX" {
		t.Errorf("by state = %v", r["byState"])
	}
	var kinds = map[string]float64{}
	for _, k := range r["kinds"].([]any) {
		kinds[k.(map[string]any)["kind"].(string)] = k.(map[string]any)["count"].(float64)
	}
	if kinds["OUTCOME"] != 2 || kinds["RATE"] != 1 {
		t.Errorf("kinds = %v", kinds)
	}
	ready := r["readiness"].(map[string]any)
	if ready["ready"] != false || ready["thresholdSource"] != "DEFAULT" || !strings.HasPrefix(ready["headline"].(string), "Not ready to cut over") {
		t.Errorf("readiness = %v", ready)
	}
	failing := map[string]bool{}
	for _, c := range ready["checks"].([]any) {
		m := c.(map[string]any)
		failing[m["key"].(string)] = m["ok"] == false
	}
	if failing["window"] || failing["volume"] || !failing["outcomes"] || !failing["codes"] {
		t.Errorf("failing checks = %v", failing)
	}
	// Thresholds from the API win, and a clean report is ready.
	f.shadow["thresholds"] = map[string]any{"minDays": 1, "minLines": 100, "minLinesPerCode": 1, "maxOutcomeDisagreementPct": 100, "maxProviderErrorPct": 100}
	for _, e := range f.codes["STRIPE"] {
		if e["outcome"] != "BLOCKED" {
			e["providerCode"], e["verified"] = "txcd_40040016", true
		}
	}
	ready = do("admin", "GET", "/v1/staff/tax/shadow?provider=STRIPE", nil, 200)["readiness"].(map[string]any)
	if ready["ready"] != true || ready["thresholdSource"] != "TAX_RETURNS" {
		t.Errorf("with the API's thresholds: %v", ready)
	}
	do("admin", "GET", "/v1/staff/tax/shadow?from=2026-11-09&to=2026-01-01", nil, 422)
	do("admin", "GET", "/v1/staff/tax/shadow?from=yesterday", nil, 422)
}

// TestStaffTaxPermissionMatrix: who may read, who may change what.
func TestStaffTaxPermissionMatrix(t *testing.T) {
	_, do, raw := taxBFF(t)
	type step struct {
		method, path string
		body         any
	}
	reads := []step{
		{"GET", "/v1/staff/tax/profiles", nil}, {"GET", "/v1/staff/tax/profiles/nuts-seeds", nil}, {"GET", "/v1/staff/tax/reviews", nil},
		{"GET", "/v1/staff/tax/reviews/AT-NUTS", nil}, {"GET", "/v1/staff/tax/code-map?provider=STRIPE", nil}, {"GET", "/v1/staff/tax/code-map/export?provider=STRIPE", nil},
		{"GET", "/v1/staff/tax/method", nil}, {"GET", "/v1/staff/tax/shadow", nil},
	}
	classify := []step{
		{"POST", "/v1/staff/tax/profiles/downloadable-ebooks/status", map[string]any{"status": "NEEDS_APPROVAL", "reason": "Rule wording changed"}},
		{"POST", "/v1/staff/tax/reviews/AT-NUTS/approve", map[string]any{"reason": "Label and weight match"}},
		{"POST", "/v1/staff/tax/reviews/AT-NUTS/reclassify", map[string]any{"categoryCode": "nuts-seeds", "reason": "Same category again"}},
		{"POST", "/v1/staff/tax/reviews/AT-NUTS/request-information", map[string]any{"message": "Please upload a clearer photo"}},
		{"PUT", "/v1/staff/tax/code-map/AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS", map[string]any{"provider": "STRIPE", "providerCode": "txcd_40040016", "verified": false, "reason": "Setting the code"}},
		{"POST", "/v1/staff/tax/code-map/import", map[string]any{"provider": "STRIPE", "csv": "internal_tax_code,provider_code\nAT.BABY_FOOD_FORMULA.STANDARD,txcd_40090005", "reason": "Import of checked list"}},
	}
	configure := []step{{"PUT", "/v1/staff/tax/method", map[string]any{"method": "RULES", "shadowProvider": "STRIPE", "reason": "Compare Stripe on real orders"}}}

	for _, s := range append(append([]step{}, reads...), append(classify, configure...)...) {
		if rec := raw("", s.method, s.path, s.body); rec.Code != 401 {
			t.Errorf("guest %s %s = %d", s.method, s.path, rec.Code)
		}
		if rec := raw("nobody", s.method, s.path, s.body); rec.Code != 403 {
			t.Errorf("customer %s %s = %d", s.method, s.path, rec.Code)
		}
		if rec := raw("noperm", s.method, s.path, s.body); rec.Code != 403 {
			t.Errorf("staff without tax:read %s %s = %d", s.method, s.path, rec.Code)
		}
		if rec := raw("catalog", s.method, s.path, s.body); rec.Code != 403 {
			t.Errorf("staff without the Fees module %s %s = %d (tax:read alone does not open it)", s.method, s.path, rec.Code)
		}
	}
	for _, s := range reads {
		for _, who := range []string{"admin", "finance", "auditor"} {
			do(who, s.method, s.path, s.body, 200)
		}
	}
	for _, s := range classify {
		for _, who := range []string{"finance", "auditor"} {
			if rec := raw(who, s.method, s.path, s.body); rec.Code != 403 || !strings.Contains(rec.Body.String(), "tax:classify-admin") {
				t.Errorf("%s %s %s = %d %s", who, s.method, s.path, rec.Code, rec.Body.String())
			}
		}
	}
	for _, who := range []string{"auditor"} {
		if rec := raw(who, configure[0].method, configure[0].path, configure[0].body); rec.Code != 403 || !strings.Contains(rec.Body.String(), "tax:configure") {
			t.Errorf("%s method change = %d", who, rec.Code)
		}
	}
	for _, s := range classify[:3] {
		do("admin", s.method, s.path, s.body, 200)
	}
	do("finance", configure[0].method, configure[0].path, configure[0].body, 200)
	// The sheets say what the person may do.
	if p := do("auditor", "GET", "/v1/staff/tax/profiles/downloadable-ebooks", nil, 200); p["canChange"] != false || !strings.Contains(p["readOnlyReason"].(string), "tax:classify-admin") {
		t.Errorf("auditor sheet = %v", p["readOnlyReason"])
	}
	if q := do("finance", "GET", "/v1/staff/tax/reviews", nil, 200); q["canDecide"] != false {
		t.Errorf("finance canDecide = %v", q["canDecide"])
	}
	if m := do("auditor", "GET", "/v1/staff/tax/method", nil, 200); m["canConfigure"] != false {
		t.Errorf("auditor canConfigure = %v", m["canConfigure"])
	}
}

func TestStaffTaxLiveWithoutDecision(t *testing.T) {
	f, do, raw := taxBFF(t)

	// The gate holds: the list is empty and the page says so with a zero total.
	none := do("auditor", "GET", "/v1/staff/tax/live-without-decision", nil, 200)
	if none["total"] != float64(0) || len(items(t, none)) != 0 {
		t.Fatalf("a healthy catalog lists nothing: %v", none)
	}

	// A listing slipped through: it is named by seller and category, with the reason in plain words.
	f.liveWithout = []map[string]any{{"productId": "prd-1", "atin": "AT00000001", "title": "Ofada rice 5 lb", "ownerOrgId": "org-1",
		"categoryName": "Groceries and food > Rice and grains", "status": "LIVE", "updatedAt": "2026-11-12T09:00:00Z",
		"variants": []any{map[string]any{"atin": "AT00000011", "sellerSku": "OFADA-5", "taxStatus": "PENDING_REVIEW",
			"reason": "Waiting for our team to check the details. This usually needs no action from you."}}}}
	got := do("auditor", "GET", "/v1/staff/tax/live-without-decision", nil, 200)
	rows := items(t, got)
	if got["total"] != float64(1) || len(rows) != 1 {
		t.Fatalf("one listing is live without a decision: %v", got)
	}
	row := rows[0]
	if row["sellerName"] != "Mama's Pantry" || row["categoryName"] != "Groceries and food > Rice and grains" || row["title"] != "Ofada rice 5 lb" {
		t.Errorf("names, not ids: %v", row)
	}
	v := row["variants"].([]any)[0].(map[string]any)
	if v["statusLabel"] != "Waiting for tax review" || !strings.Contains(v["why"].(string), "Waiting for our team") {
		t.Errorf("variant = %v", v)
	}
	if strings.Contains(got["items"].([]any)[0].(map[string]any)["sellerName"].(string), "org-") {
		t.Errorf("seller shown by id: %v", row)
	}

	// Needs the staff tax permission and a session.
	if rec := raw("noperm", "GET", "/v1/staff/tax/live-without-decision", nil); rec.Code != 403 {
		t.Errorf("without tax:read = %d, want 403", rec.Code)
	}
	if rec := raw("", "GET", "/v1/staff/tax/live-without-decision", nil); rec.Code != 401 {
		t.Errorf("without a session = %d, want 401", rec.Code)
	}
	do("auditor", "GET", "/v1/staff/tax/live-without-decision?size=0", nil, 422)
}
