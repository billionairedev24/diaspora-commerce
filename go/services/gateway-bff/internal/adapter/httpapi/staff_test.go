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
	"inventory:adjust", "inventory:read", "notifications:manage", "notifications:read", "notifications:templates:write", "orders:manage", "orders:read", "payments:configure", "payouts:approve", "payouts:read",
	"refunds:issue", "reports:read", "retail:po:approve", "retail:po:write", "returns:manage", "support:respond", "tax:configure", "users:read"}

type fakePlatform struct {
	mu        sync.Mutex
	calls     []string
	bodies    map[string]map[string]any
	payoutSt  string
	bigOrders bool
	fees      []map[string]any
	// override answers a request before the defaults when it returns true (scenario tests).
	override func(w http.ResponseWriter, r *http.Request, body map[string]any) bool
}

// feeSchedules fakes payments' fee-schedule routes: v1 in force, v2 scheduled (with overrides), v3 cancelled.
func (f *fakePlatform) feeSchedules(w http.ResponseWriter, r *http.Request, p string, body map[string]any) {
	f.mu.Lock()
	defer f.mu.Unlock()
	day := 24 * time.Hour
	if f.fees == nil {
		at := func(d time.Duration) string { return time.Now().UTC().Add(d).Format(time.RFC3339) }
		f.fees = []map[string]any{
			{"id": "fee-1", "version": 1, "effectiveFrom": at(-90 * day), "currency": "USD", "categoryBps": map[string]any{"groceries": 800, "beauty": 1000},
				"defaultCommissionBps": 1000, "overrides": []any{}, "cardPctBps": 290, "cardFixedMinor": 30, "fbaPerUnitMinor": 325,
				"reverseCommissionOnRefund": true, "note": "Launch defaults", "createdBy": "system:seed", "createdAt": at(-90 * day),
				"deliveryPricing": map[string]any{"enabled": true, "maxRadiusMiles": 25,
					"customerCharge":  map[string]any{"mode": "DISTANCE", "baseMinor": 499, "perMileMinor": 85, "minMinor": 599, "maxMinor": 2499},
					"sellerFee":       map[string]any{"mode": "FLAT", "flatPerPackageMinor": 200},
					"sellerOverrides": []any{map[string]any{"orgId": "org-1", "sellerFee": map[string]any{"mode": "FLAT", "flatPerPackageMinor": 150}}}}},
			{"id": "fee-2", "version": 2, "effectiveFrom": at(10 * day), "currency": "USD", "categoryBps": map[string]any{"groceries": 700, "beauty": 1000},
				"defaultCommissionBps": 1000, "overrides": []any{map[string]any{"orgId": "org-1", "category": "*", "commissionBps": 600}},
				"cardPctBps": 290, "cardFixedMinor": 30, "fbaPerUnitMinor": 325, "reverseCommissionOnRefund": true, "note": "Grocery promo",
				"createdBy": "stf-1", "createdAt": at(-day)},
			{"id": "fee-3", "version": 3, "effectiveFrom": at(5 * day), "currency": "USD", "categoryBps": map[string]any{}, "defaultCommissionBps": 900,
				"overrides": []any{}, "cardPctBps": 290, "cardFixedMinor": 30, "fbaPerUnitMinor": 325, "reverseCommissionOnRefund": true, "note": "Typo",
				"createdBy": "stf-1", "createdAt": at(-2 * day), "cancelledAt": at(-day), "cancelledBy": "stf-1", "cancelReason": "Wrong default"},
		}
	}
	switch {
	case p == "/v1/fee-schedules" && r.Method == http.MethodPost:
		created := map[string]any{"id": fmt.Sprintf("fee-%d", len(f.fees)+1), "version": len(f.fees) + 1, "createdBy": "stf-1", "createdAt": time.Now().UTC().Format(time.RFC3339)}
		for k, v := range body {
			created[k] = v
		}
		if _, ok := created["effectiveFrom"]; !ok {
			created["effectiveFrom"] = time.Now().UTC().Format(time.RFC3339)
		}
		f.fees = append(f.fees, created)
		writeJ(w, 201, created)
	case p == "/v1/fee-schedules":
		out := make([]any, 0, len(f.fees))
		for i := len(f.fees) - 1; i >= 0; i-- {
			out = append(out, f.fees[i])
		}
		writeJ(w, 200, out)
	case strings.HasSuffix(p, "/cancel"):
		id := strings.TrimSuffix(strings.TrimPrefix(p, "/v1/fee-schedules/"), "/cancel")
		for _, s := range f.fees {
			if s["id"] == id {
				s["cancelledAt"], s["cancelledBy"], s["cancelReason"] = time.Now().UTC().Format(time.RFC3339), "stf-1", body["reason"]
				writeJ(w, 200, s)
				return
			}
		}
		problemJ(w, 404, "payments.fee-schedule-not-found")
	default:
		http.NotFound(w, r)
	}
}

func problemDetail(w http.ResponseWriter, status int, code, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": code, "status": status, "code": code, "detail": detail})
}

