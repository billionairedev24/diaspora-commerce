package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/getkin/kin-openapi/openapi3"
	"github.com/getkin/kin-openapi/openapi3filter"
	"github.com/getkin/kin-openapi/routers/gorillamux"
	"github.com/prometheus/client_golang/prometheus"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/exportfiles"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/httpclient"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/upstreams"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

const staffContractPath = "../../../../../../contracts/openapi/gateway-bff-staff.yaml"

// TestEmbeddedStaffContractIsIdentical keeps api/openapi-staff.yaml (served at /openapi-staff.yaml) equal to the contract.
func TestEmbeddedStaffContractIsIdentical(t *testing.T) {
	want, err := os.ReadFile(staffContractPath)
	if err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(filepath.Join("..", "..", "..", "api", "openapi-staff.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatal("api/openapi-staff.yaml differs from contracts/openapi/gateway-bff-staff.yaml; copy the contract")
	}
}

// ---------------------------------------------------------------- fake platform (every staff upstream on one server)

var supportPerms = []string{"bookings:read", "catalog:moderate", "delivery:dispatch", "disputes:manage", "inventory:read", "notifications:read", "orders:read",
	"orders:update-status", "orgs:read", "payments:read", "refunds:issue", "reports:read", "returns:manage", "support:respond", "users:read"}

var adminPerms = []string{"access:admin", "access:read", "applications:approve", "applications:review", "audit:read", "catalog:moderate", "content:legal.edit",
	"content:markets.edit", "content:placements.approve", "content:placements.edit", "content:site.edit", "delivery:dispatch", "disputes:manage",
	"inventory:adjust", "inventory:read", "notifications:manage", "orders:manage", "orders:read", "payments:configure", "payouts:approve", "payouts:read",
	"refunds:issue", "reports:read", "retail:po:approve", "retail:po:write", "returns:manage", "support:respond", "tax:configure", "users:read"}

type fakePlatform struct {
	mu        sync.Mutex
	calls     []string
	bodies    map[string]map[string]any
	payoutSt  string
	bigOrders bool
}

func (f *fakePlatform) record(r *http.Request) map[string]any {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, r.Method+" "+r.URL.Path)
	var b map[string]any
	if r.Body != nil {
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &b)
	}
	if f.bodies == nil {
		f.bodies = map[string]map[string]any{}
	}
	f.bodies[r.Method+" "+r.URL.Path] = b
	return b
}

func (f *fakePlatform) called(call string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, c := range f.calls {
		if c == call {
			return true
		}
	}
	return false
}

