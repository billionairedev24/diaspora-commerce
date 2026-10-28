package testkit

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
)

// ShopperPrefix is where the shopper-side fakes (payments wallet, orders customer routes, identity
// security, bookings customer routes, trust-ops claims, storefront pages, ai-gateway, promise batches, carrier pickup/tracking, geo
// zones) are mounted on the fake server, so they never collide with the other fakes' routes.
const ShopperPrefix = "/shopper"

// Shopper fixture ids.
const (
	ShopperOrderNumber = "AT-2026-000777"
	ShopperOrderID     = "0b0b0b0b-0000-4000-8000-000000000777"
	ShopperShipmentID  = "0b0b0b0b-0000-4000-8000-000000000778"
	ShopperLineID      = "0b0b0b0b-0000-4000-8000-000000000779"
	ShopperSellerOrgID = "org_afrotransact_retail"
	ShopperServiceID   = "0c0c0c0c-0000-4000-8000-000000000001"
	ShopperProviderID  = "org_braids"
	ShopperBookingID   = "0c0c0c0c-0000-4000-8000-000000000002"
	// PayLinkOrderNumber is the late pre-order add of the pay-now link fakes; PayLinkToken is its valid token,
	// PayLinkExpiredToken an expired one; any other token is unknown.
	PayLinkOrderNumber  = "AT-2026-PAY001"
	PayLinkToken        = "v1.1893456000.good-signature" //nolint:gosec // a test fixture, not a credential
	PayLinkExpiredToken = "v1.1600000000.expired-signature"
	// ShopperCurrentPassword is the fake account's password for PUT /v1/me/password.
	ShopperCurrentPassword = "Current-Passw0rd!"
)

type shopperState struct {
	mu            sync.Mutex
	defaultPM     string
	claims        []map[string]any
	bookingStatus string
	checkouts     map[string]string // idempotency key → order number
	lastCheckout  map[string]any
	prefs         map[string]any
	account       *accountState
}

func readJSON(r *http.Request) map[string]any {
	var m map[string]any
	_ = json.NewDecoder(r.Body).Decode(&m)
	if m == nil {
		m = map[string]any{}
	}
	return m
}

var fakeGroceryProductTypes = map[string]struct{}{
	"fresh-produce": {}, "pantry": {}, "spices": {}, "grains": {}, "frozen-food": {}, "meat-seafood": {},
}

func fakeQuoteGrocery(ln map[string]any) bool {
	if cat, _ := ln["taxCategory"].(string); strings.EqualFold(cat, "GROCERY") {
		return true
	}
	pt, _ := ln["productType"].(string)
	_, ok := fakeGroceryProductTypes[strings.ToLower(strings.TrimSpace(pt))]
	return ok
}