// templates fakes notifications' template routes: order.confirmed in email (v1, v2) and sms (v1), plus its sample.
func (f *fakePlatform) templates(w http.ResponseWriter, r *http.Request, p string, body map[string]any) {
	tpl := func(channel string, version int, subject, text, by, at string) map[string]any {
		html := ""
		if channel == "email" {
			html = "<p>Thanks {{.firstName}}</p>"
		}
		return map[string]any{"key": "order.confirmed", "locale": "en", "channel": channel, "version": version, "category": "transactional",
			"subject": subject, "bodyHtml": html, "bodyText": text, "createdBy": by, "createdAt": at}
	}
	all := []any{
		tpl("email", 2, "Order {{.orderNumber}} is confirmed", "Thanks {{.firstName}}", "stf-1", "2026-10-09T10:00:00Z"),
		tpl("email", 1, "Order {{.orderNumber}} confirmed", "Thanks", "system", "2026-09-01T10:00:00Z"),
		tpl("sms", 1, "", "AfroTransact: order {{.orderNumber}} confirmed", "system", "2026-09-01T10:00:00Z"),
	}
	draft, _ := body["draft"].(map[string]any)
	switch {
	case p == "/v1/templates" && r.Method == http.MethodGet && r.URL.Query().Get("key") == "":
		writeJ(w, 200, map[string]any{"items": []any{all[0], all[2],
			map[string]any{"key": "identity.verification", "locale": "en", "channel": "sms", "version": 1, "category": "security", "bodyText": "Code {{.code}}",
				"createdBy": "system", "createdAt": "2026-09-01T10:00:00Z"}}})
	case p == "/v1/templates" && r.Method == http.MethodGet:
		if r.URL.Query().Get("key") != "order.confirmed" {
			writeJ(w, 200, map[string]any{"items": []any{}})
			return
		}
		writeJ(w, 200, map[string]any{"items": all})
	case p == "/v1/templates" && r.Method == http.MethodPost:
		out := map[string]any{"version": 3, "createdBy": "stf-1", "createdAt": "2026-10-10T12:00:00Z"}
		for k, v := range body {
			out[k] = v
		}
		writeJ(w, 201, out)
	case p == "/v1/templates/order.confirmed/sample":
		writeJ(w, 200, map[string]any{"key": "order.confirmed", "area": "orders", "source": "event", "eventTypes": []string{"orders.order.placed"},
			"data": map[string]any{"firstName": "Ada", "orderNumber": "AT-2026-7KQ3MD", "total": "$48.00"}})
	case strings.HasSuffix(p, "/sample"):
		problemJ(w, 404, "notifications.template-not-found")
	case strings.HasSuffix(p, "/preview") || strings.HasSuffix(p, "/test-send"):
		text := "AfroTransact: order AT-2026-7KQ3MD confirmed"
		if draft != nil {
			text, _ = draft["bodyText"].(string)
		}
		if strings.Contains(text, "{{.missing}}") {
			problemDetail(w, 400, "notifications.invalid-request", `invalid request
template render failed: text: template: text:1:2: executing "text" at <.missing>: map has no entry for key "missing"`)
			return
		}
		if strings.HasSuffix(p, "/test-send") {
			if body["channel"] == "sms" {
				problemDetail(w, 422, "notifications.no-own-contact", "Test-send goes only to your own email or phone, and none is on your account")
				return
			}
			writeJ(w, 200, map[string]any{"to": "ifeoma@afrotransact.com", "receipt": map[string]any{"provider": "smtp", "providerMessageId": "m-1"}})
			return
		}
		version := 2
		if draft != nil {
			version = 0
		}
		writeJ(w, 200, map[string]any{"subject": "Order AT-2026-7KQ3MD is confirmed", "html": "<p>Thanks Ada</p>", "text": text, "version": version,
			"category": "transactional"})
	default:
		http.NotFound(w, r)
	}
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
			"nextActions": []any{"CONFIRMED", "CANCELLED"}, "cancellable": true, "totalMinor": 2764, "timeline": []any{map[string]any{"to": "PLACED", "at": time.Now().UTC().Format(time.RFC3339), "source": "checkout"}},
			"lines": []any{map[string]any{"id": "ln-1", "title": "Shea butter", "category": "BEAUTY", "quantity": 1, "itemsMinor": 2000, "discountMinor": 0}}}}}
}