func writeJ(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func problemJ(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": code, "status": status, "code": code})
}

func page(items ...map[string]any) map[string]any {
	return map[string]any{"items": items, "total": len(items), "page": 0, "size": 25}
}

func order(n int) map[string]any {
	return map[string]any{"id": fmt.Sprintf("ord-%d", n), "orderNumber": fmt.Sprintf("AT-%06d", n), "status": "PLACED", "customerId": "cus-1",
		"placedAt": time.Now().UTC().Add(-time.Duration(n%20) * 24 * time.Hour).Format(time.RFC3339), "currency": "USD", "itemsMinor": 2000,
		"shippingMinor": 599, "taxMinor": 165, "discountMinor": 0, "totalMinor": 2764, "shipTo": "Houston, TX 77042", "paymentId": "pay-1",
		"shippingAddress": map[string]any{"fullName": "Amara Nwosu"},
		"shipments": []any{map[string]any{"id": "shp-1", "shipmentId": "shp-1", "label": "Package 1", "sellerName": "Kente Naturals", "status": "PLACED",
			"cancellable": true, "totalMinor": 2764, "timeline": []any{map[string]any{"to": "PLACED", "at": time.Now().UTC().Format(time.RFC3339), "source": "checkout"}},
			"lines": []any{map[string]any{"id": "ln-1", "title": "Shea butter", "category": "BEAUTY", "quantity": 1, "itemsMinor": 2000, "discountMinor": 0}}}}}
}

func (f *fakePlatform) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body := f.record(r)
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	p := r.URL.Path
	now := time.Now().UTC().Format(time.RFC3339)
	switch {
	// identity
	case p == "/v1/me":
		switch token {
		case "admin":
			writeJ(w, 200, map[string]any{"id": "stf-1", "firstName": "Ifeoma", "lastName": "Okafor", "email": "ifeoma@afrotransact.com", "principalType": "STAFF",
				"roles": []string{"account.customer", "platform.super_admin"}, "permissions": adminPerms})
		case "support":
			writeJ(w, 200, map[string]any{"id": "stf-4", "firstName": "Grace", "lastName": "Adjei", "email": "grace@afrotransact.com", "principalType": "STAFF",
				"roles": []string{"platform.customer_support"}, "permissions": supportPerms})
		default:
			writeJ(w, 200, map[string]any{"id": "cus-1", "firstName": "Amara", "lastName": "Nwosu", "principalType": "CUSTOMER", "roles": []string{"account.customer"}})
		}
	case p == "/v1/admin/module-access":
		problemJ(w, 403, "identity.forbidden")
	case p == "/v1/admin/users" && r.Method == http.MethodGet:
		writeJ(w, 200, page(map[string]any{"id": "cus-1", "name": "Amara Nwosu", "email": "amara@example.com", "enabled": true, "principalType": "CUSTOMER", "roles": []string{"account.customer"}}))
	case p == "/v1/admin/users" && r.Method == http.MethodPost:
		writeJ(w, 201, map[string]any{"id": "stf-9"})
	case strings.HasPrefix(p, "/v1/admin/users/"):
		writeJ(w, 200, map[string]any{"id": "cus-1", "name": "Amara Nwosu", "email": "amara@example.com", "enabled": true, "emailVerified": true,
			"principalType": "CUSTOMER", "roles": []string{"account.customer"}, "effectivePermissions": []string{}})
	case p == "/v1/admin/access-log":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"at": now, "actorId": "stf-1", "action": "ROLE_ASSIGNED", "targetRole": "platform.finance"}}})
	case p == "/v1/admin/roles":
		writeJ(w, 200, []any{map[string]any{"key": "platform.finance", "portal": "PLATFORM", "name": "Finance", "description": "Money", "permissions": []string{"payouts:approve"}}})
	case strings.HasPrefix(p, "/v1/admin/identity-providers"):
		writeJ(w, 200, []any{map[string]any{"id": "google", "displayName": "Google", "enabled": false, "configured": true}})
	// seller
	case p == "/v1/applications":
		writeJ(w, 200, page(map[string]any{"id": "app-1", "reference": "APP-1", "displayName": "Kente Naturals", "kind": "SELLER", "status": "AWAITING_FINAL_APPROVAL",
			"reviewerId": "stf-1", "submittedAt": now}))
	case p == "/v1/review-reason-codes":
		writeJ(w, 200, []any{map[string]any{"code": "DOCUMENTS_VERIFIED", "label": "Documents verified"}})
	case strings.HasPrefix(p, "/v1/applications/"):
		writeJ(w, 200, map[string]any{"id": "app-1", "reference": "APP-1", "kind": "SELLER", "status": "AWAITING_FINAL_APPROVAL", "reviewerId": "stf-1",
			"business": map[string]any{"storeName": "Kente Naturals", "entityType": "SINGLE_MEMBER_LLC"}, "steps": []string{"business", "bank"}})
	// orders
	case p == "/v1/admin/orders":
		if f.bigOrders && r.URL.Query().Get("customer") == "" && r.URL.Query().Get("from") == "" {
			start, _ := strconv.Atoi(r.URL.Query().Get("cursor"))
			items := []map[string]any{}
			for i := start; i < start+100 && i < 6000; i++ {
				items = append(items, order(i))
			}
			res := map[string]any{"items": items, "total": 6000}
			if start+100 < 6000 {
				res["nextCursor"] = strconv.Itoa(start + 100)
			}
			writeJ(w, 200, res)
			return
		}
		writeJ(w, 200, map[string]any{"items": []any{order(1), order(2)}, "total": 2})
	case strings.HasPrefix(p, "/v1/orders/"):
		if strings.HasSuffix(p, "AT-999999") {
			problemJ(w, 404, "orders.not-found")
			return
		}
		writeJ(w, 200, order(1))
	case strings.HasPrefix(p, "/v1/shipments/"):
		writeJ(w, 200, map[string]any{"outcome": "OK"})
	// catalog
	case p == "/v1/products":
		writeJ(w, 200, page(map[string]any{"id": "prd-1", "atin": "AT0001", "title": "Shea butter", "status": "IN_REVIEW", "ownerOrgId": "org-1",
			"variants": []any{map[string]any{"atin": "AT0001-1", "sku": "SHEA", "status": "IN_REVIEW"}}}))
	case strings.HasPrefix(p, "/v1/products/"):
		writeJ(w, 200, map[string]any{"id": "prd-1", "atin": "AT0001", "title": "Shea butter", "status": "IN_REVIEW", "ownerOrgId": "org-1"})
	// trust-ops
	case p == "/v1/moderation/items" || strings.HasPrefix(p, "/v1/moderation/items/"):
		item := map[string]any{"id": "mod-1", "title": "Herbal tea", "type": "NEW_LISTING", "status": "PENDING", "sla": map[string]any{"dueAt": now, "state": "ON_TRACK"}}
		if p == "/v1/moderation/items" {
			writeJ(w, 200, page(item))
		} else {
			writeJ(w, 200, item)
		}
	case p == "/v1/claims" || strings.HasPrefix(p, "/v1/claims/"):
		claim := map[string]any{"id": "clm-1", "reference": "CLM-1", "type": "DAMAGED_ITEM", "status": "UNDER_REVIEW", "orderId": "ord-1", "orderNumber": "AT-000001",
			"customerId": "cus-1", "amountMinor": 40000, "openedAt": now, "version": 3}
		switch {
		case p == "/v1/claims":
			writeJ(w, 200, page(claim))
		case strings.HasSuffix(p, "/history"):
			writeJ(w, 200, []any{map[string]any{"at": now, "actorId": "cus-1", "action": "OPENED", "toStatus": "UNDER_REVIEW"}})
		default:
			writeJ(w, 200, claim)
		}
	case p == "/v1/support-cases" || strings.HasPrefix(p, "/v1/support-cases/"):
		c := map[string]any{"id": "case-1", "reference": "SUP-1", "source": "LOLA_HANDOFF", "customerId": "cus-1", "reason": "Refund", "summary": "Late parcel", "status": "OPEN", "openedAt": now}
		if p == "/v1/support-cases" {
			writeJ(w, 200, page(c))
		} else {
			writeJ(w, 200, c)
		}
	case p == "/v1/audit/entries" || strings.HasPrefix(p, "/v1/audit/entries/"):
		e := map[string]any{"seq": 90, "action": "claim.decided", "actorId": "stf-1", "entityType": "claim", "entityId": "clm-1", "summary": "Refund", "occurredAt": now}
		if p == "/v1/audit/entries" {
			writeJ(w, 200, page(e))
		} else {
			writeJ(w, 200, e)
		}
	// tax-returns
	case p == "/v1/returns" || strings.HasPrefix(p, "/v1/returns/"):
		rma := map[string]any{"id": "rma-1", "number": "RMA-1", "orderId": "ord-1", "orderNumber": "AT-000001", "customerId": "cus-1", "status": "REQUESTED", "estimateMinor": 1200, "createdAt": now}
		if p == "/v1/returns" {
			writeJ(w, 200, []any{rma})
		} else {
			writeJ(w, 200, rma)
		}
	case p == "/v1/refunds":
		writeJ(w, 201, map[string]any{"id": "ref-1", "status": "PENDING"})
	case p == "/v1/tax/rates":
		writeJ(w, 200, map[string]any{"states": []any{map[string]any{"country": "US", "state": "TX", "name": "Texas", "generalRate": 0.0625, "groceryRate": 0, "preparedFoodRate": 0.0625, "version": 3}},
			"locals": []any{map[string]any{"state": "TX", "zipPrefix": "770", "name": "Houston", "rate": 0.02}}})
	case strings.HasPrefix(p, "/v1/tax/rates/"):
		writeJ(w, 200, body)
	// payments
	case p == "/v1/payouts/requests":
		writeJ(w, 200, []any{map[string]any{"id": "pay-1", "orgId": "org-1", "amountMinor": 50000, "status": f.payoutSt, "requestedAt": now}})
	case strings.HasPrefix(p, "/v1/payouts/requests/"):
		if strings.HasSuffix(p, "/approve") {
			f.mu.Lock()
			f.payoutSt = "APPROVED"
			f.mu.Unlock()
		}
		writeJ(w, 200, map[string]any{"id": "pay-1", "orgId": "org-1", "amountMinor": 50000, "status": f.payoutSt, "requestedAt": now})
	case p == "/v1/fee-schedules":
		fs := map[string]any{"id": "fee-1", "version": 1, "effectiveFrom": now, "categoryBps": map[string]any{"BEAUTY": 1200}, "defaultCommissionBps": 1000}
		if r.Method == http.MethodPost {
			writeJ(w, 201, fs)
			return
		}
		writeJ(w, 200, []any{fs})
	// inventory, retail, fulfilment
	case p == "/v1/lots" || strings.HasPrefix(p, "/v1/lots/"):
		lot := map[string]any{"id": "lot-1", "sku": "AT-SHEA", "lotNo": "L1", "nodeId": "HOU", "qtyOnHand": 10, "qtyReserved": 2, "qtyAvailable": 8, "status": "SELLABLE", "version": 4}
		if p == "/v1/lots" {
			writeJ(w, 200, page(lot))
		} else {
			writeJ(w, 200, lot)
		}
	case p == "/v1/purchase-orders" || strings.HasPrefix(p, "/v1/purchase-orders/"):
		po := map[string]any{"id": "po-1", "number": "PO-1", "supplierCode": "OLAM", "status": "PENDING_APPROVAL", "totalMinor": 400000, "version": 2,
			"lines":   []any{map[string]any{"lineNo": 1, "sku": "AT-JOL", "description": "Jollof", "qtyOrdered": 100, "unitCostMinor": 180, "amountMinor": 18000}},
			"history": []any{map[string]any{"at": now, "status": "PENDING_APPROVAL", "by": "buyer"}}}
		if p == "/v1/purchase-orders" {
			writeJ(w, 200, page(po))
		} else {
			if r.Method == http.MethodPost && r.Header.Get("If-Match") != "2" {
				problemJ(w, 412, "retail.version-mismatch")
				return
			}
			writeJ(w, 200, po)
		}
	case p == "/v1/dispatch/routes" || strings.HasPrefix(p, "/v1/dispatch/routes/"):
		rt := map[string]any{"id": "rte-1", "name": "HOU-W", "zone": "Alief", "status": "PLANNED", "serviceDate": "2026-10-10",
			"stops": []any{map[string]any{"id": "st-1", "sequence": 1, "name": "Amara", "status": "PENDING"}}}
		if p == "/v1/dispatch/routes" {
			writeJ(w, 200, []any{rt})
		} else {
			writeJ(w, 200, rt)
		}
	// content
	case p == "/v1/markets/draft/countries":
		writeJ(w, 200, []any{map[string]any{"code": "CA", "name": "Canada", "status": "COMING_SOON", "regionCount": 13, "liveRegions": 0}})
	case strings.HasPrefix(p, "/v1/markets/"):
		writeJ(w, 200, map[string]any{"code": "CA", "name": "Canada", "status": "COMING_SOON", "regions": []any{map[string]any{"code": "ON", "name": "Ontario", "capabilities": map[string]any{"sell": true}}}})
	case p == "/v1/site-config/settings" || p == "/v1/site-config":
		writeJ(w, 200, map[string]any{"revision": 14, "smsProgramName": "AfroTransact Alerts", "contacts": map[string]any{"supportPhone": "+18324300000", "supportPhoneDisplay": "(832) 430-0000",
			"emails": map[string]any{"support": "help@afrotransact.com"}}, "socials": []any{map[string]any{"network": "instagram", "url": "https://instagram.com/afrotransact"}}})
	case p == "/v1/legal":
		writeJ(w, 200, map[string]any{"documents": []any{map[string]any{"key": "terms", "title": "Conditions of Use", "version": "3.2", "effectiveDate": "2026-09-01"}}})
	case strings.HasPrefix(p, "/v1/legal/"):
		writeJ(w, 200, []any{map[string]any{"version": "3.2", "effectiveDate": "2026-09-01", "status": "CURRENT"}})
	case p == "/v1/placements" || strings.HasPrefix(p, "/v1/placements/"):
		pl := map[string]any{"id": "plc-1", "slot": "announcement_bar", "name": "Free delivery", "status": "DRAFT", "payload": map[string]any{"text": "Free delivery over $49"},
			"schedule": map[string]any{"startsAt": now}, "version": 1}
		switch {
		case p == "/v1/placements" && r.Method == http.MethodPost:
			writeJ(w, 201, pl)
		case p == "/v1/placements":
			writeJ(w, 200, []any{pl})
		default:
			writeJ(w, 200, pl)
		}
	// notifications
	case p == "/v1/notifications":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"id": "ntf-1", "recipientId": "cus-1", "channel": "sms", "templateKey": "delivery.otp", "status": "failed", "address": "+18325550100", "createdAt": now}}})
	case strings.HasPrefix(p, "/v1/notifications/"):
		writeJ(w, 200, map[string]any{"id": "ntf-1", "recipientId": "cus-1", "channel": "sms", "templateKey": "delivery.otp", "status": "failed"})
	case strings.HasPrefix(p, "/v1/dead-letters/"):
		writeJ(w, 200, map[string]any{"id": "ntf-1"})
	default:
		http.NotFound(w, r) // a route the service does not serve (bookings' customer lookup) → 503 upstream unavailable
	}
}