func (u *Upstreams) shopperRoutes(root *http.ServeMux) {
	st := &shopperState{defaultPM: "pm_visa", bookingStatus: "CONFIRMED", checkouts: map[string]string{},
		prefs:   map[string]any{"email": true, "sms": true, "push": true, "marketingOptIn": false, "locale": "fr-CA", "timeZone": "America/Toronto"},
		account: newAccountState()}
	shopperStates.Store(u, st)
	mux := http.NewServeMux()
	root.Handle(ShopperPrefix+"/", http.StripPrefix(ShopperPrefix, mux))
	u.searchClickRoutes(root)
	authed := func(fn http.HandlerFunc) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if _, ok := bearerSub(w, r); ok {
				fn(w, r)
			}
		}
	}

	// ---- orders: pay-now links (service token; the link token in the body)
	payLinkPaid := false
	payLink := func(w http.ResponseWriter, r *http.Request, confirm bool) {
		if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") {
			problem(w, 401, "unauthenticated", "service token required", nil)
			return
		}
		tok, _ := readJSON(r)["token"].(string)
		switch {
		case r.PathValue("number") != PayLinkOrderNumber || (tok != PayLinkToken && tok != PayLinkExpiredToken):
			problem(w, 404, "orders.pay-link-not-found", "This payment link is not valid.", nil)
			return
		case tok == PayLinkExpiredToken:
			problem(w, 410, "orders.pay-link-expired", "This payment link has expired.", nil)
			return
		}
		st.mu.Lock()
		if confirm {
			payLinkPaid = true
		}
		paid := payLinkPaid
		st.mu.Unlock()
		out := map[string]any{"orderNumber": PayLinkOrderNumber, //nolint:gosec // test fixture
			"status": "AWAITING_PAYMENT", "totalMinor": 4297,
			"deliveryFeeMinor": 799, "currency": "USD", "clientSecret": "pi_paylink_secret_test",
			"expiresAt": "2030-01-01T00:00:00Z", "windowName": "Lubbock Harvest", "deliversOn": "2026-10-24", "fulfilment": "DOOR",
			"lines": []map[string]any{{"title": "Egusi seeds", "quantity": 2, "totalMinor": 3498}}}
		if paid {
			out["status"] = "PAID"
			out["clientSecret"] = nil
		}
		writeJSON(w, 200, out)
	}
	mux.HandleFunc("POST /v1/service/pay-links/{number}/lookup", func(w http.ResponseWriter, r *http.Request) { payLink(w, r, false) })
	mux.HandleFunc("POST /v1/service/pay-links/{number}/confirm", func(w http.ResponseWriter, r *http.Request) { payLink(w, r, true) })

	// ---- payments
	mux.HandleFunc("GET /v1/customers/me/payment-methods", customerOnly("payments.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		writeJSON(w, 200, []map[string]any{
			{"id": "pm_visa", "type": "card", "brand": "visa", "last4": "4242", "expMonth": 8, "expYear": 2029, "isDefault": st.defaultPM == "pm_visa"},
			{"id": "pm_bank", "type": "us_bank_account", "bankName": "Chase", "accountType": "checking", "last4": "6789", "verified": false, "isDefault": st.defaultPM == "pm_bank"},
		})
	}))
	mux.HandleFunc("POST /v1/customers/me/setup-intents", customerOnly("payments.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 201, map[string]any{"setupIntentId": "seti_1", "clientSecret": "seti_1_secret_abc", "status": "requires_payment_method"})
	}))
	mux.HandleFunc("POST /v1/customers/me/payment-methods/{id}/default", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")
		if id != "pm_visa" && id != "pm_bank" {
			problem(w, 404, "payments.method-not-found", "No such payment method", nil)
			return
		}
		st.mu.Lock()
		st.defaultPM = id
		st.mu.Unlock()
		writeJSON(w, 200, map[string]any{"id": id, "type": "card", "brand": "visa", "last4": "4242", "expMonth": 8, "expYear": 2029, "isDefault": true})
	}))
	mux.HandleFunc("DELETE /v1/customers/me/payment-methods/{id}", customerOnly("payments.customer-only", func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		if r.PathValue("id") == st.defaultPM {
			problem(w, 409, "payments.default-method-required", "Choose another default first", nil)
			return
		}
		w.WriteHeader(204)
	}))

	u.creditRoutes(mux)
	u.referralRoutes(mux)

	mux.HandleFunc("GET /v1/payments", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("orderId") != ShopperOrderID {
			problem(w, 404, "payments.not-found", "No payment for this order", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"status": "CAPTURED", "amountMinor": 2706, "capturedMinor": 2706, "refundedMinor": 1353, "currency": "USD", "creditMinor": 500, "cardRefundsAsCredit": false,
			"lines": []map[string]any{{"lineId": ShopperLineID, "itemsMinor": 2500, "shippingMinor": 0, "taxMinor": 206, "shippingTaxMinor": 0,
				"refundedMinor": 1353, "refundableMinor": 1353}}})
	}))

	// ---- orders
	order := func(status string, cancellable bool) map[string]any {
		return map[string]any{"id": ShopperOrderID, "orderNumber": ShopperOrderNumber, "status": "COMPLETED", "placedAt": "2026-10-01T15:00:00Z",
			"currency": "USD", "itemsMinor": 2500, "shippingMinor": 0, "taxMinor": 206, "discountMinor": 0, "totalMinor": 2706, "shipTo": "9800 Westheimer Rd, Houston",
			"shippingAddress": map[string]any{"fullName": "Adaeze Nwosu", "phone": "+17135550142", "line1": "9800 Westheimer Rd", "city": "Houston", "state": "TX", "postalCode": "77042", "country": "US"},
			"shipments": []map[string]any{{"id": ShopperShipmentID, "shipmentId": ShopperShipmentID, "label": "Shipment 1 of 1", "sellerOrgId": ShopperSellerOrgID, "sellerName": "AfroTransact Retail",
				"fulfilmentType": "RETAIL_1P", "deliveryMethod": "CARRIER", "serviceLevel": "standard", "status": status, "items": "Jollof Seasoning × 2",
				"carrier": "ups", "trackingNumber": "1Z999", "promisedBy": "2026-10-03", "deliveredAt": "2026-10-03T17:00:00Z", "cancellable": cancellable,
				"preferences": map[string]any{"handover": "DOOR"}, "perishable": false,
				"returnable": true, "returnWindowEndsAt": "2026-11-02T17:00:00Z", "problemReportOnly": false,
				"deliveryFeeRule": map[string]any{"method": "CARRIER", "ruleId": "7f0c6d4e-1c1e-4f43-9c1e-0a0f5f1d2a11", "ruleVersion": 4, "currency": "USD",
					"amountMinor": 0, "source": "RULE", "band": "free over 35.00"},
				"lines":    []map[string]any{{"id": ShopperLineID, "variantAtin": "ATJOLLOFV1", "title": "Jollof Seasoning", "quantity": 2, "unitPriceMinor": 1250, "itemsMinor": 2500}},
				"timeline": []map[string]any{{"from": "pending_payment", "to": "placed", "at": "2026-10-01T15:00:00Z"}, {"from": "out_for_delivery", "to": "delivered", "at": "2026-10-03T17:00:00Z"}}}}}
	}
	mux.HandleFunc("GET /v1/orders", customerOnly("orders.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"items": []any{order("delivered", false)}, "nextCursor": ""})
	}))
	mux.HandleFunc("GET /v1/orders/{number}", authed(func(w http.ResponseWriter, r *http.Request) {
		switch r.PathValue("number") {
		case ShopperOrderNumber:
			writeJSON(w, 200, order("delivered", false))
		case CreditOnlyOrderNumber: // paid with credit only
			o := order("delivered", false)
			o["id"], o["orderNumber"] = CreditOnlyOrderID, CreditOnlyOrderNumber
			writeJSON(w, 200, o)
		default:
			problem(w, 404, "orders.not-found", "No such order", nil)
		}
	}))
	mux.HandleFunc("POST /v1/orders/{number}/shipments/{id}/cancel", customerOnly("orders.customer-only", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("number") != ShopperOrderNumber {
			problem(w, 404, "orders.not-found", "No such order", nil)
			return
		}
		problem(w, 409, "orders.cancel-window-closed", "Already delivered", nil)
	}))
	checkoutView := func(number, status string) map[string]any {
		return map[string]any{"orderId": "o-new", "orderNumber": number, "status": status, "clientSecret": "pi_1_secret_xyz",
			"paymentClientSecret": "pi_1_secret_xyz", "totalMinor": 1250, "currency": "USD",
			"shipments": []map[string]any{{"shipmentId": "sh-new", "label": "Shipment 1 of 1", "eta": "Tomorrow", "deliveryMethod": "CARRIER"}}}
	}
	mux.HandleFunc("POST /v1/checkout", customerOnly("orders.customer-only", func(w http.ResponseWriter, r *http.Request) {
		key := r.Header.Get("Idempotency-Key")
		if key == "" {
			problem(w, 400, "orders.idempotency-key-required", "Idempotency-Key required", nil)
			return
		}
		body := readJSON(r)
		st.mu.Lock()
		st.lastCheckout = body
		n, ok := st.checkouts[key]
		if !ok {
			n = fmt.Sprintf("AT-2026-%06d", 900+len(st.checkouts))
			st.checkouts[key] = n
		}
		st.mu.Unlock()
		view := checkoutView(n, "awaiting_payment")
		if body["useCredit"] == true { // S320: credit pays the whole order, so there is no card to confirm
			view["clientSecret"], view["paymentClientSecret"], view["creditMinor"] = nil, nil, 1250
		}
		writeJSON(w, 201, view)
	}))
	mux.HandleFunc("GET /v1/checkout/{number}", authed(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, checkoutView(r.PathValue("number"), "awaiting_payment"))
	}))
	mux.HandleFunc("POST /v1/checkout/{number}/confirm", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("number") == "AT-2026-DECLINE" {
			problem(w, 402, "orders.payment-failed", "Card declined", nil)
			return
		}
		writeJSON(w, 200, checkoutView(r.PathValue("number"), "placed"))
	}))

	u.shopperClaimRoutes(mux, st, authed)

	mux.HandleFunc("POST /v1/tax/rates/import", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		csv, _ := b["csv"].(string)
		if strings.TrimSpace(csv) == "" {
			problem(w, 422, "tax-returns.import-empty", "The CSV is empty", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"statesUpserted": 1, "localsUpserted": strings.Count(csv, "\n") - 1, "warnings": []any{}})
	}))
	mux.HandleFunc("POST /v1/tax/quotes", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		shipTo, _ := b["shipTo"].(map[string]any)
		state, _ := shipTo["state"].(string)
		lines, _ := b["lines"].([]any)
		combinedRate := 0.0
		if state == "TX" {
			combinedRate = 0.0825
		}
		var totalTax int64
		outLines := []map[string]any{}
		for _, raw := range lines {
			ln, _ := raw.(map[string]any)
			id, _ := ln["lineId"].(string)
			ship, _ := ln["shippingMinor"].(float64)
			qty, _ := ln["quantity"].(float64)
			unit, _ := ln["unitPriceMinor"].(float64)
			merch := int64(unit * qty)
			if fakeQuoteGrocery(ln) {
				merch = 0
			}
			base := merch + int64(ship)
			tax := int64(float64(base)*combinedRate + 0.5)
			totalTax += tax
			outLines = append(outLines, map[string]any{"lineId": id, "taxMinor": tax, "rate": combinedRate, "jurisdiction": state, "shippingTaxMinor": 0})
		}
		writeJSON(w, 200, map[string]any{"currency": "USD", "jurisdiction": state, "totalTaxMinor": totalTax, "lines": outLines, "provider": "rules"})
	}))

	u.shopperIdentityRoutes(mux, st, authed)
	u.shopperAccountRoutes(mux, st, authed)
	u.shopperBookingRoutes(mux, st, authed)
	u.shopperDeliveryRoutes(mux, authed)
}

