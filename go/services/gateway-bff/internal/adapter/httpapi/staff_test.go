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
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/adapter/memcache"
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

var adminPerms = []string{"access:admin", "access:read", "applications:approve", "applications:review", "audit:read", "catalog:moderate", "identity:admin", "content:legal.edit",
	"content:markets.edit", "content:placements.approve", "content:placements.edit", "content:site.edit", "delivery:dispatch", "disputes:manage",
	"inventory:adjust", "inventory:read", "notifications:manage", "notifications:read", "notifications:templates:write", "orders:manage", "orders:read", "ledger:adjust", "ledger:approve", "payments:configure", "payments:read", "payouts:approve", "payouts:read",
	"refunds:issue", "reports:read", "retail:po:approve", "retail:po:write", "returns:manage", "support:respond", "tax:configure", "users:read"}

type fakePlatform struct {
	mu        sync.Mutex
	calls     []string
	bodies    map[string]map[string]any
	payoutSt  string
	bigOrders bool
	fees      []map[string]any
	// idAdmin is identity's admin console API (S291), see staff_identity_test.go.
	idAdmin fakeIdentityAdmin
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

// giftCardFake is payments' staff view of a gift card.
func giftCardFake(now string) map[string]any {
	return map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000c1", "status": "ACTIVE", "origin": "PURCHASE", "amountMinor": 5000, "currency": "USD", "codeLast4": "K3M9",
		"purchaserId": "cus-1", "senderName": "Kofi", "recipientEmail": "ama@example.com", "recipientName": "Ama", "message": "Happy birthday", "locale": "en", "sendAt": now,
		"deliveryStatus": "SENT", "deliveryCount": 1, "sentAt": now, "refundedToCard": false, "createdAt": now}
}

func membershipTierFake(now string) map[string]any {
	return map[string]any{"code": "plus", "name": "Plus", "description": "Free delivery and cashback", "status": "PUBLISHED", "sortOrder": 10, "currency": "USD",
		"monthlyPriceMinor": 999, "yearlyPriceMinor": 9900, "trialDays": 14, "updatedAt": now, "updatedBy": "stf-1", "version": 4,
		"benefits": []any{map[string]any{"code": "FREE_DELIVERY", "minOrderMinor": 2500}, map[string]any{"code": "FREE_SHIPPING", "minOrderMinor": 2500, "capMinor": 1500},
			map[string]any{"code": "CASHBACK", "percentBps": 200, "minOrderMinor": 0}, map[string]any{"code": "PRIORITY_SUPPORT"}}}
}

func membershipFake(now string) map[string]any {
	return map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000c9", "customerId": "cus-1", "tierCode": "plus", "interval": "MONTHLY", "source": "PAID", "status": "ACTIVE",
		"priceMinor": 999, "currency": "USD", "startedAt": now, "periodStart": now, "periodEnd": now, "cancelAtPeriodEnd": false, "retryCount": 0, "freeDaysGranted": 0}
}

