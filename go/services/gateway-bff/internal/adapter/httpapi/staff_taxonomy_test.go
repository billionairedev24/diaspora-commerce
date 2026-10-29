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

const (
	txWomen    = "11111111-1111-4111-8111-111111111111"
	txDresses  = "22222222-2222-4222-8222-222222222222"
	txFashion  = "00000000-0000-4000-8000-000000000001"
	txRequest  = "33333333-3333-4333-8333-333333333333"
	txBusyNode = "44444444-4444-4444-8444-444444444444"
)

// taxonomyStaff: Catalog Manager (FULL + catalog:taxonomy), a product owner (VIEW + catalog:taxonomy) and Operations
// (EDIT on the catalog module but without catalog:taxonomy).
var taxonomyStaff = map[string][]any{
	"mgr":    {[]string{"platform.catalog_manager"}, []string{"catalog:taxonomy", "catalog:moderate"}},
	"viewer": {[]string{"platform.product_owner"}, []string{"catalog:taxonomy"}},
	"ops":    {[]string{"platform.operations_manager"}, []string{"catalog:moderate"}},
}

// fakeTaxonomy is catalog (the /v1/taxonomy API and the category requests), identity (who is signed in, who made a
// change) and seller (the organisation directory) in one server.
type fakeTaxonomy struct {
	mu      sync.Mutex
	calls   []string
	ifMatch map[string]string
	bodies  map[string]map[string]any
	status  string
}

func txNode(id, parent, name, level, path string, version int, active bool, products int) map[string]any {
	n := map[string]any{"id": id, "parentId": nil, "code": strings.ToLower(strings.ReplaceAll(name, " ", "-")), "name": name, "level": level,
		"productType": nil, "productTypeLabel": nil, "path": path, "position": 0, "active": active, "retiredAt": nil, "retiredReason": nil,
		"version": version, "productCount": products, "subtreeProductCount": products, "childCount": 0}
	if parent != "" {
		n["parentId"] = parent
	}
	if level == "PRODUCT_TYPE" {
		n["productType"], n["productTypeLabel"] = "dress", "Women's dresses"
	}
	return n
}

func txType(code string, version int) map[string]any {
	return map[string]any{"code": code, "label": "Women's dresses", "identifierRule": "GTIN", "version": version, "updatedAt": nil, "productCount": 3,
		"traits": map[string]any{"food": false, "perishable": false, "regulated": false, "preorder": false},
		"themes": []any{map[string]any{"name": "Size × Color"}},
		"usedBy": []any{map[string]any{"nodeId": txDresses, "path": "Fashion › Women › Dresses", "active": true}},
		"attributes": []any{map[string]any{"key": "brand", "label": "Brand", "kind": "TEXT", "options": []any{}, "units": []any{}, "required": true,
			"section": "DETAILS", "help": nil, "applies": true, "multiline": false}}}
}

func (f *fakeTaxonomy) request() map[string]any {
	r := map[string]any{"id": txRequest, "reference": "REQ-000012", "orgId": "org_adaeze", "path": "Fashion › Women › Bridal sets",
		"parentPath": "Fashion › Women", "name": "Bridal sets", "exampleProduct": "6-piece aso-ebi set", "detailsNeeded": "Pieces, colour, fabric",
		"status": f.status, "resolvedCategoryId": nil, "decisionNote": nil, "createdAt": "2026-10-01T10:00:00Z", "decidedAt": nil, "productsWaiting": 2}
	return r
}