// staffSessions resolves the bearer token as the caller (the identity fake decides who that is).
type staffSessions struct{ app.SessionService }

func (staffSessions) Resolve(_ context.Context, in app.ResolveInput) (*domain.Caller, error) {
	if in.BearerToken == "" {
		return &domain.Caller{}, nil
	}
	return &domain.Caller{App: true, BearerToken: in.BearerToken, Subject: "sub-" + in.BearerToken}, nil
}

func staffBFF(t *testing.T, f *fakePlatform) (http.Handler, func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder) {
	t.Helper()
	srv := httptest.NewServer(f)
	t.Cleanup(srv.Close)
	metrics := httpclient.NewMetrics(prometheus.NewRegistry())
	up := func(name string) port.StaffUpstream {
		return upstreams.NewStaffUpstream(httpclient.New(httpclient.Options{Name: name, BaseURL: srv.URL, Timeout: 3 * time.Second, Metrics: metrics, Transport: http.DefaultTransport}))
	}
	files, err := exportfiles.NewLocal(exportfiles.Options{Dir: t.TempDir(), SigningKey: "test-key", BasePath: "/api/bff"})
	if err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	d := app.StaffDeps{Up: port.StaffUpstreams{Identity: up("identity"), Seller: up("seller"), Orders: up("orders"), Catalog: up("catalog"), TrustOps: up("trust-ops"),
		TaxReturns: up("tax-returns"), Payments: up("payments"), Inventory: up("inventory"), Retail: up("retail"), Fulfilment: up("fulfilment"),
		Content: up("content"), Notifications: up("notifications"), Bookings: up("bookings")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Jobs:   exportfiles.NewMemoryJobs(), Blobs: files, ExportBasePath: "/api/bff", Log: log, Background: func(fn func()) { fn() }}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Files: files}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Services{Sessions: staffSessions{}, Staff: staff}, nil, httpapi.SecurityOptions{AllowedOrigins: []string{"http://localhost:3002"}}, log).Register(mux)
	do := func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder {
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
	return mux, do
}