func (u *Upstreams) shopperIdentityRoutes(mux *http.ServeMux, st *shopperState, authed func(http.HandlerFunc) http.HandlerFunc) {
	recovery := map[string]any{"recoveryCodes": []string{"aaaa-bbbb", "cccc-dddd"}}
	code := func(w http.ResponseWriter, r *http.Request) bool {
		if readJSON(r)["code"] != "123456" {
			problem(w, 422, "identity.invalid-code", "That code is incorrect", nil)
			return false
		}
		return true
	}
	noContent := func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) }
	mux.HandleFunc("GET /v1/me/mfa", authed(func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		staff := st.account.principalType == "STAFF"
		st.mu.Unlock()
		writeJSON(w, 200, map[string]any{"totp": true, "sms": false, "smsPhone": "•••• 0142", "recoveryCodesLeft": 8, "passkeys": 1, "required": staff, "mayDisable": !staff,
			"mayRemoveTotp": !staff, "mayRemoveSms": false})
	}))
	mux.HandleFunc("GET /v1/me/passkeys", authed(func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"credentialId": "cred-1", "label": "iPhone", "createdAt": "2026-09-01T10:00:00Z"}})
	}))
	mux.HandleFunc("GET /v1/me/sessions", authed(func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"id": "s1", "clientId": "gateway-bff", "clientName": "AfroTransact web", "signedInAt": "2026-10-09T10:00:00Z", "current": true},
			{"id": "s2", "clientId": "customer-app", "clientName": "AfroTransact app", "signedInAt": "2026-10-01T10:00:00Z", "current": false}})
	}))
	mux.HandleFunc("GET /v1/me/devices", authed(func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"id": "d1", "app": "customer", "platform": "ios", "deviceName": "Ada's iPhone", "lastSeenAt": "2026-10-09T09:00:00Z"}})
	}))
	linked := map[string]any{"accounts": []map[string]any{{"provider": "google", "email": "ada@example.com", "canUnlink": true}}, "hasPassword": true}
	mux.HandleFunc("GET /v1/me/linked-accounts", authed(func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, linked) }))
	mux.HandleFunc("DELETE /v1/me/linked-accounts/{provider}", authed(func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"accounts": []any{}, "hasPassword": true})
	}))
	mux.HandleFunc("PUT /v1/me/password", authed(func(w http.ResponseWriter, r *http.Request) {
		if readJSON(r)["currentPassword"] != ShopperCurrentPassword {
			problem(w, 422, "identity.invalid-current-password", "Your current password is incorrect", nil)
			return
		}
		w.WriteHeader(204)
	}))
	mux.HandleFunc("POST /v1/me/mfa/totp", authed(func(w http.ResponseWriter, _ *http.Request) {
		//nolint:gosec // a fake TOTP secret
		writeJSON(w, 200, map[string]any{"secret": "JBSWY3DPEHPK3PXP", "otpauthUri": "otpauth://totp/AfroTransact:ada@example.com?secret=JBSWY3DPEHPK3PXP"})
	}))
	mux.HandleFunc("POST /v1/me/mfa/totp/confirm", authed(func(w http.ResponseWriter, r *http.Request) {
		if code(w, r) {
			writeJSON(w, 200, recovery)
		}
	}))
	mux.HandleFunc("POST /v1/me/mfa/sms", authed(func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 202, map[string]any{"codeExpiresAt": "2026-10-10T10:10:00Z"})
	}))
	mux.HandleFunc("POST /v1/me/mfa/sms/confirm", authed(func(w http.ResponseWriter, r *http.Request) {
		if code(w, r) {
			writeJSON(w, 200, recovery)
		}
	}))
	mux.HandleFunc("POST /v1/me/mfa/recovery-codes", authed(func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, recovery) }))
	mux.HandleFunc("DELETE /v1/me/mfa/totp", authed(noContent))
	mux.HandleFunc("DELETE /v1/me/mfa/sms", authed(noContent))
	mux.HandleFunc("DELETE /v1/me/passkeys/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") == "last" {
			problem(w, 409, "identity.last-sign-in-method", "Keep one way to sign in", nil)
			return
		}
		w.WriteHeader(204)
	}))
	mux.HandleFunc("DELETE /v1/me/sessions/{id}", authed(noContent))
	mux.HandleFunc("POST /v1/me/sessions/revoke-others", authed(func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, map[string]any{"revoked": 1}) }))
	mux.HandleFunc("DELETE /v1/me/devices/{id}", authed(noContent))
	mux.HandleFunc("GET /v1/me/notification-preferences", authed(func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		writeJSON(w, 200, st.prefs)
	}))
	mux.HandleFunc("PUT /v1/me/notification-preferences", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		st.mu.Lock()
		defer st.mu.Unlock()
		for _, k := range []string{"email", "sms", "push", "marketingOptIn"} {
			if v, ok := b[k].(bool); ok {
				st.prefs[k] = v
			}
		}
		// Like identity, the PUT replaces the whole row: a missing language or time zone clears it.
		st.prefs["locale"], st.prefs["timeZone"] = b["locale"], b["timeZone"]
		writeJSON(w, 200, st.prefs)
	}))
}

