package testkit

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

// Organisation ids of the fake member.
const (
	SellerOrgID   = "11111111-1111-1111-1111-111111111111"
	ProviderOrgID = "22222222-2222-2222-2222-222222222222"
	PendingOrgID  = "33333333-3333-3333-3333-333333333333"
)

// MemberOrg is an organisation the fake identity lists in GET /v1/me.
type MemberOrg struct {
	ID, Kind, Name, Status, Role string
}

// DefaultMemberOrgs returns an approved seller (owner), an approved provider (owner) and a seller still in review.
func DefaultMemberOrgs() []MemberOrg {
	return []MemberOrg{
		{ID: SellerOrgID, Kind: "SELLER", Name: "Ade's Kitchen", Status: "APPROVED", Role: "seller.owner"},
		{ID: ProviderOrgID, Kind: "PROVIDER", Name: "Ama's Braiding Studio", Status: "APPROVED", Role: "provider.owner"},
		{ID: PendingOrgID, Kind: "SELLER", Name: "Bonwire Weavers", Status: "PENDING", Role: "seller.owner"},
	}
}

func (u *Upstreams) memberOrgsJSON() []map[string]any {
	out := []map[string]any{}
	for _, o := range u.MemberOrgs {
		out = append(out, map[string]any{"id": o.ID, "kind": o.Kind, "name": o.Name, "status": o.Status, "role": o.Role})
	}
	return out
}

func (u *Upstreams) orgByID(id string) (MemberOrg, bool) {
	for _, o := range u.MemberOrgs {
		if o.ID == id {
			return o, true
		}
	}
	return MemberOrg{}, false
}

type sellerState struct {
	plan          string
	defaultPM     string
	listingStatus map[string]string
	shipStatus    string
	tracking      string
	storefront    map[string]any
}

func newSellerState() *sellerState {
	return &sellerState{plan: "seller_growth", defaultPM: "aaaaaaaa-0000-0000-0000-000000000001", listingStatus: map[string]string{"prod-1": "LIVE"},
		shipStatus: "placed", storefront: map[string]any{"orgId": SellerOrgID, "kind": "SELLER", "slug": "ade-s-kitchen", "name": "Ade's Kitchen",
			"tagline": "Home-style Nigerian pantry", "theme": map[string]any{"primary": "#0A8264", "accent": "#FFD400"}, "status": "DRAFT",
			"social": []any{map[string]any{"network": "instagram", "url": "https://instagram.com/adeskitchen", "status": "APPROVED"}}, "version": 3}}
}

func bearerSub(w http.ResponseWriter, r *http.Request) (string, bool) {
	sub := subjectOf(r)
	if sub == "" {
		problem(w, 401, "auth.unauthenticated", "Bearer token required", nil)
		return "", false
	}
	return sub, true
}

func (u *Upstreams) sellerRoutes(mux *http.ServeMux) {
	u.identitySellerRoutes(mux)
	u.sellerServiceRoutes(mux)
	u.catalogSellerRoutes(mux)
	u.moneyRoutes(mux)
	if u.opts.SellerPending {
		u.ordersRoutes(mux)
		u.bookingsRoutes(mux)
	}
}