func moneySettingsFake(now string) map[string]any {
	return map[string]any{"giftCardMinMinor": 1000, "giftCardMaxMinor": 50000, "giftCardPresets": []any{2500, 5000, 10000}, "giftCardTerms": map[string]any{"en": "Gift cards never expire."},
		"creditApprovalLimitMinor": 10000, "updatedBy": "stf-1", "updatedAt": now, "version": 3}
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
	case strings.HasPrefix(p, "/v1/admin/identity/"):
		f.idAdmin.serve(w, r, p, body)
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
	// seller: the organisation directory mirror and Stripe Connect compliance
	case p == "/v1/orgs/mirror":
		writeJ(w, 200, map[string]any{"items": []any{map[string]any{"orgId": "org-1", "displayName": "Kente Naturals", "legalName": "Kente Naturals LLC", "kind": "SELLER", "offering": "PRODUCTS", "country": "US", "status": "APPROVED"}}, "total": 1})
	case strings.HasPrefix(p, "/v1/orgs/") && strings.HasSuffix(p, "/mirror"):
		writeJ(w, 200, map[string]any{"orgId": "org-1", "displayName": "Kente Naturals", "legalName": "Kente Naturals LLC", "kind": "SELLER", "offering": "PRODUCTS", "country": "US", "status": "APPROVED"})
	case p == "/v1/compliance/accounts":
		writeJ(w, 200, []any{map[string]any{"orgId": "org-1", "orgName": "Kente Naturals", "accountId": "acct_1Kente", "status": "VERIFIED", "payoutsPaused": false, "listingsHidden": false}})
	case strings.HasPrefix(p, "/v1/compliance/accounts/"):
		writeJ(w, 200, map[string]any{"orgId": "org-1", "orgName": "Kente Naturals", "accountId": "acct_1Kente", "status": "VERIFIED", "payoutsPaused": false, "listingsHidden": false})
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
	case p == "/v1/tax/codes":
		writeJ(w, 200, []any{map[string]any{"code": "GENERAL_GOODS", "label": "General taxable goods", "category": "GENERAL", "providerCode": "txcd_99999999", "active": true, "taxable": true, "exemptStates": ""},
			map[string]any{"code": "NON_TAXABLE", "label": "Not taxable", "category": "GENERAL", "providerCode": nil, "active": true, "taxable": false, "exemptStates": ""},
			map[string]any{"code": "FRESH_PRODUCE", "label": "Fresh produce", "category": "GROCERY", "providerCode": nil, "active": true, "taxable": true, "exemptStates": "NJ,TX"}})
	case p == "/v1/tax/classification-rules":
		writeJ(w, 200, []any{map[string]any{"id": 1, "productType": "dress", "attrKey": nil, "attrValue": nil, "taxCode": "CLOTHING", "note": "Clothing"},
			map[string]any{"id": 2, "productType": "grocery", "attrKey": "tax_class", "attrValue": "Hot or ready-to-eat", "taxCode": "PREPARED_FOOD", "note": "Prepared food"}})
	case p == "/v1/tax/review-queue":
		writeJ(w, 200, []any{map[string]any{"productType": "grocery", "attributes": map[string]any{}, "attrHash": "abc123", "taxCode": "GENERAL_GOODS", "basis": "DEFAULT",
			"sampleAtin": "AT1", "count": 4, "firstSeen": "2026-10-20T10:00:00Z", "lastSeen": "2026-10-21T10:00:00Z", "reviewedAt": nil, "reviewedBy": nil, "reviewNote": nil}})
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
	case p == "/v1/reconciliation/items":
		writeJ(w, 200, []any{map[string]any{"id": "rec-1", "runId": "run-1", "windowStart": now, "stripeRef": "ch_123456", "currency": "USD", "ledgerMinor": 10000, "stripeMinor": 9750,
			"differenceMinor": 250, "status": "OPEN", "createdAt": now}})
	case strings.HasPrefix(p, "/v1/reconciliation/items/"):
		writeJ(w, 200, map[string]any{"item": map[string]any{"id": "rec-1", "runId": "run-1", "windowStart": now, "stripeRef": "ch_123456", "currency": "USD", "ledgerMinor": 10000,
			"stripeMinor": 9750, "differenceMinor": 250, "status": "OPEN", "createdAt": now},
			"stripe":      map[string]any{"ref": "ch_123456", "object": map[string]any{"id": "ch_123456", "kind": "Charge", "status": "succeeded", "amountMinor": 10000, "links": map[string]any{"paymentIntent": "pi_123456"}}, "journals": []any{}},
			"journals":    []any{map[string]any{"id": "j-1", "type": "CAPTURE", "description": "Capture of order 1", "occurredAt": now, "lines": []any{map[string]any{"account": "1000", "side": "DEBIT", "amountMinor": 10000}}}},
			"adjustments": []any{}})
	case p == "/v1/reconciliation/runs":
		writeJ(w, 200, []any{map[string]any{"id": "run-1", "windowStart": now, "windowEnd": now, "currency": "USD", "ledgerNetMinor": 10000, "stripeNetMinor": 9750, "driftMinor": 250,
			"drifts": []any{map[string]any{"stripeRef": "ch_123456", "ledgerMinor": 10000, "stripeMinor": 9750}}, "createdAt": now}})
	case p == "/v1/ledger/adjustments":
		writeJ(w, 200, []any{map[string]any{"id": "adj-1", "stripeRef": "ch_123456", "currency": "USD", "reason": "Stripe fee booked twice for the charge", "status": "PROPOSED",
			"proposedBy": "u-1", "proposedAt": now, "lines": []any{map[string]any{"account": "1000", "title": "Stripe clearing", "side": "CREDIT", "amountMinor": 250}, map[string]any{"account": "5000", "title": "Stripe processing fees", "side": "DEBIT", "amountMinor": 250}}}})
	case strings.HasPrefix(p, "/v1/ledger/adjustments/"):
		writeJ(w, 200, map[string]any{"id": "adj-1", "stripeRef": "ch_123456", "currency": "USD", "reason": "Stripe fee booked twice for the charge", "status": "PROPOSED",
			"proposedBy": "u-1", "proposedAt": now, "lines": []any{map[string]any{"account": "1000", "title": "Stripe clearing", "side": "CREDIT", "amountMinor": 250}, map[string]any{"account": "5000", "title": "Stripe processing fees", "side": "DEBIT", "amountMinor": 250}}})
	// payments: AfroTransact credit, gift cards and their limits (S320, S321)
	case p == "/v1/credit/adjustments" && r.Method == http.MethodGet:
		writeJ(w, 200, []any{map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000a1", "customerId": "cus-1", "amountMinor": 25000, "currency": "USD", "source": "STAFF",
			"reason": "Goodwill for a late order", "status": "PROPOSED", "proposedBy": "stf-2", "proposedAt": now},
			map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000a2", "customerId": "cus-1", "amountMinor": -1250, "currency": "USD", "source": "STAFF",
				"reason": "Credited twice by mistake", "status": "POSTED", "proposedBy": "stf-1", "proposedAt": now, "decidedBy": "stf-1", "decidedAt": now}})
	case p == "/v1/credit/adjustments" && r.Method == http.MethodPost:
		writeJ(w, 201, map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000a2", "customerId": "cus-1", "amountMinor": body["amountMinor"], "currency": "USD", "source": body["source"],
			"reason": body["reason"], "status": "PROPOSED", "proposedBy": "stf-1", "proposedAt": now})
	case strings.HasPrefix(p, "/v1/credit/adjustments/"):
		writeJ(w, 200, map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000a1", "customerId": "cus-1", "amountMinor": 25000, "currency": "USD", "source": "STAFF",
			"reason": "Goodwill for a late order", "status": "POSTED", "proposedBy": "stf-2", "proposedAt": now, "decidedBy": "stf-1", "decidedAt": now})
	case strings.HasPrefix(p, "/v1/credit/customers/"):
		writeJ(w, 200, map[string]any{"balance": map[string]any{"customerId": "cus-1", "balanceMinor": 2500, "currency": "USD"}, "adjustments": []any{},
			"entries": []any{map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000e1", "customerId": "cus-1", "kind": "GRANT", "amountMinor": 2500, "currency": "USD", "source": "REFERRAL",
				"reason": "Referral reward", "actor": "service", "balanceAfterMinor": 2500, "createdAt": now}}})
	case p == "/v1/credit/liabilities":
		writeJ(w, 200, map[string]any{"currency": "USD", "creditOutstandingMinor": 12500, "accountsWithCredit": 4, "flowsMinor": map[string]any{"GRANT/REFERRAL": 20000, "SPEND/ORDER": -7500},
			"giftCardsOutstandingMinor": 10000, "giftCardsOutstandingCount": 2, "giftCardsRedeemedMinor": 5000, "giftCardsVoidedMinor": 2500, "ledgerCreditMinor": 15000,
			"ledgerGiftCardsMinor": 10000, "creditOnOpenOrdersMinor": 2500})
	case p == "/v1/gift-cards" && r.Method == http.MethodGet:
		writeJ(w, 200, []any{giftCardFake(now)})
	case p == "/v1/gift-cards" && r.Method == http.MethodPost:
		card := giftCardFake(now)
		card["origin"], card["amountMinor"], card["recipientEmail"], card["issueReason"] = "STAFF", body["amountMinor"], body["recipientEmail"], body["reason"]
		writeJ(w, 201, card)
	case strings.HasPrefix(p, "/v1/gift-cards/"):
		card := giftCardFake(now)
		if strings.HasSuffix(p, "/void") {
			card["status"], card["voidReason"] = "VOID", body["reason"]
		}
		writeJ(w, 200, card)
	case p == "/v1/money/settings" && r.Method == http.MethodGet:
		writeJ(w, 200, moneySettingsFake(now))
	case p == "/v1/money/settings" && r.Method == http.MethodPut:
		writeJ(w, 200, moneySettingsFake(now))
	case p == "/v1/money/settings/history":
		writeJ(w, 200, []any{map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000b1", "changedBy": "stf-1", "changedAt": now, "reason": "Raise the gift card ceiling for the holidays",
			"before": "{}", "after": "{}"}})
	// payments: membership tiers, billing rules, members and the report (S323)
	case p == "/v1/memberships/admin/tiers" && r.Method == http.MethodGet:
		premium := membershipTierFake(now)
		premium["code"], premium["name"], premium["status"], premium["sortOrder"] = "premium", "Premium", "DRAFT", 20
		writeJ(w, 200, []any{map[string]any{"tier": membershipTierFake(now), "members": 12, "trialing": 3}, map[string]any{"tier": premium, "members": 0, "trialing": 0}})
	case p == "/v1/memberships/admin/tiers" && r.Method == http.MethodPost:
		t := membershipTierFake(now)
		t["code"], t["name"], t["status"] = body["code"], body["name"], body["status"]
		writeJ(w, 201, t)
	case strings.HasSuffix(p, "/changes") && strings.HasPrefix(p, "/v1/memberships/admin/tiers/"):
		writeJ(w, 200, []any{map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000d1", "tierCode": "plus", "action": "PUBLISHED", "changedBy": "stf-1", "changedAt": now,
			"reason": "Open Plus to every shopper", "beforeJson": "{}", "afterJson": "{}"}})
	case strings.HasPrefix(p, "/v1/memberships/admin/tiers/") && r.Method == http.MethodPut:
		t := membershipTierFake(now)
		t["status"] = body["status"]
		writeJ(w, 200, t)
	case p == "/v1/memberships/admin/settings" && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"refundWindowDays": 7, "retryDays": []any{1, 3, 5}, "updatedBy": "stf-1", "updatedAt": now, "version": 2})
	case p == "/v1/memberships/admin/settings" && r.Method == http.MethodPut:
		writeJ(w, 200, map[string]any{"refundWindowDays": body["refundWindowDays"], "retryDays": body["retryDays"], "updatedBy": "stf-1", "updatedAt": now, "version": 3})
	case p == "/v1/memberships/admin/settings/changes":
		writeJ(w, 200, []any{map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000d2", "changedBy": "stf-1", "changedAt": now, "reason": "Match the returns window", "beforeJson": "{}", "afterJson": "{}"}})
	case p == "/v1/memberships/admin/members":
		writeJ(w, 200, []any{membershipFake(now)})
	case strings.HasSuffix(p, "/events") && strings.HasPrefix(p, "/v1/memberships/admin/members/"):
		writeJ(w, 200, []any{map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000e2", "type": "JOINED", "tierCode": "plus", "actor": "cus-1", "detail": "Started a 14 day trial", "occurredAt": now}})
	case strings.HasPrefix(p, "/v1/memberships/customers/"):
		writeJ(w, 200, map[string]any{"membership": membershipFake(now), "tier": membershipTierFake(now), "pendingTier": nil, "usage": []any{map[string]any{"benefit": "FREE_DELIVERY", "uses": 2, "amountMinor": 1198, "currency": "USD"}},
			"charges": []any{map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000f1", "kind": "JOIN", "amountMinor": 999, "currency": "USD", "status": "SUCCEEDED", "refundedMinor": 0, "createdAt": now}},
			"refund": map[string]any{"eligible": true, "reason": "OK", "amountMinor": 999, "currency": "USD"}, "trialAvailable": false, "refundWindowDays": 7,
			"card": map[string]any{"brand": "visa", "last4": "4242"}})
	case strings.HasSuffix(p, "/refund") && strings.HasPrefix(p, "/v1/memberships/admin/charges/"):
		writeJ(w, 200, map[string]any{"id": "0f0f0f0f-0000-4000-8000-0000000000f1", "status": "REFUNDED", "refundedMinor": 999})
	case p == "/v1/memberships/admin/report":
		writeJ(w, 200, map[string]any{"currency": "USD", "liveMembers": 15, "trialing": 3, "mrrMinor": 11988, "arrMinor": 143856, "chargedMinor": 21978, "refundedMinor": 999, "netRevenueMinor": 20979,
			"benefitCostMinor": 5400, "creditGrantedMinor": 1200, "feesWaivedMinor": 4200, "membersAtStart": 10, "joined": 8, "ended": 3, "lapsed": 1, "canceled": 2, "refunded": 1, "churnBps": 3000,
			"tiers": []any{map[string]any{"tierCode": "plus", "tierName": "Plus", "status": "PUBLISHED", "members": 15, "trialing": 3, "active": 11, "pastDue": 1, "cancelling": 2, "mrrMinor": 11988,
				"joined": 8, "canceled": 2, "lapsed": 1, "refunded": 1, "membersAtStart": 10, "churnBps": 3000, "chargedMinor": 21978, "refundedMinor": 999}},
			"costs": []any{map[string]any{"benefit": "FREE_DELIVERY", "uses": 7, "amountMinor": 4200}, map[string]any{"benefit": "CASHBACK", "uses": 9, "amountMinor": 1200}}})
	case p == "/v1/settings/summary":
		writeJ(w, 200, []any{map[string]any{"key": "settlement.hold", "label": "Hold before a seller can be paid", "value": "7 days after delivery", "help": "Protects against returns.", "source": "SETTLEMENT_HOLD"},
			map[string]any{"key": "payout.minimum", "label": "Smallest payout a seller can request", "value": "$25.00", "help": "Requests below it are refused.", "source": "PAYOUT_MINIMUM_MINOR"}})
	case p == "/v1/tax/nexus":
		writeJ(w, 200, map[string]any{"through": "2026-10", "windowMonths": 12, "defaultSalesThresholdMinor": 10000000, "states": []any{
			map[string]any{"jurisdiction": "US-TX", "name": "Texas", "facilitator": true, "salesMinor": 12000000, "orders": 340, "salesThresholdMinor": 10000000, "percent": 120, "status": "EXCEEDED", "registered": false, "customised": false},
			map[string]any{"jurisdiction": "US-GA", "name": "Georgia", "facilitator": true, "salesMinor": 2000000, "orders": 40, "salesThresholdMinor": 10000000, "transactionsThreshold": 200, "percent": 20, "status": "BELOW", "registered": false, "customised": true, "updatedBy": "u-1", "updatedAt": now}}})
	case strings.HasPrefix(p, "/v1/tax/nexus/") && r.Method == http.MethodPut:
		writeJ(w, 200, body)
	case strings.HasPrefix(p, "/v1/tax/nexus/"):
		writeJ(w, 200, map[string]any{"jurisdiction": strings.TrimPrefix(p, "/v1/tax/nexus/"), "name": "Texas", "facilitator": true, "salesMinor": 12000000, "orders": 340, "salesThresholdMinor": 10000000, "percent": 120,
			"status": "EXCEEDED", "registered": false, "customised": false})
	case p == "/v1/tax/remittances" && r.Method == http.MethodGet:
		writeJ(w, 200, map[string]any{"obligations": []any{
			map[string]any{"month": "2026-09", "jurisdiction": "US-TX", "currency": "USD", "dueMinor": 133600, "status": "NOT_FILED", "outstandingMinor": 133600, "changedSinceFilingMinor": 0},
			map[string]any{"month": "2026-08", "jurisdiction": "US-TX", "currency": "USD", "dueMinor": 100000, "status": "SETTLED", "outstandingMinor": 0, "changedSinceFilingMinor": 0,
				"remittance": map[string]any{"id": "rem-1", "jurisdiction": "US-TX", "month": "2026-08", "currency": "USD", "dueMinor": 100000, "paidMinor": 100000, "filedOn": "2026-09-15", "paidOn": "2026-09-15",
					"confirmationRef": "TX-CONF-1", "recordedBy": "u-1", "recordedAt": now, "active": true}}}})
	case p == "/v1/tax/remittances" && r.Method == http.MethodPost:
		writeJ(w, 201, body)
	case strings.HasPrefix(p, "/v1/tax/remittances/") && strings.HasSuffix(p, "/history"):
		writeJ(w, 200, []any{map[string]any{"id": "rem-1", "jurisdiction": "US-TX", "month": "2026-08", "currency": "USD", "dueMinor": 100000, "paidMinor": 100000, "filedOn": "2026-09-15",
			"paidOn": "2026-09-15", "recordedBy": "u-1", "recordedAt": now, "active": true}})
	case strings.HasSuffix(p, "/void") && strings.HasPrefix(p, "/v1/tax/remittances/"):
		writeJ(w, 200, map[string]any{"id": "rem-1", "active": false})
	case strings.HasPrefix(p, "/v1/tax/remittances/"):
		parts := strings.Split(strings.TrimPrefix(p, "/v1/tax/remittances/"), "/")
		if parts[0] == "2026-08" {
			writeJ(w, 200, map[string]any{"month": "2026-08", "jurisdiction": parts[1], "currency": "USD", "dueMinor": 100000, "status": "SETTLED", "outstandingMinor": 0, "changedSinceFilingMinor": 0,
				"remittance": map[string]any{"id": "rem-1", "jurisdiction": parts[1], "month": "2026-08", "currency": "USD", "dueMinor": 100000, "paidMinor": 100000, "filedOn": "2026-09-15",
					"paidOn": "2026-09-15", "recordedBy": "u-1", "recordedAt": now, "active": true}})
		} else {
			writeJ(w, 200, map[string]any{"month": parts[0], "jurisdiction": parts[1], "currency": "USD", "dueMinor": 133600, "status": "NOT_FILED", "outstandingMinor": 133600, "changedSinceFilingMinor": 0})
		}
	case p == "/v1/tax/settings":
		if r.Method == http.MethodPut {
			writeJ(w, 200, body)
		} else {
			writeJ(w, 200, map[string]any{"method": "RULES", "configuredDefault": "RULES", "stripeAvailable": false, "changedBy": "u-1", "changedAt": now,
				"history": []any{map[string]any{"from": nil, "to": "RULES", "reason": "Our own rules until Stripe Tax is set up", "changedBy": "u-1", "changedAt": now}}})
		}
	case p == "/v1/settlement/transfers":
		writeJ(w, 200, []any{map[string]any{"id": "t-1", "orderId": "ord-1", "orgId": "org-1", "stripeTransferId": "tr_1ABC123", "amountMinor": 50000, "reversedMinor": 0, "currency": "USD", "createdAt": now,
			"dashboardUrl": "https://dashboard.stripe.com/test/connect/transfers/tr_1ABC123"}})
	case p == "/v1/stripe/lookup":
		writeJ(w, 200, map[string]any{"ref": r.URL.Query().Get("ref"), "object": map[string]any{"id": r.URL.Query().Get("ref"), "kind": "Payout", "status": "in_transit", "amountMinor": 50000, "links": map[string]any{}},
			"journals": []any{}, "connectedAccount": "acct_1", "balance": map[string]any{"account": "acct_1", "currency": "USD", "availableMinor": 1000, "pendingMinor": 2000}})
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
	case p == "/v1/exchange-rates" || p == "/v1/exchange-rates/history":
		writeJ(w, 200, page(map[string]any{"currency": "MXN", "rateDate": "2026-10-20", "rateToUsd": 0.0577, "updatedBy": "ops", "updatedAt": now, "version": 0}))
	case p == "/v1/lots" || strings.HasPrefix(p, "/v1/lots/"):
		lot := map[string]any{"id": "lot-1", "sku": "AT-SHEA", "lotNo": "L1", "nodeId": "HOU", "qtyOnHand": 10, "qtyReserved": 2, "qtyAvailable": 8, "status": "SELLABLE", "version": 4,
			"unitLandedCostMinor": 192, "currency": "USD", "fxRateToUsd": 1, "unitCostUsdMinor": 192}
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
	return staffBFFWith(t, f)
}

// staffBFFWith builds the staff BFF against the fake platform; options add services (for example Lola) that need the
// deps the shell, tables and dashboard use.
func staffBFFWith(t *testing.T, f *fakePlatform, opts ...func(d app.StaffDeps, staff *httpapi.StaffServices)) func(token, method, path string, body any, headers ...string) *httptest.ResponseRecorder {
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
		Jobs:   exportfiles.NewMemoryJobs(), Maintenance: app.NewMaintenanceService(memcache.NewMaintenance(), nil, nil), Blobs: files, Renderer: exportfiles.NewRenderer(), ExportBasePath: "/api/bff", Log: log, Background: func(fn func()) { fn() }, RetailOrgID: "org-retail"}
	staff := httpapi.StaffServices{Shell: app.NewStaffShellService(d), Records: app.NewStaffRecordsService(d), Dashboard: app.NewStaffDashboardService(d),
		Exports: app.NewStaffExportsService(d), Support: app.NewStaffSupportService(d), Fees: app.NewStaffFeesService(d), Files: files,
		Dispatch: app.NewStaffDispatchService(d), Notifications: app.NewStaffNotificationsService(d)}
	for _, o := range opts {
		o(d, &staff)
	}
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
		if res == "stripe-lookup" {
			continue // a lookup shows nothing until an id is searched for; checked below
		}
		pg := do("admin", "GET", "/v1/staff/resources/"+res+"?size=10", nil, 200)
		rows := pg["rows"].([]any)
		if len(rows) == 0 {
			t.Fatalf("%s: no rows", res)
		}
		id := rows[0].(map[string]any)["id"].(string)
		do("admin", "GET", "/v1/staff/resources/"+res+"/"+id, nil, 200)
	}
	// AfroTransact credit and gift cards (S320, S321): names, not ids; the reason travels; nothing pays credit out.
	adj := do("admin", "GET", "/v1/staff/resources/credit-adjustments/0f0f0f0f-0000-4000-8000-0000000000a1", nil, 200)
	if adj["title"] != "Credit added for Amara Nwosu" {
		t.Fatalf("credit adjustment sheet = %v", adj["title"])
	}
	do("admin", "POST", "/v1/staff/resources/credit-adjustments/0f0f0f0f-0000-4000-8000-0000000000a1/actions/approve", nil, 200)
	do("support", "POST", "/v1/staff/resources/credit-adjustments", map[string]any{"values": map[string]any{"customerEmail": "amara@example.com", "change": "ADD", "amount": 5, "source": "STAFF", "reason": "Goodwill for a late order"}}, 403)
	do("admin", "POST", "/v1/staff/resources/credit-adjustments", map[string]any{"values": map[string]any{"customerEmail": "nobody@example.com", "change": "ADD", "amount": 5, "source": "STAFF", "reason": "Goodwill for a late order"}}, 422)
	do("admin", "POST", "/v1/staff/resources/credit-adjustments", map[string]any{"values": map[string]any{"customerEmail": "amara@example.com", "change": "TAKE_BACK", "amount": 12.5, "source": "STAFF", "reason": "Credited twice by mistake"}}, 201)
	if sent := f.bodies["POST /v1/credit/adjustments"]; sent["customerId"] != "cus-1" || sent["amountMinor"] != float64(-1250) || sent["reason"] != "Credited twice by mistake" || sent["source"] != "STAFF" {
		t.Fatalf("payments credit adjustment body = %v", sent)
	}
	card := do("admin", "GET", "/v1/staff/resources/gift-cards/0f0f0f0f-0000-4000-8000-0000000000c1", nil, 200)
	if card["title"] != "Gift card •••• K3M9" {
		t.Fatalf("gift card sheet = %v", card["title"])
	}
	do("admin", "POST", "/v1/staff/resources/gift-cards/0f0f0f0f-0000-4000-8000-0000000000c1/actions/void", map[string]any{"values": map[string]any{"reason": "Bought by mistake, buyer asked"}}, 200)
	if sent := f.bodies["POST /v1/gift-cards/0f0f0f0f-0000-4000-8000-0000000000c1/void"]; sent["reason"] != "Bought by mistake, buyer asked" {
		t.Fatalf("void body = %v", sent)
	}
	do("support", "POST", "/v1/staff/resources/gift-cards/0f0f0f0f-0000-4000-8000-0000000000c1/actions/void", map[string]any{"values": map[string]any{"reason": "Bought by mistake, buyer asked"}}, 403)
	do("admin", "POST", "/v1/staff/resources/gift-cards", map[string]any{"values": map[string]any{"amount": 25, "recipientEmail": "ama@example.com", "locale": "en", "sendAt": "2026-12-01", "reason": "Goodwill after a lost parcel"}}, 201)
	if sent := f.bodies["POST /v1/gift-cards"]; sent["amountMinor"] != float64(2500) || sent["sendAt"] != "2026-12-01T09:00:00Z" || sent["reason"] != "Goodwill after a lost parcel" {
		t.Fatalf("issue body = %v", sent)
	}
	liab := do("admin", "GET", "/v1/staff/resources/credit-liabilities", nil, 200)
	if len(liab["rows"].([]any)) != 9 {
		t.Fatalf("liability rows = %v", liab["rows"])
	}
	do("admin", "PATCH", "/v1/staff/resources/money-settings/money", map[string]any{"values": map[string]any{"giftCardMin": 10, "giftCardMax": 600, "presets": "25, 50, 100", "creditApprovalLimit": 150,
		"terms.en": "Gift cards never expire.", "reason": "Raise the ceiling for the holidays"}}, 200)
	if sent := f.bodies["PUT /v1/money/settings"]; sent["giftCardMaxMinor"] != float64(60000) || sent["creditApprovalLimitMinor"] != float64(15000) || sent["version"] != float64(3) ||
		sent["reason"] != "Raise the ceiling for the holidays" || len(sent["giftCardPresets"].([]any)) != 3 {
		t.Fatalf("money settings body = %v", sent)
	}
	do("admin", "PATCH", "/v1/staff/resources/money-settings/money", map[string]any{"values": map[string]any{"giftCardMin": 10, "giftCardMax": 600, "presets": "lots", "creditApprovalLimit": 150, "reason": "Raise the ceiling for the holidays"}}, 422)
	do("support", "PATCH", "/v1/staff/resources/money-settings/money", map[string]any{"values": map[string]any{"giftCardMin": 10, "giftCardMax": 600, "presets": "25", "creditApprovalLimit": 150, "reason": "Raise the ceiling for the holidays"}}, 403)
	// Membership (S323): tiers staff define in Settings, with a reason; Save is refused until something changes.
	tier := do("admin", "GET", "/v1/staff/resources/membership-tiers/plus", nil, 200)
	if tier["title"] != "Plus membership" {
		t.Fatalf("tier sheet = %v", tier["title"])
	}
	if tier["status"] != "PUBLISHED" {
		t.Fatalf("tier status = %v", tier["status"])
	}
	tierEdit := func(change func(v map[string]any)) map[string]any {
		v := map[string]any{"name": "Plus", "description": "Free delivery and cashback", "status": "PUBLISHED", "sortOrder": 10, "monthlyPrice": 9.99, "yearlyPrice": 99, "trialDays": 14,
			"freeDelivery": true, "freeDeliveryMin": 25, "freeDeliveryCap": 0, "freeShipping": true, "freeShippingMin": 25, "freeShippingCap": 15, "cashback": true, "cashbackPercent": 2,
			"cashbackMin": 0, "cashbackCap": 0, "memberPrices": false, "earlyAccess": false, "earlyAccessHours": 0, "prioritySupport": true, "freeReturns": false, "boostedRewards": false,
			"boostedPercent": 0, "monthlyCredit": false, "monthlyCreditAmount": 0, "reason": "Raise the cashback for the holidays"}
		change(v)
		return map[string]any{"values": v}
	}
	do("admin", "PATCH", "/v1/staff/resources/membership-tiers/plus", tierEdit(func(map[string]any) {}), 422) // nothing changed: Save stays disabled, the server refuses too
	do("admin", "PATCH", "/v1/staff/resources/membership-tiers/plus", tierEdit(func(v map[string]any) { v["cashbackPercent"] = 3; v["earlyAccess"] = true; v["earlyAccessHours"] = 24 }), 200)
	if sent := f.bodies["PUT /v1/memberships/admin/tiers/plus"]; sent["reason"] != "Raise the cashback for the holidays" || sent["expectedVersion"] != float64(4) || sent["monthlyPriceMinor"] != float64(999) ||
		len(sent["benefits"].([]any)) != 5 {
		t.Fatalf("tier body = %v", sent)
	}
	do("admin", "PATCH", "/v1/staff/resources/membership-tiers/plus", tierEdit(func(v map[string]any) { v["cashbackPercent"] = 90 }), 422)
	do("admin", "PATCH", "/v1/staff/resources/membership-tiers/plus", tierEdit(func(v map[string]any) { v["earlyAccess"] = true; v["earlyAccessHours"] = 0 }), 422)
	do("admin", "PATCH", "/v1/staff/resources/membership-tiers/plus", tierEdit(func(v map[string]any) { v["cashbackPercent"] = 3; v["reason"] = "" }), 422)
	do("support", "PATCH", "/v1/staff/resources/membership-tiers/plus", tierEdit(func(v map[string]any) { v["cashbackPercent"] = 3 }), 403)
	do("admin", "POST", "/v1/staff/resources/membership-tiers/plus/actions/retire", map[string]any{"values": map[string]any{"reason": "Replaced by the Premium tier"}}, 200)
	if sent := f.bodies["PUT /v1/memberships/admin/tiers/plus"]; sent["status"] != "RETIRED" || sent["reason"] != "Replaced by the Premium tier" {
		t.Fatalf("retire body = %v", sent)
	}
	do("admin", "POST", "/v1/staff/resources/membership-tiers/plus/actions/publish", map[string]any{"values": map[string]any{"reason": "Opening Plus again"}}, 409) // it is published
	do("admin", "POST", "/v1/staff/resources/membership-tiers", map[string]any{"values": map[string]any{"code": "premium", "name": "Premium", "status": "DRAFT", "sortOrder": 20, "monthlyPrice": 19.99,
		"yearlyPrice": 199, "trialDays": 7, "freeDelivery": true, "freeDeliveryMin": 0, "cashback": true, "cashbackPercent": 5, "monthlyCredit": true, "monthlyCreditAmount": 5,
		"reason": "A bigger tier for large households"}}, 201)
	if sent := f.bodies["POST /v1/memberships/admin/tiers"]; sent["code"] != "premium" || sent["expectedVersion"] != nil || len(sent["benefits"].([]any)) != 3 {
		t.Fatalf("new tier body = %v", sent)
	}
	do("admin", "POST", "/v1/staff/resources/membership-tiers", map[string]any{"values": map[string]any{"code": "Bad Code", "name": "Premium", "status": "DRAFT", "sortOrder": 20,
		"monthlyPrice": 19.99, "yearlyPrice": 199, "trialDays": 7, "reason": "A bigger tier for large households"}}, 422)
	rules := do("admin", "GET", "/v1/staff/resources/membership-settings/membership", nil, 200)
	if rules["title"] != "Membership billing rules" {
		t.Fatalf("rules sheet = %v", rules["title"])
	}
	do("admin", "PATCH", "/v1/staff/resources/membership-settings/membership", map[string]any{"values": map[string]any{"refundWindowDays": 7, "retryDays": "1, 3, 5", "reason": "No change at all here"}}, 422)
	do("admin", "PATCH", "/v1/staff/resources/membership-settings/membership", map[string]any{"values": map[string]any{"refundWindowDays": 14, "retryDays": "2, 4", "reason": "Match the returns window"}}, 200)
	if sent := f.bodies["PUT /v1/memberships/admin/settings"]; sent["refundWindowDays"] != float64(14) || len(sent["retryDays"].([]any)) != 2 || sent["expectedVersion"] != float64(2) {
		t.Fatalf("membership settings body = %v", sent)
	}
	members := do("admin", "GET", "/v1/staff/resources/memberships", nil, 200)
	if row := members["rows"].([]any)[0].(map[string]any)["cells"].(map[string]any); row["tierName"] != "Plus" || row["customerName"] == "cus-1" || row["customerName"] == nil {
		t.Fatalf("members table = %v", row)
	}
	if found := do("admin", "GET", "/v1/staff/resources/memberships?q=plus", nil, 200)["rows"].([]any); len(found) != 1 {
		t.Fatalf("search by tier name found %d members, want 1", len(found))
	}
	member := do("admin", "GET", "/v1/staff/resources/memberships/0f0f0f0f-0000-4000-8000-0000000000c9", nil, 200)
	if len(member["tables"].([]any)) != 3 {
		t.Fatalf("member sheet tables = %v", member["tables"])
	}
	do("admin", "POST", "/v1/staff/resources/memberships/0f0f0f0f-0000-4000-8000-0000000000c9/actions/refund", map[string]any{"values": map[string]any{"reason": "Charged twice by mistake"}}, 200)
	if sent := f.bodies["POST /v1/memberships/admin/charges/0f0f0f0f-0000-4000-8000-0000000000f1/refund"]; sent["reason"] != "Charged twice by mistake" {
		t.Fatalf("refund body = %v", sent)
	}
	do("support", "POST", "/v1/staff/resources/memberships/0f0f0f0f-0000-4000-8000-0000000000c9/actions/refund", map[string]any{"values": map[string]any{"reason": "Charged twice by mistake"}}, 403)
	report := do("admin", "GET", "/v1/staff/resources/membership-report", nil, 200)
	if len(report["rows"].([]any)) != 17 {
		t.Fatalf("report rows = %d", len(report["rows"].([]any)))
	}
	made := do("admin", "POST", "/v1/staff/resources/stripe-lookup", map[string]any{"values": map[string]any{"ref": "po_123456", "orgId": "org-1"}}, 201)
	if made["id"] != "po_123456 org-1" {
		t.Fatalf("stripe lookup id = %v", made["id"])
	}
	do("admin", "GET", "/v1/staff/resources/nope", nil, 404)

	// Names, not ids: the Seller of a product is the organisation's name, search finds the name and the id, and the
	// sheet keeps the id as a secondary ID field.
	prods := do("admin", "GET", "/v1/staff/resources/products", nil, 200)
	sellerKey := ""
	for _, c := range prods["columns"].([]any) {
		if c.(map[string]any)["label"] == "Seller" {
			sellerKey = c.(map[string]any)["key"].(string)
		}
	}
	if sellerKey == "" || sellerKey == "ownerOrgId" || prods["rows"].([]any)[0].(map[string]any)["cells"].(map[string]any)[sellerKey] != "Kente Naturals" {
		t.Fatalf("products table = %v", prods)
	}
	for _, q := range []string{"kente", "org-1"} {
		if found := do("admin", "GET", "/v1/staff/resources/products?q="+q, nil, 200)["rows"].([]any); len(found) != 1 {
			t.Fatalf("search %q found %d products, want 1", q, len(found))
		}
	}
	psheet := do("admin", "GET", "/v1/staff/resources/products/prd-1", nil, 200)
	if !strings.Contains(psheet["subtitle"].(string), "Kente Naturals") || strings.Contains(psheet["subtitle"].(string), "org-1") {
		t.Fatalf("product subtitle = %v", psheet["subtitle"])
	}
	idsShown := false
	for _, sec := range psheet["sections"].([]any) {
		for _, f := range sec.(map[string]any)["fields"].([]any) {
			fm := f.(map[string]any)
			if fm["value"] == "org-1" {
				idsShown = sec.(map[string]any)["title"] == "IDs" && fm["label"] == "Seller ID" && fm["kind"] == "mono"
				if !idsShown {
					t.Fatalf("the raw id appears outside the IDs section: %v", fm)
				}
			}
		}
	}
	if !idsShown {
		t.Fatalf("no Seller ID field in %v", psheet["sections"])
	}
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
		items[1].(map[string]any)["latestVersion"] != float64(2) || items[1].(map[string]any)["updatedBy"] != "Amara Nwosu" ||
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
	if tplCreated["version"] != float64(3) || tplCreated["channel"] != "email" || tplCreated["createdBy"] != "Amara Nwosu" {
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