func (f *fakePlatform) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body := f.record(r)
	if f.override != nil && f.override(w, r, body) {
		return
	}
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
		case "auditor":
			writeJ(w, 200, map[string]any{"id": "stf-7", "firstName": "Kofi", "lastName": "Mensah", "email": "kofi@afrotransact.com", "principalType": "STAFF",
				"roles": []string{"platform.auditor"}, "permissions": []string{"audit:read", "payments:read", "orgs:read"}})
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
	// offers: platform coupons
	case p == "/v1/coupons":
		writeJ(w, 200, []any{map[string]any{"id": "cp-1", "code": "WELCOME5", "description": "$5 off a first order", "type": "FIXED", "value": 500, "currency": "USD",
			"scope": "CART", "status": "ACTIVE", "funding": "PLATFORM", "redeemedCount": 12, "version": 2}})
	// carrier-gateway: customer pickup points
	case p == "/v1/pickup-locations":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"id": "pl-1", "name": "Houston counter", "kind": "PLATFORM_COUNTER", "active": true,
			"address": map[string]any{"line1": "100 Main St", "city": "Houston", "state": "TX", "postalCode": "77002"},
			"hours":   []any{map[string]any{"weekday": 1, "open": "09:00", "close": "17:00"}}, "readyHours": 2}}})
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
	case p == "/v1/fee-schedules" || strings.HasPrefix(p, "/v1/fee-schedules/"):
		f.feeSchedules(w, r, p, body)
	// catalog taxonomy and seller's organisation directory (fee editor)
	case p == "/v1/categories":
		writeJ(w, 200, []any{map[string]any{"id": "d1", "name": "Groceries", "level": "DEPARTMENT", "path": "Groceries"},
			map[string]any{"id": "d2", "name": "Home & Art", "level": "DEPARTMENT", "path": "Home & Art"},
			map[string]any{"id": "c1", "parentId": "d1", "name": "Rice", "level": "CATEGORY", "path": "Groceries › Rice"}})
	case p == "/v1/orgs/directory":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"id": "org-1", "displayName": "Kente Naturals", "kind": "SELLER", "status": "APPROVED"},
			map[string]any{"id": "org-2", "displayName": "Ade's Kitchen", "kind": "SELLER", "status": "APPROVED"},
			map[string]any{"id": "org-retail", "displayName": "AfroTransact Retail", "kind": "SELLER", "status": "APPROVED"}}, "page": 0, "size": 200, "total": 3})
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
	// payments: AfroTransact Delivery quote (S255)
	case p == "/v1/delivery-pricing/quote":
		miles, _ := body["distanceMiles"].(float64)
		if miles > 25 {
			writeJ(w, 200, map[string]any{"available": false, "unavailableReason": "BEYOND_MAX_RADIUS", "customerChargeMinor": 0, "sellerFeeMinor": 0,
				"currency": "USD", "scheduleVersion": 1, "maxRadiusMiles": 25})
			return
		}
		writeJ(w, 200, map[string]any{"available": true, "customerChargeMinor": 499 + int(85*miles+0.5), "sellerFeeMinor": 200, "sellerFeeSource": "DEFAULT",
			"currency": "USD", "scheduleId": "fee-1", "scheduleVersion": 1, "maxRadiusMiles": 25, "distanceMiles": miles})
	// fulfilment: seller pickups (S255)
	case p == "/v1/dispatch/shipments":
		writeJ(w, 200, []any{
			map[string]any{"shipmentId": "shp-9", "orderNumber": "AT-000009", "sellerOrgId": "org-1", "sellerName": "Kente Naturals", "origin": "SELLER",
				"city": "Houston", "state": "TX", "postalCode": "77042", "perishable": true, "units": 2, "status": "AWAITING_ROUTE", "promisedBy": "2026-10-11",
				"createdAt": now},
			map[string]any{"shipmentId": "shp-8", "orderNumber": "AT-000008", "origin": "FC", "city": "Houston", "state": "TX", "status": "AWAITING_ROUTE"}})
	case strings.HasSuffix(p, "/driver-suggestions") && strings.HasPrefix(p, "/v1/dispatch/pickups/"):
		if strings.Contains(p, "/nope/") {
			problemJ(w, 404, "fulfilment.shipment-not-found")
			return
		}
		writeJ(w, 200, map[string]any{"shipmentId": "shp-9", "drivers": []any{
			map[string]any{"driverId": "drv-1", "routeId": "rte-1", "routeName": "HOU-W", "zone": "Alief", "routeStatus": "IN_PROGRESS", "straightLineMiles": 2.1,
				"roadMiles": 2.9, "durationMinutes": 9, "distanceMethod": "ROAD_OSRM", "positionAt": now, "packagesOnRoute": 12, "capacityLeft": 28},
			map[string]any{"driverId": "drv-2", "routeId": "rte-2", "routeName": "HOU-S", "zone": "Alief", "routeStatus": "ASSIGNED", "straightLineMiles": 4.8,
				"distanceMethod": "STRAIGHT_LINE", "positionAt": now, "packagesOnRoute": 30, "capacityLeft": 10}}})
	case strings.HasSuffix(p, "/assign") && strings.HasPrefix(p, "/v1/dispatch/pickups/"):
		if body["routeId"] == "rte-done" {
			problemJ(w, 409, "fulfilment.pickup-not-movable")
			return
		}
		writeJ(w, 200, map[string]any{"id": body["routeId"], "name": "HOU-S", "zone": "Alief", "status": "ASSIGNED", "driverId": "drv-2"})
	case strings.HasSuffix(p, "/assignments") && strings.HasPrefix(p, "/v1/dispatch/pickups/"):
		writeJ(w, 200, []any{
			map[string]any{"id": "pa-1", "shipmentId": "shp-9", "routeId": "rte-1", "driverId": "drv-1", "mode": "AUTO_NEAREST", "straightMiles": 2.1, "roadMiles": 2.9,
				"distanceMethod": "ROAD_OSRM", "actor": "system", "createdAt": "2026-10-10T08:00:00Z"},
			map[string]any{"id": "pa-2", "shipmentId": "shp-9", "routeId": "rte-2", "driverId": "drv-2", "mode": "DISPATCH_OVERRIDE", "reason": "Driver 1 has a flat tyre",
				"previousRouteId": "rte-1", "actor": "stf-1", "createdAt": "2026-10-10T09:00:00Z"}})
	// seller: pickup locations
	case strings.HasPrefix(p, "/v1/ship-from-locations/"):
		writeJ(w, 200, map[string]any{"id": "loc-1", "orgId": "org-1", "label": "Store", "line1": "9800 Bissonnet St", "city": "Houston", "state": "TX",
			"postalCode": "77036", "country": "US", "position": map[string]any{"lat": 29.67, "lng": -95.55, "precision": "ADDRESS"},
			"afrotransactPickup": map[string]any{"enabled": true, "zoneName": "Houston Metro", "hours": []any{map[string]any{"day": "MONDAY", "opens": "09:00", "closes": "17:00"}}}})
	// fulfilment: dispatch board (S281)
	case p == "/v1/dispatch/sos":
		writeJ(w, 200, []any{map[string]any{"alertId": "sos-1", "driverId": "drv-1", "routeId": "rte-1", "kind": "BREAKDOWN", "lat": 29.7, "lon": -95.5,
			"note": "Flat tyre on I-69", "raisedAt": now, "receivedAt": now}})
	case strings.HasPrefix(p, "/v1/dispatch/sos/") && strings.HasSuffix(p, "/acknowledge"):
		w.WriteHeader(http.StatusNoContent)
	case p == "/v1/dispatch/defects":
		writeJ(w, 200, []any{map[string]any{"id": "def-1", "routeId": "rte-1", "vehicleId": "VAN-12", "driverId": "drv-1", "critical": true,
			"items": []any{"brakes"}, "note": "Soft brake pedal", "reportedAt": now}})
	case strings.HasPrefix(p, "/v1/dispatch/defects/") && strings.HasSuffix(p, "/resolve"):
		writeJ(w, 200, map[string]any{"id": "rte-1", "status": "ASSIGNED"})
	case p == "/v1/dispatch/events":
		writeJ(w, 200, []any{map[string]any{"eventId": "ev-1", "deviceId": "dev-1", "sequence": 4, "driverId": "drv-1", "type": "delivery.completed",
			"routeId": "rte-1", "stopId": "st-1", "capturedAt": now, "receivedAt": now, "outcome": "APPLIED"}})
	case p == "/v1/dispatch/routes/build":
		if body["depotNodeId"] == "EMPTY-FC" {
			writeJ(w, 201, []any{})
			return
		}
		writeJ(w, 201, []any{map[string]any{"id": "rte-7", "name": "HOU-SW", "status": "PLANNED"}})
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
	case p == "/v1/help/admin/articles":
		items := []any{map[string]any{"slug": "shipping", "topic": "ORDERS_DELIVERY", "topicTitle": "Orders & delivery", "title": "Shipping & delivery",
			"summary": "Carriers and pickup", "status": "PUBLISHED", "sortOrder": 10, "version": 4, "updatedAt": now,
			"sections": []any{map[string]any{"heading": "Pickup", "body": "Collect from a locker."}}}}
		f.mu.Lock()
		created := f.bodies["PUT /v1/help/articles/track-a-package"]
		f.mu.Unlock()
		if created != nil {
			items = append(items, map[string]any{"slug": "track-a-package", "topic": created["topic"], "topicTitle": "Orders & delivery", "title": created["title"],
				"summary": created["summary"], "status": created["status"], "sortOrder": 10, "version": 0, "updatedAt": now, "sections": created["sections"]})
		}
		writeJ(w, 200, items)
	case strings.HasPrefix(p, "/v1/help/articles/") && r.Method == http.MethodGet:
		if p != "/v1/help/articles/shipping" {
			writeJ(w, 404, map[string]any{"status": 404, "code": "content.help-article-not-found", "title": "Not found"})
			return
		}
		writeJ(w, 200, map[string]any{"slug": "shipping"})
	case strings.HasPrefix(p, "/v1/help/articles/"):
		writeJ(w, 200, map[string]any{"slug": strings.TrimPrefix(p, "/v1/help/articles/")})
	case p == "/v1/privacy/requests":
		writeJ(w, 200, []any{map[string]any{"id": "0b1c", "reference": "PR-100001", "type": "access", "email": "ada@example.com", "state": "TX",
			"status": "RECEIVED", "overdue": false, "submittedAt": now, "dueAt": now, "version": 0}})
	case strings.HasPrefix(p, "/v1/privacy/requests/"):
		writeJ(w, 200, map[string]any{"id": "0b1c", "reference": "PR-100001", "type": "access", "email": "ada@example.com", "state": "TX",
			"status": "RECEIVED", "overdue": false, "submittedAt": now, "dueAt": now, "version": 0})
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
	case p == "/v1/templates" || strings.HasPrefix(p, "/v1/templates/"):
		f.templates(w, r, p, body)
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