func (f *fakeTaxonomy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p := r.URL.Path
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	key := r.Method + " " + p
	f.calls = append(f.calls, key)
	f.ifMatch[key] = r.Header.Get("If-Match")
	body := map[string]any{}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	f.bodies[key] = body
	fieldProblem := func(status int, code, field, msg string) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": code, "status": status, "code": code, "detail": msg,
			"fieldErrors": map[string]any{field: msg}})
	}
	stale := func() bool { return strings.Trim(r.Header.Get("If-Match"), `"`) == "9" }
	switch {
	case p == "/v1/me":
		who, ok := taxonomyStaff[token]
		if !ok {
			writeJ(w, 200, map[string]any{"id": "cus-1", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
			return
		}
		writeJ(w, 200, map[string]any{"id": "stf-" + token, "firstName": "Test", "lastName": token, "email": token + "@afrotransact.test",
			"principalType": "STAFF", "roles": who[0], "permissions": who[1]})
	case p == "/v1/admin/module-access":
		problemJ(w, 403, "identity.forbidden")
	case p == "/v1/admin/users/stf-mgr":
		writeJ(w, 200, map[string]any{"id": "stf-mgr", "firstName": "Chidi", "lastName": "Eze", "email": "chidi@afrotransact.test"})
	case p == "/v1/orgs/directory":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"id": "org_adaeze", "displayName": "Adaeze Fabrics"}}, "total": 1})

	// taxonomy
	case p == "/v1/taxonomy/tree":
		writeJ(w, 200, map[string]any{"pendingRequests": 1, "nodes": []any{txNode(txFashion, "", "Fashion", "DEPARTMENT", "Fashion", 0, true, 0),
			txNode(txWomen, txFashion, "Women", "CATEGORY", "Fashion › Women", 3, true, 3), txNode(txDresses, txWomen, "Dresses", "PRODUCT_TYPE", "Fashion › Women › Dresses", 1, true, 3)}})
	case p == "/v1/taxonomy/nodes" && r.Method == http.MethodPost:
		if body["name"] == "Dup" {
			fieldProblem(422, "catalog.rule-violation", "name", "There is already one called Dup here")
			return
		}
		writeJ(w, 201, txNode("55555555-5555-4555-8555-555555555555", txWomen, "Bridal sets", "PRODUCT_TYPE", "Fashion › Women › Bridal sets", 0, true, 0))
	case strings.HasPrefix(p, "/v1/taxonomy/nodes/") && r.Method == http.MethodPatch:
		if stale() {
			problemJ(w, 412, "catalog.version-mismatch")
			return
		}
		writeJ(w, 200, txNode(txWomen, txFashion, "Ladies", "CATEGORY", "Fashion › Ladies", 4, true, 3))
	case strings.HasSuffix(p, "/retire"):
		if strings.Contains(p, txBusyNode) {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(409)
			_ = json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": "Conflict", "status": 409, "code": "catalog.taxonomy-in-use",
				"detail": "Fashion › Women still has 3 products."})
			return
		}
		writeJ(w, 200, txNode(txDresses, txWomen, "Dresses", "PRODUCT_TYPE", "Fashion › Women › Dresses", 2, false, 0))
	case strings.HasSuffix(p, "/restore"):
		writeJ(w, 200, txNode(txDresses, txWomen, "Dresses", "PRODUCT_TYPE", "Fashion › Women › Dresses", 3, true, 0))
	case strings.HasSuffix(p, "/move"):
		writeJ(w, 200, txNode(txDresses, txFashion, "Dresses", "PRODUCT_TYPE", "Fashion › Dresses", 2, true, 3))
	case p == "/v1/taxonomy/reorder":
		writeJ(w, 200, []any{txNode(txWomen, txFashion, "Women", "CATEGORY", "Fashion › Women", 4, true, 3)})
	case p == "/v1/taxonomy/product-types" && r.Method == http.MethodGet:
		writeJ(w, 200, []any{txType("dress", 2), txType("custom", 0)})
	case p == "/v1/taxonomy/product-types" && r.Method == http.MethodPost:
		writeJ(w, 201, txType("bridal_sets", 0))
	case p == "/v1/taxonomy/product-types/dress" || p == "/v1/taxonomy/product-types/custom":
		if r.Method == http.MethodPatch {
			if stale() {
				problemJ(w, 412, "catalog.version-mismatch")
				return
			}
			writeJ(w, 200, txType("dress", 3))
			return
		}
		writeJ(w, 200, txType(strings.TrimPrefix(p, "/v1/taxonomy/product-types/"), 2))
	case strings.HasPrefix(p, "/v1/taxonomy/product-types/"):
		problemJ(w, 404, "catalog.product-type-not-found")
	case p == "/v1/taxonomy/audit":
		writeJ(w, 200, []any{
			map[string]any{"id": "66666666-6666-4666-8666-666666666666", "at": "2026-10-02T09:00:00Z", "actorId": "stf-mgr", "action": "NODE_RENAMED",
				"subjectKind": "NODE", "subjectId": txWomen, "subjectLabel": "Fashion › Ladies", "reason": "Better wording",
				"details": map[string]any{"from": "Women", "to": "Ladies", "oldPath": "Fashion › Women"}},
			map[string]any{"id": "77777777-7777-4777-8777-777777777777", "at": "2026-10-01T09:00:00Z", "actorId": "system", "action": "TYPE_UPDATED",
				"subjectKind": "PRODUCT_TYPE", "subjectId": "dress", "subjectLabel": "Women's dresses", "reason": "Add brand",
				"details": map[string]any{"attributes": map[string]any{"added": []any{"brand"}}}}})

	// category requests
	case p == "/v1/category-requests" && r.Method == http.MethodGet:
		if r.URL.Query().Get("status") == f.status {
			writeJ(w, 200, []any{f.request()})
			return
		}
		writeJ(w, 200, []any{})
	case p == "/v1/category-requests/"+txRequest:
		writeJ(w, 200, map[string]any{"request": f.request(), "products": []any{map[string]any{"id": "p-1", "atin": "AT00000001", "title": "Aso-ebi set"}},
			"suggestedParent": txNode(txWomen, txFashion, "Women", "CATEGORY", "Fashion › Women", 3, true, 3)})
	case p == "/v1/category-requests/"+txRequest+"/approval-preview":
		if body["parentCategoryId"] == txBusyNode {
			fieldProblem(422, "catalog.rule-violation", "name", "There is already a Bridal sets in Fashion › Women")
			return
		}
		writeJ(w, 200, map[string]any{"nodes": []any{map[string]any{"level": "PRODUCT_TYPE", "name": "Bridal sets", "path": "Fashion › Women › Bridal sets", "created": true}},
			"productType": map[string]any{"code": "bridal_sets", "label": "Bridal sets", "created": true, "attributeCount": 1}, "productsMoving": 2})
	case p == "/v1/category-requests/"+txRequest+"/approve":
		f.status = "APPROVED"
		writeJ(w, 200, f.request())
	case p == "/v1/category-requests/"+txRequest+"/reject":
		f.status = "REJECTED"
		writeJ(w, 200, f.request())
	case p == "/v1/categories":
		writeJ(w, 200, []any{txNode(txFashion, "", "Fashion", "DEPARTMENT", "Fashion", 0, true, 0), txNode(txWomen, txFashion, "Women", "CATEGORY", "Fashion › Women", 3, true, 3),
			txNode(txDresses, txWomen, "Dresses", "PRODUCT_TYPE", "Fashion › Women › Dresses", 1, true, 3)})
	default:
		problemJ(w, 404, "not-found")
	}
}