func jsonOf(t *testing.T, rec *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("decode %q: %v", rec.Body.String(), err)
	}
	return m
}

// TestStaffContractConformance drives every staff operation through the real handlers and use cases against fake
// upstreams and validates each answer against gateway-bff-staff.yaml.
func TestStaffContractConformance(t *testing.T) {
	doc, err := openapi3.NewLoader().LoadFromFile(staffContractPath)
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
	f := &fakePlatform{payoutSt: "REQUESTED"}
	_, raw := staffBFF(t, f)
	validated := map[string]int{}
	do := func(token, method, path string, body any, want int, headers ...string) map[string]any {
		t.Helper()
		rec := raw(token, method, path, body, headers...)
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
		if strings.HasPrefix(rec.Header().Get("Content-Type"), "application/") && strings.Contains(rec.Header().Get("Content-Type"), "json") {
			if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
				t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", method, path, rec.Code, err, rec.Body.String())
			}
		}
		validated[route.Operation.OperationID]++
		if strings.Contains(rec.Header().Get("Content-Type"), "json") {
			return jsonOf(t, rec)
		}
		return map[string]any{"_body": rec.Body.String(), "_location": rec.Header().Get("Location")}
	}

	// Shell: guests, customers, staff with RBAC-filtered navigation.
	do("", "GET", "/v1/staff/session", nil, 401)
	if c := do("customer", "GET", "/v1/staff/session", nil, 403)["code"]; c != domain.CodeNotStaff {
		t.Fatalf("code = %v", c)
	}
	admin := do("admin", "GET", "/v1/staff/session", nil, 200)
	if admin["landing"] != "dash" || len(admin["navGroups"].([]any)) != 7 {
		t.Fatalf("admin session = %v", admin)
	}
	support := do("support", "GET", "/v1/staff/session", nil, 200)
	keys := []string{}
	for _, g := range support["navGroups"].([]any) {
		for _, m := range g.(map[string]any)["modules"].([]any) {
			keys = append(keys, m.(map[string]any)["key"].(string))
		}
	}
	joined := strings.Join(keys, ",")
	if !strings.Contains(joined, "support") || strings.Contains(joined, "rbac") || strings.Contains(joined, "fees") {
		t.Fatalf("support modules = %v", keys)
	}
	if support["refundLimitMinor"].(float64) != 25000 {
		t.Fatalf("refund limit = %v", support["refundLimitMinor"])
	}

	// Dashboard: one feed.
	dash := do("admin", "GET", "/v1/staff/dashboard?rangeDays=30", nil, 200)
	if len(dash["charts"].([]any)) != 4 {
		t.Fatalf("charts = %v", dash["charts"])
	}
	do("admin", "GET", "/v1/staff/dashboard?rangeDays=5", nil, 422)

	// Every table and its first sheet.
	for _, res := range domain.ResourceKeys {
		pg := do("admin", "GET", "/v1/staff/resources/"+res+"?size=10", nil, 200)
		rows := pg["rows"].([]any)
		if len(rows) == 0 {
			t.Fatalf("%s: no rows", res)
		}
		id := rows[0].(map[string]any)["id"].(string)
		do("admin", "GET", "/v1/staff/resources/"+res+"/"+id, nil, 200)
	}
	do("admin", "GET", "/v1/staff/resources/nope", nil, 404)
	do("admin", "GET", "/v1/staff/resources/claims?status=NOPE", nil, 422)
	do("support", "GET", "/v1/staff/resources/users", nil, 403)

	// Actions follow permissions and are enforced server-side.
	sheet := do("support", "GET", "/v1/staff/resources/payouts/pay-1", nil, 200)
	for _, a := range sheet["actions"].([]any) {
		if a.(map[string]any)["allowed"] == true {
			t.Fatalf("support may not act on payouts: %v", a)
		}
	}
	do("support", "POST", "/v1/staff/resources/payouts/pay-1/actions/approve", nil, 403)
	if do("admin", "POST", "/v1/staff/resources/payouts/pay-1/actions/approve", nil, 200)["status"] != "APPROVED" {
		t.Fatal("payout not approved")
	}
	// Two-step review: the step-one reviewer cannot give final approval.
	app1 := do("admin", "GET", "/v1/staff/resources/applications/app-1", nil, 200)
	for _, a := range app1["actions"].([]any) {
		if m := a.(map[string]any); m["key"] == "final-approve" && (m["allowed"] != false || !strings.Contains(m["reason"].(string), "different approver")) {
			t.Fatalf("final approval by the reviewer: %v", m)
		}
	}
	do("admin", "POST", "/v1/staff/resources/applications/app-1/actions/final-approve", map[string]any{"values": map[string]any{"reasonCode": "DOCUMENTS_VERIFIED"}}, 409)
	do("admin", "POST", "/v1/staff/resources/applications/app-1/actions/send-back", map[string]any{"values": map[string]any{"reasonCode": "DOCUMENTS_VERIFIED", "note": "Check the EIN"}}, 200)
	// Claim refunds above the role's limit are refused; forms are validated.
	do("support", "POST", "/v1/staff/resources/claims/clm-1/actions/refund", map[string]any{"values": map[string]any{"note": "ok"}}, 409)
	do("admin", "POST", "/v1/staff/resources/claims/clm-1/actions/refund", map[string]any{"values": map[string]any{}}, 422)
	do("admin", "POST", "/v1/staff/resources/claims/clm-1/actions/refund", map[string]any{"values": map[string]any{"note": "Photos confirm damage"}}, 200)
	if f.bodies["POST /v1/claims/clm-1/decision"]["outcome"] != "REFUND" {
		t.Fatalf("decision body = %v", f.bodies["POST /v1/claims/clm-1/decision"])
	}
	do("admin", "POST", "/v1/staff/resources/purchase-orders/po-1/actions/approve", map[string]any{"values": map[string]any{"note": "ok"}}, 200)
	do("admin", "POST", "/v1/staff/resources/orders/AT-000001/actions/shipment-status", map[string]any{"values": map[string]any{"shipmentId": "shp-1", "to": "CANCELLED", "reason": "Customer asked"}}, 200)
	do("admin", "POST", "/v1/staff/resources/orders/AT-000001/actions/nope", nil, 404)
	// Create and edit with validation.
	do("admin", "POST", "/v1/staff/resources/tax-rates", map[string]any{"values": map[string]any{"state": "Texas", "zipPrefix": "77", "name": "X", "rate": 0.5}}, 422)
	do("admin", "POST", "/v1/staff/resources/tax-rates", map[string]any{"values": map[string]any{"state": "TX", "zipPrefix": "770", "name": "Houston", "rate": 0.02}}, 201)
	do("admin", "POST", "/v1/staff/resources/announcements", map[string]any{"values": map[string]any{"name": "Free delivery", "text": "Free delivery over $49", "startsAt": "2026-10-10T00:00:00Z"}}, 201)
	do("admin", "PATCH", "/v1/staff/resources/markets/CA", map[string]any{"values": map[string]any{"status": "LIVE", "launchNote": "Ontario first"}}, 200)
	do("admin", "PATCH", "/v1/staff/resources/site-settings/site", map[string]any{"values": map[string]any{"supportPhone": "+18324300000", "supportPhoneDisplay": "(832) 430-0000",
		"supportEmail": "help@afrotransact.com", "smsProgramName": "AfroTransact Alerts", "summary": "New TikTok", "social.tiktok": "https://tiktok.com/@afrotransact"}}, 200)
	if b := f.bodies["PUT /v1/site-config"]; b["expectedRevision"] != float64(14) || len(b["socials"].([]any)) != 1 {
		t.Fatalf("site config body = %v", b)
	}
	do("support", "PATCH", "/v1/staff/resources/markets/CA", map[string]any{"values": map[string]any{"status": "LIVE"}}, 403)
	do("admin", "POST", "/v1/staff/resources/claims/clm-1/notes", map[string]any{"text": "Called the seller"}, 503)

	// Exports: small ones finish within the request, behind a signed, expiring link.
	job := do("admin", "POST", "/v1/staff/exports", map[string]any{"resource": "claims", "format": "csv"}, 201)
	if job["status"] != "READY" || job["async"] != false {
		t.Fatalf("job = %v", job)
	}
	id := job["id"].(string)
	do("admin", "GET", "/v1/staff/exports/"+id, nil, 200)
	if items := do("admin", "GET", "/v1/staff/exports", nil, 200)["items"].([]any); len(items) != 1 {
		t.Fatalf("exports = %v", items)
	}
	do("support", "GET", "/v1/staff/exports/"+id, nil, 404)
	loc := do("admin", "GET", "/v1/staff/exports/"+id+"/download", nil, 302)["_location"].(string)
	if !strings.HasPrefix(loc, "/api/bff/v1/staff/export-files/"+id+"?") {
		t.Fatalf("location = %q", loc)
	}
	file := do("", "GET", strings.TrimPrefix(loc, "/api/bff"), nil, 200)["_body"].(string)
	if !strings.Contains(file, "CLM-1") || !strings.HasPrefix(file, "\ufeffClaim,") {
		t.Fatalf("csv = %q", file)
	}
	do("", "GET", "/v1/staff/export-files/"+id+"?expires=9999999999&sig=bad", nil, 403)
	do("admin", "POST", "/v1/staff/exports", map[string]any{"resource": "markets", "format": "csv"}, 422)
	xlsx := do("admin", "POST", "/v1/staff/exports", map[string]any{"resource": "orders", "format": "xlsx"}, 201)
	xloc := do("admin", "GET", "/v1/staff/exports/"+xlsx["id"].(string)+"/download", nil, 302)["_location"].(string)
	if body := raw("", "GET", strings.TrimPrefix(xloc, "/api/bff"), nil).Body.String(); !strings.HasPrefix(body, "PK") {
		t.Fatal("xlsx is not a zip")
	}
	// Large exports run in the background (inline here) and page through every upstream page.
	f.bigOrders = true
	big := do("admin", "POST", "/v1/staff/exports", map[string]any{"resource": "orders", "format": "csv"}, 202)
	if big["status"] != "QUEUED" || big["async"] != true {
		t.Fatalf("big = %v", big)
	}
	done := do("admin", "GET", "/v1/staff/exports/"+big["id"].(string), nil, 200)
	if done["status"] != "READY" || done["rows"].(float64) != 6000 {
		t.Fatalf("big done = %v", done)
	}
	f.bigOrders = false

	// Support console.
	do("support", "GET", "/v1/staff/support/customers?q=a", nil, 422)
	if items := do("support", "GET", "/v1/staff/support/customers?q=amara", nil, 200)["items"].([]any); len(items) != 1 {
		t.Fatalf("hits = %v", items)
	}
	c360 := do("support", "GET", "/v1/staff/support/customers/cus-1", nil, 200)
	if len(c360["orders"].([]any)) != 2 || len(c360["claims"].([]any)) == 0 || !strings.Contains(fmt.Sprint(c360["unavailable"]), "bookings") {
		t.Fatalf("customer 360 = %v", c360)
	}
	refund := map[string]any{"customerId": "cus-1", "orderNumber": "AT-000001", "amountMinor": 30000, "reason": "Late delivery", "via": "ORDER"}
	if c := do("support", "POST", "/v1/staff/support/refunds", refund, 403, "Idempotency-Key", "refund-000001")["code"]; c != domain.CodeRefundLimit {
		t.Fatalf("code = %v", c)
	}
	refund["amountMinor"] = 1500
	do("support", "POST", "/v1/staff/support/refunds", refund, 422)
	if r := do("support", "POST", "/v1/staff/support/refunds", refund, 200, "Idempotency-Key", "refund-000002"); r["audited"] != true {
		t.Fatalf("refund = %v", r)
	}
	if lines := f.bodies["POST /v1/refunds"]["lines"].([]any); len(lines) != 1 || lines[0].(map[string]any)["itemsMinor"] != float64(1500) {
		t.Fatalf("refund lines = %v", lines)
	}
	refund["amountMinor"] = 5000
	do("support", "POST", "/v1/staff/support/refunds", refund, 422, "Idempotency-Key", "refund-000003")
	do("support", "POST", "/v1/staff/support/notifications/ntf-1/resend", map[string]any{"customerId": "cus-1"}, 403)
	do("admin", "POST", "/v1/staff/support/notifications/ntf-1/resend", map[string]any{"customerId": "cus-1"}, 200)
	if !f.called("POST /v1/dead-letters/ntf-1/replay") {
		t.Fatal("failed notification not replayed")
	}
	do("support", "POST", "/v1/staff/support/shipments/shp-1/cancel", map[string]any{"customerId": "cus-1", "orderNumber": "AT-000001", "reason": "Ordered twice"}, 200)
	do("support", "POST", "/v1/staff/support/shipments/shp-1/cancel", map[string]any{"customerId": "cus-1", "orderNumber": "AT-999999", "reason": "Ordered twice"}, 404)

	for _, op := range []string{"getStaffSession", "getStaffDashboard", "listStaffResource", "createStaffRecord", "getStaffRecord", "updateStaffRecord", "runStaffAction",
		"addStaffNote", "listStaffExports", "createStaffExport", "getStaffExport", "downloadStaffExport", "getStaffExportFile", "searchSupportCustomers",
		"getSupportCustomer", "supportRefund", "supportResendNotification", "supportCancelShipment"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not exercised", op)
		}
	}
}

// TestStaffAccessLevels checks module levels over several roles and the derived modules.
func TestStaffAccessLevels(t *testing.T) {
	a := domain.StaffAccess{Roles: []string{"platform.customer_support", "platform.finance"}, Permissions: []string{"support:respond"}, Matrix: domain.DefaultPlatformMatrix}
	if a.Level("payouts") != domain.LevelApprove || a.Level("support") != domain.LevelEdit || a.Level("rbac") != domain.LevelNone {
		t.Fatalf("levels: payouts=%s support=%s rbac=%s", a.Level("payouts"), a.Level("support"), a.Level("rbac"))
	}
	if a.Level("exports") != a.Level("dash") {
		t.Fatal("exports follow the dashboard level")
	}
	if l := domain.RefundLimit([]string{"platform.customer_support", "platform.finance"}); l != nil {
		t.Fatalf("finance has no refund limit, got %v", *l)
	}
	if l := domain.RefundLimit([]string{"platform.marketing"}); l == nil || *l != 0 {
		t.Fatal("marketing may not refund")
	}
}