func (u *Upstreams) identitySellerRoutes(mux *http.ServeMux) {
	mux.HandleFunc("PUT /v1/me/active-org", func(w http.ResponseWriter, r *http.Request) {
		sub, ok := bearerSub(w, r)
		if !ok {
			return
		}
		var b struct {
			OrgID string `json:"orgId"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		if _, ok := u.orgByID(b.OrgID); !ok {
			problem(w, 403, "identity.not-a-member", "Not a member", nil)
			return
		}
		u.ActiveOrgID = b.OrgID
		writeJSON(w, 200, map[string]any{"id": sub, "firstName": "Adaeze", "lastName": "Nwosu", "email": "adaeze@example.com", "orgId": b.OrgID, "orgs": u.memberOrgsJSON()})
	})
	mux.HandleFunc("GET /v1/admin/module-access", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		denied := u.AccessDeniedMatrix
		u.mu.Unlock()
		if denied {
			problem(w, 403, "identity.forbidden", "access:read required", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"portal": "SELLER", "modules": []map[string]any{
			{"key": "sHome", "label": "Home", "levels": map[string]string{"seller.owner": "full", "seller.fulfilment": "view"}},
			{"key": "sOrders", "label": "Orders", "levels": map[string]string{"seller.owner": "full", "seller.fulfilment": "full"}},
		}})
	})
	mux.HandleFunc("PUT /v1/orgs/{orgId}/members/{userId}", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); ok {
			w.WriteHeader(204)
		}
	})
}

func (u *Upstreams) sellerServiceRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/orgs/{id}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		o, ok := u.orgByID(r.PathValue("id"))
		u.mu.Unlock()
		if !ok {
			problem(w, 404, "seller.org-not-found", "No such organisation", nil)
			return
		}
		status := o.Status
		if status == "PENDING" {
			status = "INFO_REQUESTED"
		}
		writeJSON(w, 200, map[string]any{"id": o.ID, "kind": o.Kind, "displayName": o.Name, "legalName": o.Name + " LLC", "country": "US", "status": status,
			"members": []map[string]any{{"userId": "user-1", "roleKey": o.Role, "createdAt": "2026-04-10T12:00:00Z"},
				{"userId": "user-2-abcdef", "roleKey": strings.Split(o.Role, ".")[0] + ".finance", "createdAt": "2026-05-01T12:00:00Z"}}})
	})
	mux.HandleFunc("GET /v1/applications/me", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		writeJSON(w, 200, map[string]any{"id": "app-1", "reference": "APP-30131", "orgId": PendingOrgID, "kind": "SELLER", "status": "INFO_REQUESTED",
			"currentStep": "COMPLIANCE", "steps": []string{"BUSINESS", "OWNERS", "STRIPE", "PAYOUTS", "PLAN", "COMPLIANCE"},
			"completedSteps": []string{"BUSINESS", "OWNERS", "STRIPE", "PAYOUTS", "PLAN"}, "requestedItems": []string{"Harris County food permit"}})
	})
	mux.HandleFunc("GET /v1/orgs/{id}/account-health", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") == ProviderOrgID {
			writeJSON(w, 200, map[string]any{"status": "COMPLIANT", "chargesEnabled": true, "payoutsEnabled": true, "openItems": 0, "requirements": []any{}})
			return
		}
		writeJSON(w, 200, map[string]any{"accountId": "acct_1", "orgId": r.PathValue("id"), "status": "ACTION_NEEDED", "chargesEnabled": true,
			"payoutsEnabled": true, "payoutsPaused": false, "listingsHidden": false, "openItems": 2, "currentDeadline": "2026-10-24T00:00:00Z",
			"requirements": []map[string]any{{"requirement": "company.tax_id", "bucket": "currently_due"}, {"requirement": "external_account", "bucket": "past_due"}}})
	})
	billing := func() map[string]any {
		return map[string]any{"orgId": SellerOrgID, "planCode": u.seller.plan, "planName": "Seller Growth", "cadence": "QUARTERLY", "status": "ACTIVE",
			"price": map[string]any{"cadence": "QUARTERLY", "months": 3, "totalCents": 11115, "perMonthCents": 3705, "savingCents": 585,
				"perMonthLabel": "$37.05/mo", "billedLabel": "Billed $111.15 every 3 months", "savingLabel": "Save $5.85 vs monthly"},
			"currentPeriodEnd": "2027-01-10T00:00:00Z", "nextChargeAt": "2027-01-10T00:00:00Z", "nextChargeCents": 11115, "nextChargeLabel": "$111.15 on Jan 10, 2027",
			"cancelAtPeriodEnd": false, "paymentMethods": u.paymentMethodsJSON()}
	}
	mux.HandleFunc("GET /v1/orgs/{id}/billing", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, billing())
	})
	mux.HandleFunc("GET /v1/orgs/{id}/billing/invoices", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"id": "in_s3", "number": "AT-0003", "periodStart": "2026-07-10T00:00:00Z", "periodEnd": "2026-10-10T00:00:00Z",
			"amountDueCents": 11115, "amountPaidCents": 11115, "currency": "usd", "status": "paid", "invoicePdf": "https://files.example/in_s3.pdf",
			"hostedInvoiceUrl": "https://invoice.example/in_s3", "createdAt": "2026-10-10T00:00:00Z"}})
	})
	mux.HandleFunc("GET /v1/plans", func(w http.ResponseWriter, _ *http.Request) {
		price := func(cad string, months int, total, per, save int64) map[string]any {
			return map[string]any{"cadence": cad, "months": months, "totalCents": total, "perMonthCents": per, "savingCents": save, "perMonthLabel": "$x/mo",
				"billedLabel": "Billed", "savingLabel": ""}
		}
		writeJSON(w, 200, []map[string]any{
			{"code": "seller_essentials", "kind": "SELLER", "name": "Seller Essentials", "tagline": "Start selling", "monthlyCents": 0, "trialDays": 0,
				"features": []string{"Up to 50 listings"}, "prices": []any{price("MONTHLY", 1, 0, 0, 0)}},
			{"code": "seller_growth", "kind": "SELLER", "name": "Seller Growth", "tagline": "Most popular", "monthlyCents": 3900, "trialDays": 30,
				"features": []string{"Up to 1,000 listings"}, "prices": []any{price("MONTHLY", 1, 3900, 3900, 0), price("QUARTERLY", 3, 11115, 3705, 585), price("ANNUAL", 12, 39000, 3250, 7800)}},
		})
	})
	mux.HandleFunc("POST /v1/orgs/{id}/billing/plan", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			PlanCode string `json:"planCode"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		applied := "AT_RENEWAL"
		if b.PlanCode == "seller_pro" {
			applied = "IMMEDIATE_UPGRADE"
			u.seller.plan = b.PlanCode
		}
		writeJSON(w, 200, map[string]any{"applied": applied, "billing": billing()})
	})
	mux.HandleFunc("POST /v1/orgs/{id}/billing/payment-methods/{pm}/default", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		u.seller.defaultPM = r.PathValue("pm")
		writeJSON(w, 200, u.paymentMethodsJSON())
	})
	mux.HandleFunc("DELETE /v1/orgs/{id}/billing/payment-methods/{pm}", func(w http.ResponseWriter, r *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		if r.PathValue("pm") == u.seller.defaultPM {
			problem(w, 409, "billing.default-payment-method", "Make another method the default first", nil)
			return
		}
		writeJSON(w, 200, u.paymentMethodsJSON()[:1])
	})
	// storefront service
	mux.HandleFunc("GET /v1/storefronts/me/draft", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, u.seller.storefront)
	})
	mux.HandleFunc("PUT /v1/storefronts/me/draft", func(w http.ResponseWriter, r *http.Request) {
		var doc map[string]any
		if err := json.NewDecoder(r.Body).Decode(&doc); err != nil {
			problem(w, 422, "storefront.invalid", "Invalid draft", nil)
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		u.seller.storefront = doc
		writeJSON(w, 200, doc)
	})
	mux.HandleFunc("POST /v1/storefronts/me/publish", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		u.seller.storefront["status"] = "PUBLISHED"
		u.seller.storefront["publishedAt"] = "2026-10-10T12:00:00Z"
		w.WriteHeader(200)
	})
}