func taxonomyBFF(t *testing.T) (*fakeTaxonomy, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder) {
	t.Helper()
	f := &fakeTaxonomy{ifMatch: map[string]string{}, bodies: map[string]map[string]any{}, status: "PENDING_REVIEW"}
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Catalog: up("catalog"), Seller: up("seller")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Log:    log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Taxonomy: app.NewStaffTaxonomyService(d)}
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

// TestStaffTaxonomy drives every Taxonomy operation of gateway-bff-staff.yaml through the BFF against a fake catalog:
// reads with names instead of ids, every change with its reason and If-Match, the BFF's own validation, catalog's
// refusals relayed (stale version, node in use), the category-request queue with approve and reject, permissions, and
// checks each answer against the contract. The last step fails when an operation of the tag was never exercised.
func TestStaffTaxonomy(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
	if err != nil {
		t.Fatalf("load contract: %v", err)
	}
	doc.Servers = openapi3.Servers{{URL: "http://bff.internal"}}
	router, err := gorillamux.NewRouter(doc)
	if err != nil {
		t.Fatal(err)
	}
	f, raw := taxonomyBFF(t)
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
		return jsonOf(t, rec)
	}

	// Reads: the tree with counts, the module's levels, product types, the audit trail by name.
	tree := do("mgr", "GET", "/v1/staff/taxonomy/tree", nil, 200)
	if tree["canEdit"] != true || tree["canDecide"] != true || tree["pendingRequests"] != float64(1) || len(tree["nodes"].([]any)) != 3 {
		t.Fatalf("tree = %v", tree)
	}
	if v := do("viewer", "GET", "/v1/staff/taxonomy/tree", nil, 200); v["canEdit"] != false || v["canDecide"] != false {
		t.Fatalf("a viewer may not change the tree: %v", v)
	}
	types := do("mgr", "GET", "/v1/staff/taxonomy/product-types", nil, 200)
	if len(types["items"].([]any)) != 2 || types["canEdit"] != true {
		t.Fatalf("types = %v", types)
	}
	if one := do("mgr", "GET", "/v1/staff/taxonomy/product-types/dress", nil, 200); one["version"] != float64(2) || one["canEdit"] != true {
		t.Fatalf("type = %v", one)
	}
	do("mgr", "GET", "/v1/staff/taxonomy/product-types/nope", nil, 404)
	do("mgr", "GET", "/v1/staff/taxonomy/product-types/Bad-Code", nil, 404)
	audit := do("mgr", "GET", "/v1/staff/taxonomy/audit?subject="+txWomen+"&limit=10", nil, 200)
	items := audit["items"].([]any)
	first := items[0].(map[string]any)
	if first["actorName"] != "Chidi Eze" || first["summary"] != "Renamed “Women” to “Ladies”" || first["actionLabel"] != "Renamed" {
		t.Fatalf("audit names the person and the change: %v", first)
	}
	if strings.Contains(mustJSON(t, audit), "stf-mgr") {
		t.Fatalf("the audit shows ids: %s", mustJSON(t, audit))
	}
	if second := items[1].(map[string]any); second["actorName"] != "Unknown user" || second["summary"] != "Changed Women's dresses: added brand" {
		t.Fatalf("a system actor and a schema change = %v", second)
	}
	if !strings.Contains(strings.Join(f.calls, "\n"), "GET /v1/taxonomy/audit") || f.bodies["GET /v1/taxonomy/audit"] == nil {
		t.Fatalf("audit not read from catalog: %v", f.calls)
	}

	// Creating: the BFF names the field before catalog is asked.
	created := do("mgr", "POST", "/v1/staff/taxonomy/nodes", map[string]any{"level": "product_type", "parentId": txWomen, "name": "  Bridal   sets ",
		"reason": "Sellers keep asking for it", "newProductType": map[string]any{"identifierRule": "gtin", "label": "Bridal sets",
			"attributes": []any{map[string]any{"key": "pieces", "label": "Pieces", "kind": "text", "required": true}}}}, 201)
	if created["path"] != "Fashion › Women › Bridal sets" {
		t.Fatalf("created = %v", created)
	}
	if b := f.bodies["POST /v1/taxonomy/nodes"]; b["level"] != "PRODUCT_TYPE" || b["name"] != "Bridal sets" || b["reason"] != "Sellers keep asking for it" {
		t.Fatalf("catalog body = %s", mustJSON(t, b))
	} else if nt := b["newProductType"].(map[string]any); nt["identifierRule"] != "GTIN" || nt["attributes"].([]any)[0].(map[string]any)["kind"] != "TEXT" {
		t.Fatalf("new product type body = %s", mustJSON(t, nt))
	}
	for name, bad := range map[string]map[string]any{
		"level":       {"level": "SHELF", "name": "Hats", "reason": "because"},
		"name":        {"level": "DEPARTMENT", "name": "A", "reason": "because"},
		"reason":      {"level": "DEPARTMENT", "name": "Hats", "reason": "no"},
		"parentId":    {"level": "CATEGORY", "name": "Hats", "reason": "because"},
		"productType": {"level": "PRODUCT_TYPE", "parentId": txWomen, "name": "Hats", "reason": "because"},
		"newProductType.attributes[0].key": {"level": "PRODUCT_TYPE", "parentId": txWomen, "name": "Hats", "reason": "because",
			"newProductType": map[string]any{"identifierRule": "GTIN", "attributes": []any{map[string]any{"label": "Pieces", "kind": "TEXT"}}}},
	} {
		if e := do("mgr", "POST", "/v1/staff/taxonomy/nodes", bad, 422)["errors"].(map[string]any); e[name] == nil {
			t.Errorf("no error for %s: %v", name, e)
		}
	}
	if e := do("mgr", "POST", "/v1/staff/taxonomy/nodes", map[string]any{"level": "DEPARTMENT", "name": "Dup", "reason": "duplicate"}, 422)["errors"].(map[string]any); e["name"] == nil {
		t.Errorf("catalog's field error is relayed: %v", e)
	}

	// Renaming, moving, retiring and restoring carry If-Match; a stale version is VERSION_CONFLICT.
	renamed := do("mgr", "PATCH", "/v1/staff/taxonomy/nodes/"+txWomen, map[string]any{"name": "Ladies", "reason": "Better wording"}, 200, "If-Match", `"3"`)
	if renamed["name"] != "Ladies" || f.ifMatch["PATCH /v1/taxonomy/nodes/"+txWomen] != `"3"` {
		t.Fatalf("rename = %v, If-Match %q", renamed, f.ifMatch["PATCH /v1/taxonomy/nodes/"+txWomen])
	}
	if stale := do("mgr", "PATCH", "/v1/staff/taxonomy/nodes/"+txWomen, map[string]any{"name": "Ladies", "reason": "Better wording"}, 412, "If-Match", `"9"`); stale["code"] != "VERSION_CONFLICT" {
		t.Fatalf("stale = %v", stale)
	}
	do("mgr", "PATCH", "/v1/staff/taxonomy/nodes/"+txWomen, map[string]any{"name": "Ladies", "reason": "Better wording"}, 422)
	do("mgr", "PATCH", "/v1/staff/taxonomy/nodes/not-an-id", map[string]any{"name": "Ladies", "reason": "Better wording"}, 404, "If-Match", `"1"`)
	do("mgr", "POST", "/v1/staff/taxonomy/nodes/"+txDresses+"/move", map[string]any{"parentId": txFashion, "reason": "Dresses are top level"}, 200, "If-Match", `"1"`)
	do("mgr", "POST", "/v1/staff/taxonomy/nodes/"+txDresses+"/move", map[string]any{"parentId": "x", "reason": "Dresses are top level"}, 422, "If-Match", `"1"`)
	do("mgr", "POST", "/v1/staff/taxonomy/nodes/"+txDresses+"/retire", map[string]any{"reason": "Not selling dresses"}, 200, "If-Match", `"1"`)
	inUse := do("mgr", "POST", "/v1/staff/taxonomy/nodes/"+txBusyNode+"/retire", map[string]any{"reason": "Not selling dresses"}, 409, "If-Match", `"1"`)
	if !strings.Contains(inUse["detail"].(string), "3 products") {
		t.Fatalf("a category with products is not retired: %v", inUse)
	}
	do("mgr", "POST", "/v1/staff/taxonomy/nodes/"+txDresses+"/restore", map[string]any{"reason": "Changed our minds"}, 200, "If-Match", `"2"`)
	do("mgr", "POST", "/v1/staff/taxonomy/nodes/"+txDresses+"/restore", map[string]any{"reason": "ab"}, 422, "If-Match", `"2"`)

	// Reordering.
	reordered := do("mgr", "POST", "/v1/staff/taxonomy/reorder", map[string]any{"parentId": txFashion, "ids": []string{txWomen, txDresses}, "reason": "Women first"}, 200)
	if len(reordered["items"].([]any)) != 1 {
		t.Fatalf("reordered = %v", reordered)
	}
	do("mgr", "POST", "/v1/staff/taxonomy/reorder", map[string]any{"ids": []string{txWomen}, "reason": "Women first"}, 422)

	// Product types: create, edit only what changed, nothing changed is refused, the stale version is a conflict.
	newType := do("mgr", "POST", "/v1/staff/taxonomy/product-types", map[string]any{"label": "Bridal sets", "identifierRule": "GTIN", "reason": "For bridal sets",
		"attributes": []any{map[string]any{"key": "pieces", "label": "Pieces", "kind": "NUMBER", "units": []string{"pcs"}}}}, 201)
	if newType["code"] != "bridal_sets" {
		t.Fatalf("new type = %v", newType)
	}
	do("mgr", "POST", "/v1/staff/taxonomy/product-types", map[string]any{"identifierRule": "GTIN", "reason": "For bridal sets"}, 422)
	edited := do("mgr", "PATCH", "/v1/staff/taxonomy/product-types/dress", map[string]any{"identifierRule": "ISBN", "reason": "Treat like books"}, 200, "If-Match", `"2"`)
	if edited["version"] != float64(3) {
		t.Fatalf("edited = %v", edited)
	}
	if b := f.bodies["PATCH /v1/taxonomy/product-types/dress"]; len(b) != 2 || b["identifierRule"] != "ISBN" {
		t.Fatalf("only the changed fields go to catalog: %s", mustJSON(t, b))
	}
	if e := do("mgr", "PATCH", "/v1/staff/taxonomy/product-types/dress", map[string]any{"reason": "Nothing"}, 422, "If-Match", `"2"`)["errors"].(map[string]any); e["form"] == nil {
		t.Fatalf("nothing changed = %v", e)
	}
	do("mgr", "PATCH", "/v1/staff/taxonomy/product-types/dress", map[string]any{"attributes": []any{map[string]any{"key": "brand", "label": "Brand", "kind": "BOGUS"}},
		"reason": "Bad kind"}, 422, "If-Match", `"2"`)
	do("mgr", "PATCH", "/v1/staff/taxonomy/product-types/dress", map[string]any{"label": "Dresses", "reason": "Late"}, 412, "If-Match", `"9"`)
	do("mgr", "PATCH", "/v1/staff/taxonomy/product-types/dress", map[string]any{"label": "Dresses", "reason": "No version"}, 422)

	// The category-request queue: seller by name, the products waiting, a plan before anyone approves.
	queue := do("mgr", "GET", "/v1/staff/resources/category-requests", nil, 200)
	row := queue["rows"].([]any)[0].(map[string]any)["cells"].(map[string]any)
	if row["orgName"] != "Adaeze Fabrics" || row["path"] != "Fashion › Women › Bridal sets" || row["productsWaiting"] != float64(2) || row["reference"] != "REQ-000012" {
		t.Fatalf("queue row = %v", row)
	}
	if strings.Contains(mustJSON(t, queue["columns"]), "org_adaeze") {
		t.Fatal("the queue shows the seller's id")
	}
	sheet := do("mgr", "GET", "/v1/staff/resources/category-requests/"+txRequest, nil, 200)
	text := mustJSON(t, sheet)
	if !strings.Contains(text, "Adaeze Fabrics") || !strings.Contains(text, "Fashion › Women › Bridal sets — will be created") || !strings.Contains(text, "AT00000001") {
		t.Fatalf("sheet lacks the seller name, the plan or the waiting products: %s", text)
	}
	actions := map[string]map[string]any{}
	for _, a := range sheet["actions"].([]any) {
		m := a.(map[string]any)
		actions[m["key"].(string)] = m
	}
	if actions["approve"]["allowed"] != true || actions["reject"]["allowed"] != true {
		t.Fatalf("actions = %v", actions)
	}
	form := actions["approve"]["form"].(map[string]any)
	if fields := form["fields"].([]any); fields[0].(map[string]any)["value"] != txWomen || len(fields[0].(map[string]any)["options"].([]any)) != 2 ||
		fields[2].(map[string]any)["options"].([]any)[0].(map[string]any)["value"] != "__new__" {
		t.Fatalf("approve form = %s", mustJSON(t, form))
	}
	plan := do("mgr", "POST", "/v1/staff/taxonomy/requests/"+txRequest+"/approval-preview", map[string]any{"parentCategoryId": txWomen, "productType": "__new__", "basedOn": "dress"}, 200)
	if plan["productsMoving"] != float64(2) {
		t.Fatalf("plan = %v", plan)
	}
	if nt := f.bodies["POST /v1/category-requests/"+txRequest+"/approval-preview"]["newProductType"].(map[string]any); nt["basedOn"] != "dress" ||
		len(nt["attributes"].([]any)) != 1 || nt["identifierRule"] != "GTIN" {
		t.Fatalf("a new schema copies the template: %s", mustJSON(t, nt))
	}
	do("mgr", "POST", "/v1/staff/taxonomy/requests/"+txRequest+"/approval-preview", map[string]any{"parentCategoryId": txWomen}, 422)
	do("mgr", "POST", "/v1/staff/taxonomy/requests/"+txRequest+"/approval-preview", map[string]any{"parentCategoryId": txBusyNode, "productType": "dress"}, 422)
	do("mgr", "POST", "/v1/staff/taxonomy/requests/nope/approval-preview", map[string]any{"parentCategoryId": txWomen, "productType": "dress"}, 404)

	// Approve through the action: the request is decided and read back; a second approval is refused.
	approved := do("mgr", "POST", "/v1/staff/resources/category-requests/"+txRequest+"/actions/approve",
		map[string]any{"values": map[string]any{"parentCategoryId": txWomen, "productType": "dress", "note": "Welcome"}}, 200)
	if approved["status"] != "APPROVED" {
		t.Fatalf("approved = %v", approved)
	}
	if b := f.bodies["POST /v1/category-requests/"+txRequest+"/approve"]; b["parentCategoryId"] != txWomen || b["productType"] != "dress" || b["note"] != "Welcome" {
		t.Fatalf("approve body = %s", mustJSON(t, b))
	}
	do("mgr", "POST", "/v1/staff/resources/category-requests/"+txRequest+"/actions/approve", map[string]any{"values": map[string]any{"parentCategoryId": txWomen, "productType": "dress"}}, 409)
	f.mu.Lock()
	f.status = "PENDING_REVIEW"
	f.mu.Unlock()
	do("mgr", "POST", "/v1/staff/resources/category-requests/"+txRequest+"/actions/reject", map[string]any{"values": map[string]any{}}, 422)
	rejected := do("mgr", "POST", "/v1/staff/resources/category-requests/"+txRequest+"/actions/reject", map[string]any{"values": map[string]any{"note": "Already covered by Dresses"}}, 200)
	if rejected["status"] != "REJECTED" {
		t.Fatalf("rejected = %v", rejected)
	}

	// Permissions: a viewer reads but changes nothing; Operations lacks catalog:taxonomy; customers and guests are out.
	do("viewer", "PATCH", "/v1/staff/taxonomy/nodes/"+txWomen, map[string]any{"name": "Ladies", "reason": "Better wording"}, 403, "If-Match", `"3"`)
	do("viewer", "POST", "/v1/staff/taxonomy/nodes", map[string]any{"level": "DEPARTMENT", "name": "Hats", "reason": "New shelf"}, 403)
	do("viewer", "GET", "/v1/staff/taxonomy/product-types", nil, 200)
	do("ops", "GET", "/v1/staff/taxonomy/tree", nil, 403)
	do("ops", "GET", "/v1/staff/taxonomy/audit", nil, 403)
	do("customer", "GET", "/v1/staff/taxonomy/tree", nil, 403)
	do("", "GET", "/v1/staff/taxonomy/tree", nil, 401)
	f.mu.Lock()
	f.status = "PENDING_REVIEW"
	f.mu.Unlock()
	do("viewer", "POST", "/v1/staff/resources/category-requests/"+txRequest+"/actions/reject", map[string]any{"values": map[string]any{"note": "Not yours to decide"}}, 403)

	// Every operation of the tag was exercised.
	for _, item := range doc.Paths.Map() {
		for _, op := range item.Operations() {
			for _, tag := range op.Tags {
				if tag == "Taxonomy" && validated[op.OperationID] == 0 {
					t.Errorf("operation %s was not exercised", op.OperationID)
				}
			}
		}
	}
	t.Logf("validated responses per operation: %v", validated)
}