func (u *Upstreams) shopperBookingRoutes(mux *http.ServeMux, st *shopperState, authed func(http.HandlerFunc) http.HandlerFunc) {
	policy := map[string]any{"kind": "MODERATE", "label": "Moderate", "text": "Free cancellation until 48 h before."}
	service := map[string]any{"id": ShopperServiceID, "orgId": ShopperProviderID, "providerName": "Braids by Ama", "name": "Knotless braids",
		"description": "Mid-back knotless braids", "category": "hair", "durationMinutes": 240, "priceMinor": 18000, "depositMinor": 3000, "balanceMinor": 15000,
		"currency": "USD", "modes": []string{"IN_PERSON"}, "addOns": []map[string]any{{"id": "wash", "name": "Wash", "priceMinor": 1500, "extraMinutes": 30}},
		"policy": policy, "instantBooking": false, "team": []map[string]any{{"id": "0c0c0c0c-0000-4000-8000-0000000000aa", "displayName": "Ama"}}}
	booking := func(status string) map[string]any {
		return map[string]any{"id": ShopperBookingID, "reference": "BK-7Q2X", "orgId": ShopperProviderID, "status": status, "statusLabel": "Confirmed",
			"startAt": "2026-10-20T15:00:00Z", "endAt": "2026-10-20T19:00:00Z", "location": "Houston, TX", "notes": "",
			"service": map[string]any{"serviceId": ShopperServiceID, "name": "Knotless braids", "durationMinutes": 240, "priceMinor": 18000, "depositMinor": 3000,
				"balanceMinor": 15000, "currency": "USD", "addOns": []string{}, "mode": "IN_PERSON", "providerName": "Braids by Ama", "memberName": "Ama"},
			"policy":  policy,
			"payment": map[string]any{"state": "DEPOSIT_CAPTURED", "depositCapturedMinor": 3000, "refundedMinor": 0, "balance": map[string]any{"state": "ON_AUTHORISATION"}}}
	}
	mux.HandleFunc("GET /v1/public/services/search", func(w http.ResponseWriter, r *http.Request) {
		if z := r.URL.Query().Get("zip"); z != "" && len(z) != 5 {
			problem(w, 422, "bookings.invalid-zip", "Invalid ZIP", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"services": []map[string]any{{"serviceId": ShopperServiceID, "orgId": ShopperProviderID, "name": "Knotless braids",
			"providerName": "Braids by Ama", "description": "Mid-back knotless braids", "category": "hair", "durationMinutes": 240, "priceFromMinor": 18000,
			"depositMinor": 3000, "currency": "USD", "modes": []string{"IN_PERSON"}, "rating": 4.8, "ratingCount": 52, "nextAvailable": "Tomorrow 10:00", "location": "Houston, TX"}}})
	})
	mux.HandleFunc("GET /v1/public/services/{id}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != ShopperServiceID {
			problem(w, 404, "bookings.service-not-found", "No such service", nil)
			return
		}
		writeJSON(w, 200, service)
	})
	mux.HandleFunc("GET /v1/public/services/{id}/days", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"date": "2026-10-20", "slots": 3}, {"date": "2026-10-21", "slots": 0}})
	})
	mux.HandleFunc("GET /v1/public/services/{id}/slots", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"start": "2026-10-20T15:00:00Z", "end": "2026-10-20T19:00:00Z", "memberIds": []string{"0c0c0c0c-0000-4000-8000-0000000000aa"}}})
	})
	mux.HandleFunc("GET /v1/public/services/{id}/summary", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"serviceId": ShopperServiceID, "serviceName": "Knotless braids", "providerName": "Braids by Ama", "durationMinutes": 240,
			"priceMinor": 18000, "depositNowMinor": 3000, "balanceLaterMinor": 15000, "currency": "USD", "mode": "IN_PERSON", "addOns": []string{},
			"policy": "MODERATE", "policyText": "Free cancellation until 48 h before.", "instantBooking": false, "providerAnswersWithinMinutes": 120})
	})
	mux.HandleFunc("GET /v1/public/providers/{org}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("org") != ShopperProviderID {
			problem(w, 404, "bookings.provider-not-found", "No such provider", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"orgId": ShopperProviderID, "displayName": "Braids by Ama", "timeZone": "America/Chicago", "location": "Houston, TX",
			"policy": policy, "instantBooking": false, "services": []any{service}, "team": []map[string]any{{"id": "0c0c0c0c-0000-4000-8000-0000000000aa", "displayName": "Ama"}}})
	})
	mux.HandleFunc("GET /v1/public/business-pages/{slug}", func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("slug") == "ades-kitchen" {
			writeJSON(w, 200, map[string]any{"orgId": SellerOrgID, "slug": "ades-kitchen", "kind": "SELLER", "name": "Ade's Kitchen",
				"description": "Home-style Nigerian pantry.", "brandColor": nil, "logoUrl": nil, "contactEmail": nil, "approved": false})
			return
		}
		if r.PathValue("slug") != "braids-by-ama" {
			problem(w, 404, "seller.business-page-not-found", "No page", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"orgId": ShopperProviderID, "slug": "braids-by-ama", "kind": "PROVIDER", "name": "Braids by Ama",
			"description": "Knotless braids and protective styles in Houston.", "brandColor": "#8A3B12", "logoUrl": nil, "contactEmail": "ama@example.com", "approved": true})
	})
	mux.HandleFunc("POST /v1/bookings", customerOnly("bookings.customer-only", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Idempotency-Key") == "" {
			problem(w, 400, "bookings.idempotency-key-required", "Idempotency-Key required", nil)
			return
		}
		b := readJSON(r)
		if b["startAt"] == "2026-10-20T11:00:00Z" {
			problem(w, 409, "bookings.slot-taken", "That time was just taken", nil)
			return
		}
		bk := booking("PENDING_PAYMENT")
		bk["payment"] = map[string]any{"state": "REQUIRES_PAYMENT_METHOD", "clientSecret": "pi_bk_secret_123"}
		writeJSON(w, 200, map[string]any{"booking": bk, "replayed": false})
	}))
	mux.HandleFunc("GET /v1/bookings/mine", customerOnly("bookings.customer-only", func(w http.ResponseWriter, _ *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		writeJSON(w, 200, []any{booking(st.bookingStatus)})
	}))
	mux.HandleFunc("GET /v1/bookings/{id}", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.PathValue("id") != ShopperBookingID {
			problem(w, 404, "bookings.not-found", "No such booking", nil)
			return
		}
		st.mu.Lock()
		defer st.mu.Unlock()
		writeJSON(w, 200, booking(st.bookingStatus))
	}))
	quote := map[string]any{"by": "CUSTOMER", "refundSharePercent": 100, "depositMinor": 3000, "refundMinor": 3000, "releaseAuthorisation": true,
		"releasedMinor": 15000, "keptMinor": 0, "providerFeeMinor": 0, "policy": "MODERATE", "explanation": "Free cancellation: more than 48 h before.", "currency": "USD"}
	mux.HandleFunc("GET /v1/bookings/{id}/cancellation-quote", authed(func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, quote) }))
	mux.HandleFunc("POST /v1/bookings/{id}/cancel", authed(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Idempotency-Key") == "" {
			problem(w, 400, "bookings.idempotency-key-required", "Idempotency-Key required", nil)
			return
		}
		st.mu.Lock()
		st.bookingStatus = "CANCELLED_CUSTOMER"
		st.mu.Unlock()
		writeJSON(w, 200, map[string]any{"booking": booking("CANCELLED_CUSTOMER"), "applied": quote})
	}))
	mux.HandleFunc("POST /v1/bookings/{id}/reschedule", authed(func(w http.ResponseWriter, _ *http.Request) {
		bk := booking("RESCHEDULE_REQUESTED")
		bk["proposedStartAt"] = "2026-10-22T15:00:00Z"
		writeJSON(w, 200, bk)
	}))
	mux.HandleFunc("POST /v1/bookings/{id}/new-time/{decision}", authed(func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, booking("CONFIRMED")) }))
	mux.HandleFunc("POST /v1/bookings/{id}/payment/sync", authed(func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, booking("REQUESTED")) }))
	mux.HandleFunc("POST /v1/bookings/{id}/review", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		writeJSON(w, 200, map[string]any{"bookingId": r.PathValue("id"), "serviceId": ShopperServiceID, "orgId": ShopperProviderID, "rating": b["rating"], "text": b["text"]})
	}))
}