func (u *Upstreams) paymentMethodsJSON() []map[string]any {
	card := map[string]any{"id": "aaaaaaaa-0000-0000-0000-000000000001", "kind": "card", "brand": "visa", "last4": "4242", "expMonth": 8, "expYear": 2028,
		"verified": true, "isDefault": u.seller.defaultPM == "aaaaaaaa-0000-0000-0000-000000000001", "label": "Visa ending in 4242"}
	bank := map[string]any{"id": "aaaaaaaa-0000-0000-0000-000000000002", "kind": "bank", "bankName": "Chase", "accountType": "Business checking",
		"last4": "4417", "verified": true, "receivesPayouts": true, "isDefault": u.seller.defaultPM == "aaaaaaaa-0000-0000-0000-000000000002"}
	return []map[string]any{card, bank}
}

func (u *Upstreams) catalogSellerRoutes(mux *http.ServeMux) {
	product := func(id, status string) map[string]any {
		return map[string]any{"id": id, "atin": "ATADE00001", "ownerKind": "SELLER", "ownerOrgId": SellerOrgID, "title": "Ofada Rice, Destoned · 5 lb",
			"images": []map[string]any{{"url": "https://img.example/ofada.webp", "contentType": "image/webp"}},
			"variants": []map[string]any{{"id": "v1", "atin": "ATADEV0001", "sellerSku": "OFADA-5LB", "options": map[string]string{"Net weight": "5 lb"}, "priceMinor": 2499, "currency": "USD", "quantity": 40},
				{"id": "v2", "atin": "ATADEV0002", "sellerSku": "OFADA-10LB", "options": map[string]string{"Net weight": "10 lb"}, "priceMinor": 4299, "currency": "USD", "quantity": 12}},
			"status": status, "updatedAt": "2026-10-09T10:00:00Z"}
	}
	mux.HandleFunc("GET /v1/products", func(w http.ResponseWriter, r *http.Request) {
		if _, ok := bearerSub(w, r); !ok {
			return
		}
		u.mu.Lock()
		st := u.seller.listingStatus["prod-1"]
		u.mu.Unlock()
		items := []any{}
		if f := r.URL.Query().Get("status"); f == "" || f == st {
			items = append(items, product("prod-1", st))
		}
		writeJSON(w, 200, map[string]any{"items": items, "total": len(items), "page": 0, "size": 25})
	})
	mux.HandleFunc("POST /v1/products", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		status := "IN_REVIEW"
		if _, ok := b["categoryRequestId"]; ok {
			status = "PENDING_CATEGORY_APPROVAL"
		}
		p := product("prod-2", status)
		p["title"] = b["title"]
		writeJSON(w, 200, p)
	})
	for _, action := range []string{"pause", "resume"} {
		mux.HandleFunc("POST /v1/products/{id}/"+action, func(w http.ResponseWriter, r *http.Request) {
			u.mu.Lock()
			defer u.mu.Unlock()
			st := "PAUSED"
			if action == "resume" {
				st = "LIVE"
			}
			u.seller.listingStatus[r.PathValue("id")] = st
			writeJSON(w, 200, product(r.PathValue("id"), st))
		})
	}
	mux.HandleFunc("GET /v1/product-types/{code}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("code") != "grocery" {
			problem(w, 404, "catalog.product-type-not-found", "Unknown product type", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"code": "grocery", "label": "packaged food", "identifierRule": "GTIN",
			"attributes": []map[string]any{{"key": "brand", "label": "Brand", "required": true}, {"key": "allergens", "label": "Allergens (FALCPA)", "options": []string{"None", "Contains peanuts"}, "required": true}},
			"themes":     []map[string]any{{"name": "Net weight", "dimensions": []map[string]any{{"name": "Net weight", "options": []string{"1 lb", "5 lb"}}}}}})
	})
	mux.HandleFunc("GET /v1/identifiers/gtin/{code}", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"digits": r.PathValue("code"), "valid": true, "message": "Valid GTIN-13"})
	})
	mux.HandleFunc("GET /v1/category-requests/mine", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"id": "cr-1", "reference": "REQ-4821", "path": "Fashion › Women › Bridal aso-ebi sets", "status": "PENDING_REVIEW", "createdAt": "2026-10-08T09:00:00Z"}})
	})
	mux.HandleFunc("POST /v1/category-requests", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]string
		_ = json.NewDecoder(r.Body).Decode(&b)
		writeJSON(w, 201, map[string]any{"id": "cr-2", "reference": "REQ-4822", "path": b["parentPath"] + " › " + b["name"], "status": "PENDING_REVIEW", "createdAt": "2026-10-10T09:00:00Z"})
	})
	// inventory, media, carrier-gateway
	mux.HandleFunc("GET /v1/positions", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"items": []map[string]any{
			{"sku": "OFADA-5LB", "nodeId": "HOU1", "ownerOrg": SellerOrgID, "onHand": 120, "reserved": 8, "available": 112, "inbound": 40},
			{"sku": "OFADA-10LB", "nodeId": "ATL1", "ownerOrg": SellerOrgID, "onHand": 9, "reserved": 2, "available": 7, "inbound": 0}}, "total": 2})
	})
	mux.HandleFunc("POST /v1/uploads", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 201, map[string]any{"asset": map[string]any{"id": "asset-1", "status": "PENDING"}, "upload": map[string]any{"method": "PUT",
			"url": "https://uploads.example/asset-1", "headers": map[string]string{"Content-Type": "image/webp"}, "publicUrl": "https://img.example/asset-1.webp", "expiresAt": "2026-10-10T12:15:00Z"}})
	})
	mux.HandleFunc("POST /v1/assets/{id}/complete", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"id": r.PathValue("id"), "url": "https://img.example/asset-1.webp", "contentType": "image/webp", "bytes": 182000, "width": 1600, "height": 1600, "status": "READY"})
	})
	mux.HandleFunc("POST /v1/rates", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"quotes": []map[string]any{
			{"carrier": "ups", "service": "03", "serviceName": "UPS Ground", "amountMinor": 1189, "currency": "USD", "transitDays": 3},
			{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "serviceName": "USPS Ground Advantage", "amountMinor": 845, "currency": "USD", "transitDays": 4}}, "partial": false})
	})
	mux.HandleFunc("POST /v1/labels", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Idempotency-Key") == "" {
			problem(w, 400, "carrier.idempotency-key-required", "Idempotency-Key required", nil)
			return
		}
		writeJSON(w, 201, map[string]any{"carrier": "usps", "service": "USPS_GROUND_ADVANTAGE", "trackingNumber": "9400111899223344556677", "costMinor": 845,
			"currency": "USD", "status": "purchased", "documentUrl": "https://labels.example/l1.pdf"})
	})
}