func staffBFF(t *testing.T, f *fakePlatform) func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder {
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
		Content: up("content"), Notifications: up("notifications"), Bookings: up("bookings"), Carrier: up("carrier-gateway"), Offers: up("offers")},
		Matrix: upstreams.NewOrgAccess(httpclient.New(httpclient.Options{Name: "identity", BaseURL: srv.URL, Metrics: metrics, Transport: http.DefaultTransport})),
		Jobs:   exportfiles.NewMemoryJobs(), Blobs: files, Renderer: exportfiles.NewRenderer(), ExportBasePath: "/api/bff", Log: log, Background: func(fn func()) { fn() }, RetailOrgID: "org-retail"}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Fees: app.NewStaffFeesService(d), Files: files,
		Dispatch: app.NewStaffDispatchService(d), Notifications: app.NewStaffNotificationsService(d)}
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
	return do
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
	raw := staffBFF(t, f)
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
	// Seller refuses every step-2 decision by the step-one reviewer, sending back included.
	do("admin", "POST", "/v1/staff/resources/applications/app-1/actions/send-back", map[string]any{"values": map[string]any{"reasonCode": "DOCUMENTS_VERIFIED", "note": "Check the EIN"}}, 409)
	// Claim refunds above the role's limit are refused; forms are validated.
	do("support", "POST", "/v1/staff/resources/claims/clm-1/actions/refund", map[string]any{"values": map[string]any{"note": "ok"}}, 409)
	do("admin", "POST", "/v1/staff/resources/claims/clm-1/actions/refund", map[string]any{"values": map[string]any{}}, 422)
	do("admin", "POST", "/v1/staff/resources/claims/clm-1/actions/refund", map[string]any{"values": map[string]any{"note": "Photos confirm damage"}}, 200)
	if f.bodies["POST /v1/claims/clm-1/decision"]["outcome"] != "REFUND" {
		t.Fatalf("decision body = %v", f.bodies["POST /v1/claims/clm-1/decision"])
	}
	do("admin", "POST", "/v1/staff/resources/purchase-orders/po-1/actions/approve", map[string]any{"values": map[string]any{"note": "ok"}}, 200)
	do("admin", "POST", "/v1/staff/resources/orders/AT-000001/actions/shipment-status", map[string]any{"values": map[string]any{"step": "shp-1|CANCELLED", "reason": "Customer asked"}}, 200)
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
	// Help center pages: one Markdown text in the form, sections at "## " headings, the version read last.
	help := do("admin", "GET", "/v1/staff/resources/help-articles/shipping", nil, 200)
	if help["title"] != "Shipping & delivery" {
		t.Fatalf("help sheet = %v", help)
	}
	page := "## Carriers\n\nUSPS, UPS and FedEx.\n\n## Pickup\n\n- Lockers\n- Will call"
	helpValues := map[string]any{"title": "Shipping & delivery", "summary": "Carriers", "topic": "ORDERS_DELIVERY", "status": "PUBLISHED", "sortOrder": 10, "sections": page}
	do("admin", "PATCH", "/v1/staff/resources/help-articles/shipping", map[string]any{"values": helpValues}, 200)
	if b := f.bodies["PUT /v1/help/articles/shipping"]; b["expectedVersion"] != float64(4) || len(b["sections"].([]any)) != 2 ||
		b["sections"].([]any)[1].(map[string]any)["body"] != "- Lockers\n- Will call" {
		t.Fatalf("help body = %v", b)
	}
	badHelp := map[string]any{}
	for k, v := range helpValues {
		badHelp[k] = v
	}
	badHelp["sections"] = "No heading here"
	do("admin", "PATCH", "/v1/staff/resources/help-articles/shipping", map[string]any{"values": badHelp}, 422)
	newHelp := map[string]any{"slug": "track-a-package"}
	for k, v := range helpValues {
		newHelp[k] = v
	}
	do("admin", "POST", "/v1/staff/resources/help-articles", map[string]any{"values": newHelp}, 201)
	if _, ok := f.bodies["PUT /v1/help/articles/track-a-package"]["expectedVersion"]; ok {
		t.Fatal("a new help page is created without expectedVersion")
	}
	newHelp["slug"] = "shipping"
	do("admin", "POST", "/v1/staff/resources/help-articles", map[string]any{"values": newHelp}, 422)
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

	// Fees & commissions: the fee-schedule editor.
	feesAt := func(m map[string]any, keys ...string) any {
		var cur any = m
		for _, k := range keys {
			if i, err := strconv.Atoi(k); err == nil {
				cur = cur.([]any)[i]
			} else {
				cur = cur.(map[string]any)[k]
			}
		}
		return cur
	}
	fees := do("admin", "GET", "/v1/staff/fees", nil, 200)
	if fees["canConfigure"] != true || feesAt(fees, "current", "version") != float64(1) || feesAt(fees, "base", "version") != float64(2) || fees["immediateAllowed"] != false {
		t.Fatalf("fees overview = %v", fees)
	}
	statuses := []string{}
	for _, v := range fees["schedules"].([]any) {
		statuses = append(statuses, v.(map[string]any)["status"].(string))
	}
	if strings.Join(statuses, ",") != "CANCELLED,SCHEDULED,ACTIVE" {
		t.Fatalf("statuses = %v", statuses)
	}
	if feesAt(fees, "base", "overrides", "0", "orgName") != "Kente Naturals" || feesAt(fees, "base", "overrides", "0", "category") != nil ||
		feesAt(fees, "schedules", "1", "cancellable") != true || feesAt(fees, "schedules", "2", "cancellable") != false {
		t.Fatalf("base = %v", fees["base"])
	}
	cats := map[string]string{}
	for _, c := range fees["categories"].([]any) {
		cats[c.(map[string]any)["key"].(string)] = c.(map[string]any)["source"].(string)
	}
	if cats["groceries"] != "TAXONOMY" || cats["home & art"] != "TAXONOMY" || cats["beauty"] != "SCHEDULE" || cats["services"] != "PLATFORM" || cats["rice"] != "" {
		t.Fatalf("categories = %v", cats)
	}
	if feesAt(fees, "retail", "note") != domain.RetailFeesNote || feesAt(fees, "retail", "orgId") != "org-retail" {
		t.Fatalf("retail = %v", fees["retail"])
	}
	if ro := do("auditor", "GET", "/v1/staff/fees", nil, 200); ro["canConfigure"] != false || feesAt(ro, "schedules", "1", "cancellable") != false {
		t.Fatalf("auditor overview = %v", ro)
	}
	do("support", "GET", "/v1/staff/fees", nil, 403)
	do("admin", "GET", "/v1/staff/fees/orgs?q=k", nil, 422)
	if hits := do("admin", "GET", "/v1/staff/fees/orgs?q=kente", nil, 200)["items"].([]any); len(hits) != 1 || hits[0].(map[string]any)["orgId"] != "org-1" {
		t.Fatalf("org hits = %v", hits)
	}
	if hits := do("admin", "GET", "/v1/staff/fees/orgs?q=retail", nil, 200)["items"].([]any); len(hits) != 1 || hits[0].(map[string]any)["selectable"] != false {
		t.Fatalf("retail hits = %v", hits)
	}
	scheduledAt := time.Now().UTC().Add(11 * 24 * time.Hour).Format(time.RFC3339)
	draft := func() map[string]any {
		return map[string]any{"effectiveFrom": scheduledAt, "defaultCommissionBps": 1000, "cardPctBps": 290, "cardFixedMinor": 30, "fbaPerUnitMinor": 325,
			"reverseCommissionOnRefund": true, "note": "Fashion up, Kente promo",
			"categoryRates": []any{map[string]any{"category": "Groceries", "commissionBps": 700}, map[string]any{"category": "fashion", "commissionBps": 1250}},
			"overrides":     []any{map[string]any{"orgId": "org-1", "commissionBps": 600}, map[string]any{"orgId": "org-2", "category": "groceries", "commissionBps": 500}}}
	}
	bad := draft()
	bad["effectiveFrom"] = time.Now().UTC().Add(-time.Hour).Format(time.RFC3339)
	bad["defaultCommissionBps"] = 6000
	bad["note"] = ""
	bad["categoryRates"] = []any{map[string]any{"category": "fashion", "commissionBps": 1000}, map[string]any{"category": " Fashion ", "commissionBps": 1000}}
	bad["overrides"] = []any{map[string]any{"orgId": "org-retail", "commissionBps": 0}}
	errs := do("admin", "POST", "/v1/staff/fees/schedules", bad, 422)["errors"].(map[string]any)
	for _, k := range []string{"effectiveFrom", "defaultCommissionBps", "note", "categoryRates.1.category", "overrides.0.orgId"} {
		if errs[k] == nil {
			t.Fatalf("missing error %s: %v", k, errs)
		}
	}
	early := draft()
	early["effectiveFrom"] = time.Now().UTC().Add(24 * time.Hour).Format(time.RFC3339)
	if e := do("admin", "POST", "/v1/staff/fees/schedules", early, 422)["errors"].(map[string]any); e["effectiveFrom"] == nil {
		t.Fatalf("before the scheduled version: %v", e)
	}
	now := draft()
	delete(now, "effectiveFrom")
	now["immediate"] = true
	if e := do("admin", "POST", "/v1/staff/fees/schedules", now, 422)["errors"].(map[string]any); e["immediate"] == nil {
		t.Fatalf("immediate while scheduled: %v", e)
	}
	do("auditor", "POST", "/v1/staff/fees/schedules", draft(), 403)
	created := do("admin", "POST", "/v1/staff/fees/schedules", draft(), 201, "Idempotency-Key", "fee-draft-0001")
	if created["status"] != "SCHEDULED" || created["version"] != float64(4) || len(created["categoryRates"].([]any)) != 2 || len(created["overrides"].([]any)) != 2 {
		t.Fatalf("created = %v", created)
	}
	sent := f.bodies["POST /v1/fee-schedules"]
	if feesAt(sent, "categoryBps", "groceries") != float64(700) || feesAt(sent, "categoryBps", "fashion") != float64(1250) || sent["effectiveFrom"] != scheduledAt ||
		feesAt(sent, "overrides", "1", "category") != "groceries" || feesAt(sent, "overrides", "0", "category") != nil || sent["note"] != "Fashion up, Kente promo" {
		t.Fatalf("payments body = %v", sent)
	}
	do("admin", "POST", "/v1/staff/fees/schedules/fee-2/cancel", map[string]any{"reason": "x"}, 422)
	do("auditor", "POST", "/v1/staff/fees/schedules/fee-2/cancel", map[string]any{"reason": "Promo postponed"}, 403)
	if c := do("admin", "POST", "/v1/staff/fees/schedules/fee-2/cancel", map[string]any{"reason": "Promo postponed"}, 200); c["status"] != "CANCELLED" || c["cancelReason"] != "Promo postponed" {
		t.Fatalf("cancelled = %v", c)
	}
	do("admin", "POST", "/v1/staff/fees/schedules/fee-2/cancel", map[string]any{"reason": "Again"}, 409)
	do("admin", "POST", "/v1/staff/fees/schedules/fee-1/cancel", map[string]any{"reason": "Too late"}, 409)
	do("admin", "POST", "/v1/staff/fees/schedules/fee-404/cancel", map[string]any{"reason": "Unknown"}, 404)
	if !f.called("POST /v1/fee-schedules/fee-2/cancel") || f.called("POST /v1/fee-schedules/fee-1/cancel") {
		t.Fatal("cancel calls")
	}

	// AfroTransact Delivery pricing (S255): shown per version, validated, sent to payments; omitted = carried over.
	if dp := feesAt(fees, "current", "deliveryPricing").(map[string]any); dp["enabled"] != true || feesAt(dp, "sellerOverrides", "0", "orgName") != "Kente Naturals" ||
		len(feesAt(dp, "customerCharge", "bands").([]any)) != 0 || feesAt(dp, "customerCharge", "flatPerPackageMinor") != nil {
		t.Fatalf("delivery pricing = %v", dp)
	}
	if feesAt(fees, "base", "deliveryPricing") != nil || feesAt(fees, "limits", "deliveryMaxRadiusMiles") != float64(200) {
		t.Fatalf("base delivery pricing = %v", feesAt(fees, "base", "deliveryPricing"))
	}
	delivery := func() map[string]any {
		return map[string]any{"enabled": true, "maxRadiusMiles": 30,
			"customerCharge": map[string]any{"mode": "DISTANCE", "flatPerPackageMinor": nil, "baseMinor": 499, "perMileMinor": 85, "minMinor": 599, "maxMinor": nil,
				"bands": []any{map[string]any{"fromMiles": 0, "toMiles": 3, "feeMinor": 499}}},
			"sellerFee":       map[string]any{"mode": "FLAT", "flatPerPackageMinor": 200, "baseMinor": nil, "perMileMinor": nil, "minMinor": nil, "maxMinor": nil, "bands": []any{}},
			"sellerOverrides": []any{map[string]any{"orgId": "org-2", "sellerFee": map[string]any{"mode": "FLAT", "flatPerPackageMinor": 100, "bands": []any{}}}}}
	}
	withDelivery := draft()
	withDelivery["effectiveFrom"] = time.Now().UTC().Add(12 * 24 * time.Hour).Format(time.RFC3339)
	badDelivery := delivery()
	badDelivery["maxRadiusMiles"] = 250
	badDelivery["customerCharge"].(map[string]any)["bands"] = []any{map[string]any{"fromMiles": 0, "toMiles": 5, "feeMinor": 499}, map[string]any{"fromMiles": 4, "toMiles": 8, "feeMinor": 699}}
	badDelivery["customerCharge"].(map[string]any)["maxMinor"] = 100
	badDelivery["sellerOverrides"] = []any{map[string]any{"orgId": "org-retail", "sellerFee": map[string]any{"mode": "FLAT", "flatPerPackageMinor": 0, "bands": []any{}}}}
	withDelivery["deliveryPricing"] = badDelivery
	e := do("admin", "POST", "/v1/staff/fees/schedules", withDelivery, 422)["errors"].(map[string]any)
	for _, k := range []string{"deliveryPricing.maxRadiusMiles", "deliveryPricing.customerCharge.bands.1.fromMiles", "deliveryPricing.customerCharge.maxMinor", "deliveryPricing.sellerOverrides.0.orgId"} {
		if e[k] == nil {
			t.Fatalf("missing delivery error %s: %v", k, e)
		}
	}
	withDelivery["deliveryPricing"] = delivery()
	if c := do("admin", "POST", "/v1/staff/fees/schedules", withDelivery, 201, "Idempotency-Key", "fee-draft-0002"); feesAt(c, "deliveryPricing", "maxRadiusMiles") != float64(30) {
		t.Fatalf("created with delivery = %v", c)
	}
	sentDP := f.bodies["POST /v1/fee-schedules"]["deliveryPricing"].(map[string]any)
	if _, has := sentDP["customerCharge"].(map[string]any)["maxMinor"]; has || feesAt(sentDP, "sellerFee", "flatPerPackageMinor") != float64(200) ||
		feesAt(sentDP, "customerCharge", "bands", "0", "feeMinor") != float64(499) {
		t.Fatalf("payments delivery body = %v", sentDP)
	}
	if _, has := sent["deliveryPricing"]; has {
		t.Fatal("a draft without the section must not send it (payments carries it over)")
	}
	q := do("admin", "POST", "/v1/staff/fees/delivery-quote", map[string]any{"sellerOrgId": "org-1", "distanceMiles": 7.4, "packages": 1}, 200)
	if q["available"] != true || q["customerChargeMinor"] != float64(1128) || q["sellerFeeSource"] != "DEFAULT" {
		t.Fatalf("quote = %v", q)
	}
	if q := do("auditor", "POST", "/v1/staff/fees/delivery-quote", map[string]any{"sellerOrgId": "org-1", "distanceMiles": 31}, 200); q["unavailableReason"] != "BEYOND_MAX_RADIUS" || q["sellerFeeSource"] != nil {
		t.Fatalf("far quote = %v", q)
	}
	do("admin", "POST", "/v1/staff/fees/delivery-quote", map[string]any{"sellerOrgId": "", "distanceMiles": -1}, 422)

	// Dispatch: seller pickups, nearest drivers, overrides with a reason, history.
	pk := do("admin", "GET", "/v1/staff/dispatch/pickups", nil, 200)
	if items := pk["items"].([]any); len(items) != 1 || items[0].(map[string]any)["shipmentId"] != "shp-9" || pk["canAssign"] != true {
		t.Fatalf("pickups = %v", pk)
	}
	if s := do("support", "GET", "/v1/staff/dispatch/pickups", nil, 200); s["canAssign"] != false {
		t.Fatalf("support may view, not assign: %v", s)
	}
	sg := do("admin", "GET", "/v1/staff/dispatch/pickups/shp-9/driver-suggestions", nil, 200)
	if d := sg["drivers"].([]any); len(d) != 2 || d[1].(map[string]any)["roadMiles"] != nil || d[0].(map[string]any)["roadMiles"] != 2.9 {
		t.Fatalf("suggestions = %v", sg)
	}
	do("admin", "GET", "/v1/staff/dispatch/pickups/nope/driver-suggestions", nil, 404)
	do("admin", "POST", "/v1/staff/dispatch/pickups/shp-9/assign", map[string]any{"routeId": "rte-2", "reason": "ok"}, 422)
	do("support", "POST", "/v1/staff/dispatch/pickups/shp-9/assign", map[string]any{"routeId": "rte-2", "reason": "Driver 1 has a flat tyre"}, 403)
	if a := do("admin", "POST", "/v1/staff/dispatch/pickups/shp-9/assign", map[string]any{"routeId": "rte-2", "reason": "Driver 1 has a flat tyre"}, 200); a["routeId"] != "rte-2" || a["driverId"] != "drv-2" {
		t.Fatalf("assign = %v", a)
	}
	if b := f.bodies["POST /v1/dispatch/pickups/shp-9/assign"]; b["reason"] != "Driver 1 has a flat tyre" {
		t.Fatalf("assign body = %v", b)
	}
	do("admin", "POST", "/v1/staff/dispatch/pickups/shp-9/assign", map[string]any{"routeId": "rte-done", "reason": "Too late to move"}, 409)

	// Dispatch board (S281): SOS acknowledged, defect resolved with its route, routes built from the waiting pool.
	do("admin", "POST", "/v1/staff/resources/sos-alerts/sos-1/actions/acknowledge", nil, 200)
	do("admin", "POST", "/v1/staff/resources/vehicle-defects/def-1/actions/resolve", map[string]any{"values": map[string]any{"note": "Brakes bled"}}, 200)
	if b := f.bodies["POST /v1/dispatch/defects/def-1/resolve"]; b["routeId"] != "rte-1" || b["note"] != "Brakes bled" {
		t.Fatalf("resolve body = %v", b)
	}
	do("admin", "POST", "/v1/staff/resources/routes", map[string]any{"values": map[string]any{"depotNodeId": "HOU-FC", "serviceDate": "2026-10-12", "maxDropoffs": 20}}, 201)
	if b := f.bodies["POST /v1/dispatch/routes/build"]; b["maxDropoffs"] != float64(20) || b["depotNodeId"] != "HOU-FC" {
		t.Fatalf("build body = %v", b)
	}
	do("admin", "POST", "/v1/staff/resources/routes", map[string]any{"values": map[string]any{"depotNodeId": "EMPTY-FC", "serviceDate": "2026-10-12"}}, 409)
	if h := do("admin", "GET", "/v1/staff/dispatch/pickups/shp-9/assignments", nil, 200)["items"].([]any); len(h) != 2 || h[0].(map[string]any)["mode"] != "DISPATCH_OVERRIDE" {
		t.Fatalf("history = %v", h)
	}
	if l := do("admin", "GET", "/v1/staff/ship-from-locations/loc-1", nil, 200); l["pickupEnabled"] != true || l["position"].(map[string]any)["precision"] != "ADDRESS" {
		t.Fatalf("location = %v", l)
	}

	// Notifications → Templates (S268): list, detail with sample, previews, new versions, test-send; edits need templates:write.
	tl := do("admin", "GET", "/v1/staff/notifications/templates", nil, 200)
	if items := tl["items"].([]any); len(items) != 2 || tl["canEdit"] != true || items[1].(map[string]any)["key"] != "order.confirmed" ||
		items[1].(map[string]any)["latestVersion"] != float64(2) || items[1].(map[string]any)["updatedBy"] != "stf-1" ||
		fmt.Sprint(items[1].(map[string]any)["channels"]) != "[email sms]" {
		t.Fatalf("templates = %v", tl)
	}
	if q := do("support", "GET", "/v1/staff/notifications/templates?q=SECURITY", nil, 200); len(q["items"].([]any)) != 1 || q["canEdit"] != false {
		t.Fatalf("search, read-only = %v", q)
	}
	do("auditor", "GET", "/v1/staff/notifications/templates", nil, 403)
	td := do("admin", "GET", "/v1/staff/notifications/templates/order.confirmed", nil, 200)
	vars := td["variants"].([]any)
	if len(vars) != 2 || vars[0].(map[string]any)["channel"] != "email" || len(vars[0].(map[string]any)["versions"].([]any)) != 2 ||
		vars[0].(map[string]any)["versions"].([]any)[0].(map[string]any)["version"] != float64(2) || td["sample"].(map[string]any)["source"] != "event" ||
		td["canTestSend"] != true {
		t.Fatalf("template = %v", td)
	}
	do("admin", "GET", "/v1/staff/notifications/templates/no.such", nil, 404)
	pv := do("support", "POST", "/v1/staff/notifications/templates/order.confirmed/preview", map[string]any{"channel": "sms", "data": map[string]any{"orderNumber": "AT-1"}}, 200)
	if pv["version"] != float64(2) || pv["sms"].(map[string]any)["segments"] != float64(1) || pv["sms"].(map[string]any)["encoding"] != "GSM-7" {
		t.Fatalf("preview = %v", pv)
	}
	if pe := do("support", "POST", "/v1/staff/notifications/templates/order.confirmed/preview", map[string]any{"channel": "email",
		"draft": map[string]any{"subject": "S", "bodyHtml": "<p>x</p>", "bodyText": "Hi {{.missing}}"}}, 422); pe["errors"].(map[string]any)["draft.bodyText"] != "Not in the sample data: missing" {
		t.Fatalf("draft render error = %v", pe)
	}
	if pe := do("support", "POST", "/v1/staff/notifications/templates/order.confirmed/preview", map[string]any{"channel": "fax"}, 422); pe["errors"].(map[string]any)["channel"] == nil {
		t.Fatalf("channel = %v", pe)
	}
	if pd := do("admin", "POST", "/v1/staff/notifications/templates/order.confirmed/preview", map[string]any{"channel": "sms",
		"draft": map[string]any{"bodyText": "Order · confirmed"}}, 200); pd["version"] != float64(0) || pd["sms"].(map[string]any)["encoding"] != "UCS-2" {
		t.Fatalf("draft preview = %v", pd)
	}
	tplDraft := map[string]any{"locale": "en", "channel": "email", "category": "transactional", "subject": "Order {{.orderNumber}} is in",
		"bodyHtml": "<p>{{.firstName}}, {{.total}}</p>", "bodyText": "{{.firstName}}: {{.total}}"}
	do("support", "POST", "/v1/staff/notifications/templates/order.confirmed/versions", tplDraft, 403)
	tplBad := map[string]any{"locale": "en", "channel": "email", "category": "transactional", "subject": "Hi {{.nickname}}", "bodyHtml": "<p>x</p>",
		"bodyText": "{{range .lines}}{{.title}}{{end}} {{.couponCode}}"}
	if e := do("admin", "POST", "/v1/staff/notifications/templates/order.confirmed/versions", tplBad, 422)["errors"].(map[string]any); e["subject"] != "Not in the sample data: nickname" ||
		e["bodyText"] != "Not in the sample data: couponCode, lines" {
		t.Fatalf("placeholder check = %v", e)
	}
	if e := do("admin", "POST", "/v1/staff/notifications/templates/order.confirmed/versions", map[string]any{"locale": "en", "channel": "sms",
		"category": "transactional", "bodyText": strings.Repeat("Order confirmed. ", 30)}, 422)["errors"].(map[string]any); !strings.Contains(fmt.Sprint(e["bodyText"]), "4 segments") {
		t.Fatalf("sms segments = %v", e)
	}
	if e := do("admin", "POST", "/v1/staff/notifications/templates/order.confirmed/versions", map[string]any{"locale": "en", "channel": "email",
		"category": "transactional", "bodyText": "x"}, 422)["errors"].(map[string]any); e["subject"] == nil || e["bodyHtml"] == nil {
		t.Fatalf("email needs subject and html = %v", e)
	}
	tplCreated := do("admin", "POST", "/v1/staff/notifications/templates/order.confirmed/versions", tplDraft, 201)
	if tplCreated["version"] != float64(3) || tplCreated["channel"] != "email" || tplCreated["createdBy"] != "stf-1" {
		t.Fatalf("created = %v", tplCreated)
	}
	if b := f.bodies["POST /v1/templates"]; b["key"] != "order.confirmed" || b["subject"] != "Order {{.orderNumber}} is in" {
		t.Fatalf("create body = %v", b)
	}
	if b := f.bodies["POST /v1/templates/order.confirmed/preview"]; b["draft"] == nil || b["data"].(map[string]any)["firstName"] != "Ada" {
		t.Fatalf("create renders the draft with the sample data first: %v", b)
	}
	ts := do("admin", "POST", "/v1/staff/notifications/templates/order.confirmed/test-send", map[string]any{"channel": "email", "data": map[string]any{"firstName": "Ada"}}, 200)
	if ts["to"] != "ifeoma@afrotransact.com" || ts["provider"] != "smtp" {
		t.Fatalf("test-send = %v", ts)
	}
	do("support", "POST", "/v1/staff/notifications/templates/order.confirmed/test-send", map[string]any{"channel": "email"}, 403)
	if e := do("admin", "POST", "/v1/staff/notifications/templates/order.confirmed/test-send", map[string]any{"channel": "push"}, 422)["errors"].(map[string]any); e["channel"] == nil {
		t.Fatalf("push test-send = %v", e)
	}
	if e := do("admin", "POST", "/v1/staff/notifications/templates/order.confirmed/test-send", map[string]any{"channel": "sms"}, 422); e["title"] != "No address to send to" {
		t.Fatalf("no own phone = %v", e)
	}

	for _, op := range []string{"listNotificationTemplates", "getNotificationTemplate", "previewNotificationTemplate", "createNotificationTemplateVersion",
		"testSendNotificationTemplate", "getStaffSession", "getStaffDashboard", "listStaffResource", "createStaffRecord", "getStaffRecord", "updateStaffRecord", "runStaffAction",
		"addStaffNote", "listStaffExports", "createStaffExport", "getStaffExport", "downloadStaffExport", "getStaffExportFile", "searchSupportCustomers",
		"getSupportCustomer", "supportRefund", "supportResendNotification", "supportCancelShipment", "getStaffFees", "searchFeeOrgs", "createFeeSchedule",
		"cancelFeeSchedule", "quoteStaffDelivery", "listStaffPickups", "getPickupDriverSuggestions", "assignPickup", "listPickupAssignments",
		"getStaffShipFromLocation"} {
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
	for perms, want := range map[string]string{"": domain.LevelNone, "notifications:read": domain.LevelView,
		"notifications:read notifications:templates:write": domain.LevelEdit} {
		if got := (domain.StaffAccess{Permissions: strings.Fields(perms)}).Level("notifications"); got != want {
			t.Errorf("notifications with %q = %s, want %s", perms, got, want)
		}
	}
	if l := domain.RefundLimit([]string{"platform.customer_support", "platform.finance"}); l != nil {
		t.Fatalf("finance has no refund limit, got %v", *l)
	}
	if l := domain.RefundLimit([]string{"platform.marketing"}); l == nil || *l != 0 {
		t.Fatal("marketing may not refund")
	}
}