func (u *Upstreams) shopperDeliveryRoutes(mux *http.ServeMux, authed func(http.HandlerFunc) http.HandlerFunc) {
	mux.HandleFunc("POST /v1/promises", func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			Offers []struct {
				OfferID string         `json:"offerId"`
				Method  map[string]any `json:"method"`
			} `json:"offers"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		out := []map[string]any{}
		for _, o := range b.Offers {
			out = append(out, map[string]any{"offerId": o.OfferID, "available": true, "earliestDate": "2026-10-12", "latestDate": "2026-10-13",
				"label": "Mon, Oct 12 – Tue, Oct 13", "cutoffLabel": "Order within 3 h"})
		}
		writeJSON(w, 200, map[string]any{"promises": out})
	})
	mux.HandleFunc("GET /v1/pickup-locations", func(w http.ResponseWriter, r *http.Request) {
		items := []map[string]any{}
		if r.URL.Query().Get("ownerOrg") == "" {
			items = append(items,
				map[string]any{"id": "pl-hou", "kind": "PLATFORM_COUNTER", "name": "AfroTransact Houston counter", "timezone": "America/Chicago",
					"address":  map[string]any{"line1": "1 Fulfilment Way", "city": "Houston", "state": "TX", "postalCode": "77042", "country": "US"},
					"position": map[string]any{"lat": 29.76, "lng": -95.37}, "hours": []map[string]any{{"weekday": 1, "open": "09:00", "close": "18:00"}},
					"readyHours": 4, "active": true},
				map[string]any{"id": "pl-dal", "kind": "PLATFORM_COUNTER", "name": "Dallas FC — Will Call", "timezone": "America/Chicago",
					"address":  map[string]any{"line1": "5000 Singleton Blvd", "city": "Dallas", "state": "TX", "postalCode": "75212", "country": "US"},
					"position": map[string]any{"lat": 32.776, "lng": -96.822}, "hours": []map[string]any{{"weekday": 1, "open": "08:00", "close": "20:00"}},
					"readyHours": 2, "active": true})
			prefix := r.URL.Query().Get("postalPrefix")
			if prefix != "" {
				filtered := items[:0]
				for _, it := range items {
					addr, _ := it["address"].(map[string]any)
					pc, _ := addr["postalCode"].(string)
					if strings.HasPrefix(pc, prefix) {
						filtered = append(filtered, it)
					}
				}
				items = filtered
			}
		}
		writeJSON(w, 200, map[string]any{"items": items})
	})
	mux.HandleFunc("GET /v1/zones/covering", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, map[string]any{"zones": []map[string]any{{"type": "DELIVERY", "ownerId": "fc_houston_tx", "name": "Houston metro"}}})
	})
	mux.HandleFunc("GET /v1/tracking/{carrier}/{number}", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer svc-shipping:rate" {
			problem(w, 403, "shipping.forbidden", "shipping:rate required", nil)
			return
		}
		writeJSON(w, 200, map[string]any{"live": false, "events": []map[string]any{{"description": "Departed facility", "state": "IN_TRANSIT",
			"occurredAt": "2026-10-02T08:00:00Z", "location": "Houston, TX"}}})
	})
	// ai-gateway: one Lola turn as SSE (token by token), and the stored conversation.
	mux.HandleFunc("POST /v1/lola/chat", authed(func(w http.ResponseWriter, r *http.Request) {
		b := readJSON(r)
		if b["message"] == "budget" {
			w.Header().Set("Retry-After", "3600")
			problem(w, 429, "ai.budget-exceeded", "Daily budget reached", nil)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(200)
		f, _ := w.(http.Flusher)
		send := func(event string, data any) {
			raw, _ := json.Marshal(data)
			_, _ = io.WriteString(w, "event:"+event+"\ndata:"+string(raw)+"\n\n")
			if f != nil {
				f.Flush()
			}
		}
		send("tool_call", map[string]any{"id": "t1", "tool": "search_products", "label": "Searching for jollof", "arguments": map[string]any{}})
		send("tool_result", map[string]any{"id": "t1", "tool": "search_products", "kind": "products", "data": map[string]any{"products": []any{}}})
		send("action_proposal", map[string]any{"proposal": map[string]any{"id": "p1", "type": "ADD_TO_CART", "label": "Add 1 × Jollof Seasoning to cart",
			"payload": map[string]any{"lines": []map[string]any{{"variantAtin": "ATJOLLOFV1", "quantity": 1}}}, "requiresConfirmation": true}})
		for _, t := range strings.Fields("Here is a jollof seasoning ") {
			send("token", map[string]any{"text": t + " "})
		}
		send("done", map[string]any{"sessionId": "lola_abcdef123456", "message": "Here is a jollof seasoning", "products": []any{}, "proposals": []any{},
			"suggestions": []string{"Jollof for 20"}, "dropped": []any{}, "models": []string{"m"}, "promptTokens": 1, "completionTokens": 1,
			"zip": b["zip"]})
	}))
	mux.HandleFunc("GET /v1/lola/sessions/{id}/messages", authed(func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, []map[string]any{{"role": "user", "content": "Jollof", "products": []any{}, "at": "2026-10-10T10:00:00Z"},
			{"role": "assistant", "content": "Here you go", "products": []any{}, "at": "2026-10-10T10:00:02Z"}})
	}))
}

var shopperStates sync.Map // *Upstreams → *shopperState

// LastCheckout is the last body orders' fake checkout received (tests assert the mapping).
func (u *Upstreams) LastCheckout() map[string]any {
	v, ok := shopperStates.Load(u)
	if !ok {
		return nil
	}
	st, _ := v.(*shopperState)
	st.mu.Lock()
	defer st.mu.Unlock()
	return st.lastCheckout
}