func (u *Upstreams) moneyRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/payouts/balance", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"orgId": SellerOrgID, "currency": "USD", "availableMinor": 128455, "onHoldMinor": 52725, "requestedOrInTransitMinor": 0,
			"paidMinor": 932210, "providerFeesMinor": 1500, "minimumPayoutMinor": 2500, "canRequestToday": true,
			"payoutsPaused": true, "payoutsPausedReason": "AUTO_PAST_DUE", "onHold": []map[string]any{{"orderId": "AT-2026-000123", "amountMinor": 52725}}})
	})
	mux.HandleFunc("GET /v1/payouts/provider-fees", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{
			{"id": "fee-1", "orgId": ProviderOrgID, "bookingId": "23970", "bookingReference": "BK-23970", "amountMinor": 1500, "currency": "usd",
				"reason": "Late cancellation by provider", "status": "OUTSTANDING", "attempts": 1, "nextAttemptAt": "2026-10-11T12:00:00Z", "createdAt": "2026-10-05T16:00:00Z"},
			{"id": "fee-0", "orgId": ProviderOrgID, "bookingId": "23900", "bookingReference": "BK-23900", "amountMinor": 1500, "currency": "usd",
				"reason": "Late cancellation by provider", "status": "COLLECTED", "createdAt": "2026-09-20T16:00:00Z"}})
	})
	mux.HandleFunc("GET /v1/payouts/requests", func(w http.ResponseWriter, _ *http.Request) {
		problem(w, 403, "payments.forbidden", "payouts:approve required", nil)
	})
	mux.HandleFunc("POST /v1/payouts/requests", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			AmountMinor int64 `json:"amountMinor"`
			All         bool  `json:"all"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if !b.All && b.AmountMinor < 2500 {
			problem(w, 422, "payments.below-minimum", "The minimum payout is $25.00", map[string]any{"errors": map[string]string{"amountMinor": "At least $25.00"}})
			return
		}
		amount := b.AmountMinor
		if b.All {
			amount = 128455
		}
		writeJSON(w, 201, map[string]any{"id": "po_1", "orgId": SellerOrgID, "amountMinor": amount, "currency": "USD", "status": "REQUESTED", "requestedAt": time.Now().UTC().Format(time.RFC3339)})
	})
}

// ---------------------------------------------------------------- services still being built (SellerPending)

func (u *Upstreams) orderJSON() map[string]any {
	st := u.seller.shipStatus
	next := map[string][]string{"placed": {"confirmed", "cancelled"}, "confirmed": {"packed"}, "packed": {"ready"}}[st]
	return map[string]any{"id": "o1", "orderNumber": "AT-2026-000123", "status": "in_progress", "placedAt": "2026-10-09T15:00:00Z", "currency": "USD", "totalMinor": 5437,
		"shipTo": "Houston, TX", "shippingAddress": map[string]any{"fullName": "Adaeze Nwosu", "phone": "+17135550142", "line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "postalCode": "77042", "country": "US"},
		"shipments": []map[string]any{{"id": "sh-1", "shipmentId": "sh-1", "label": "Shipment 1 of 1 · Seller", "sellerOrgId": SellerOrgID, "fulfilmentType": "SELLER_FULFILLED",
			"deliveryMethod": "CARRIER", "status": st, "items": "Ofada rice ×2", "itemsMinor": 4998, "shippingMinor": 0, "totalMinor": 5437, "carrier": nil, "trackingNumber": u.seller.tracking,
			"nextActions": next, "lines": []map[string]any{{"variantAtin": "ATADEV0001", "title": "Ofada Rice · 5 lb", "quantity": 2, "unitPriceMinor": 2499, "itemsMinor": 4998}},
			"timeline": []map[string]any{{"from": "pending_payment", "to": "placed", "actorType": "SYSTEM", "at": "2026-10-09T15:00:00Z"}}}}}
}

func (u *Upstreams) ordersRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/seller/shipments", func(w http.ResponseWriter, _ *http.Request) {
		u.mu.Lock()
		defer u.mu.Unlock()
		o := u.orderJSON()
		sh := o["shipments"].([]map[string]any)[0]
		writeJSON(w, 200, map[string]any{"items": []any{map[string]any{"orderNumber": o["orderNumber"], "orderStatus": o["status"], "placedAt": o["placedAt"],
			"shipTo": o["shippingAddress"], "shipment": sh}}, "nextCursor": nil, "total": 1})
	})
	mux.HandleFunc("GET /v1/orders/{number}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("number") != "AT-2026-000123" {
			problem(w, 404, "orders.not-found", "No such order", nil)
			return
		}
		u.mu.Lock()
		defer u.mu.Unlock()
		writeJSON(w, 200, u.orderJSON())
	})
	mux.HandleFunc("POST /v1/shipments/{id}/transitions", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			To string `json:"to"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		allowed := map[string]string{"placed": "confirmed", "confirmed": "packed", "packed": "ready"}
		if allowed[u.seller.shipStatus] != b.To {
			problem(w, 409, "orders.invalid-transition", "Can't move from "+u.seller.shipStatus+" to "+b.To, nil)
			return
		}
		u.seller.shipStatus = b.To
		writeJSON(w, 200, map[string]any{"outcome": "applied", "order": u.orderJSON()})
	})
	mux.HandleFunc("POST /v1/shipments/{id}/tracking", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			TrackingNumber string `json:"trackingNumber"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		u.mu.Lock()
		defer u.mu.Unlock()
		u.seller.tracking = b.TrackingNumber
		writeJSON(w, 200, u.orderJSON())
	})
	mux.HandleFunc("GET /v1/seller/analytics", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"currency": "USD", "toShip": 9,
			"days":         []map[string]any{{"date": "2026-10-09", "salesMinor": 190000, "units": 64, "orders": 20}, {"date": "2026-10-10", "salesMinor": 210000, "units": 70, "orders": 22}},
			"topProducts":  []map[string]any{{"title": "Ofada rice 5 lb", "revenueMinor": 624000}},
			"byFulfilment": []map[string]any{{"fulfilment": "AFROTRANSACT", "orders": 412}, {"fulfilment": "SELLER", "orders": 268}}})
	})
}

func (u *Upstreams) bookingsRoutes(mux *http.ServeMux) {
	card := func(id, status string, start time.Time) map[string]any {
		return map[string]any{"id": id, "reference": "BK-" + id, "customerName": "Folake Bello", "serviceName": "Cornrows · 8–10 rows", "startAt": start.Format(time.RFC3339),
			"endAt": start.Add(2 * time.Hour).Format(time.RFC3339), "durationMinutes": 120, "status": status, "priceMinor": 7500, "depositMinor": 2000, "currency": "USD", "depositState": "held"}
	}
	start := time.Date(2026, 10, 12, 15, 0, 0, 0, time.UTC)
	mux.HandleFunc("GET /v1/providers/me/calendar", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"timezone": "America/Chicago", "openHours": []map[string]any{{"day": "TUE", "ranges": []map[string]string{{"start": "10:00", "end": "18:00"}}}},
			"bookings": []any{card("24011", "CONFIRMED", start), card("24018", "REQUESTED", start.Add(24*time.Hour))}})
	})
	mux.HandleFunc("GET /v1/providers/me/bookings", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []any{card("24018", "REQUESTED", start.Add(24*time.Hour))})
	})
	mux.HandleFunc("POST /v1/bookings/{id}/{action}", func(w http.ResponseWriter, r *http.Request) {
		st := map[string]string{"confirm": "CONFIRMED", "decline": "DECLINED", "propose": "RESCHEDULE_PROPOSED", "complete": "COMPLETED", "no-show": "NO_SHOW", "cancel": "CANCELLED_PROVIDER"}[r.PathValue("action")]
		writeJSON(w, 200, card(r.PathValue("id"), st, start.Add(24*time.Hour)))
	})
	avail := map[string]any{"timezone": "America/Chicago", "weekly": []map[string]any{{"day": "MON", "ranges": []any{}}, {"day": "TUE", "ranges": []map[string]string{{"start": "10:00", "end": "18:00"}}}},
		"blockedDates": []any{}, "rules": map[string]any{"leadMinutes": 720, "horizonDays": 30, "bufferMinutes": 30, "stepMinutes": 30, "maxPerDay": 3, "instantBooking": false, "autoAcceptReschedule": true}}
	mux.HandleFunc("GET /v1/providers/me/availability", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, avail) })
	mux.HandleFunc("PUT /v1/providers/me/availability", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		writeJSON(w, 200, b)
	})
	svc := map[string]any{"id": "s1a", "name": "Knotless box braids · medium", "description": "Hair included", "durationMinutes": 300, "priceMinor": 18000, "depositMinor": 4000,
		"currency": "USD", "active": true, "takeHome": map[string]any{"commissionPercent": 10, "commissionMinor": 1800, "cardFeesMinor": 552, "takeHomeMinor": 15648}}
	mux.HandleFunc("GET /v1/providers/me/services", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, []any{svc}) })
	mux.HandleFunc("POST /v1/providers/me/services", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 201, svc) })
	mux.HandleFunc("PATCH /v1/providers/me/services/{id}", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, svc) })
	mux.HandleFunc("GET /v1/providers/me/cancellation-policy", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"kind": "MODERATE"})
	})
	mux.HandleFunc("PUT /v1/providers/me/cancellation-policy", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		_ = json.NewDecoder(r.Body).Decode(&b)
		writeJSON(w, 200, b)
	})
	mux.HandleFunc("GET /v1/providers/me/earnings", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"currency": "USD", "commissionPercent": 10, "heldMinor": 4000, "rows": []map[string]any{
			{"bookingId": "23990", "reference": "BK-23990", "customerName": "Chioma Eze", "serviceName": "Fulani braids", "startAt": "2026-10-08T15:00:00Z", "status": "COMPLETED",
				"collectedMinor": 16000, "commissionMinor": 1600, "cardFeesMinor": 494, "lateCancelFeeMinor": 0},
			{"bookingId": "23970", "reference": "BK-23970", "customerName": "Ama K.", "serviceName": "Cornrows", "startAt": "2026-10-05T15:00:00Z", "status": "CANCELLED_PROVIDER",
				"collectedMinor": 0, "commissionMinor": 0, "cardFeesMinor": 0, "lateCancelFeeMinor": 1500}}})
	})
	mux.HandleFunc("GET /v1/providers/me/analytics", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"days": []map[string]any{{"date": "2026-10-10", "bookings": 5, "depositMinor": 26000}},
			"byService": []map[string]any{{"service": "Box braids", "bookings": 64}}, "byStatus": []map[string]any{{"status": "Completed", "count": 168}},
			"weeks": []map[string]any{{"week": "W1", "paidOutMinor": 240000, "pendingMinor": 60000}}})
	})
}
