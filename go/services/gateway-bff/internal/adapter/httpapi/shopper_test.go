package httpapi_test

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/getkin/kin-openapi/openapi3filter"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/testkit"
)

// validateAll checks every response against the contract and counts validated operations.
func validateAll(t *testing.T, b *browser) map[string]int {
	t.Helper()
	router := loadRouter(t)
	validated := map[string]int{}
	b.onResponse = func(req *http.Request, rec *httptest.ResponseRecorder) {
		route, params, err := router.FindRoute(req)
		if err != nil {
			return
		}
		if route.Operation.Responses.Status(rec.Code) == nil {
			t.Errorf("%s %s answered %d, which the contract does not document: %s", req.Method, req.URL.Path, rec.Code, rec.Body.Bytes())
			return
		}
		in := &openapi3filter.ResponseValidationInput{
			RequestValidationInput: &openapi3filter.RequestValidationInput{Request: req, PathParams: params, Route: route,
				Options: &openapi3filter.Options{AuthenticationFunc: openapi3filter.NoopAuthenticationFunc}},
			Status: rec.Code, Header: rec.Header(), Body: io.NopCloser(bytes.NewReader(rec.Body.Bytes())),
			Options: &openapi3filter.Options{IncludeResponseStatus: true, MultiError: true},
		}
		if strings.HasPrefix(rec.Header().Get("Content-Type"), "text/event-stream") {
			validated[route.Operation.OperationID]++ // the stream body is relayed verbatim
			return
		}
		if err := openapi3filter.ValidateResponse(context.Background(), in); err != nil {
			t.Errorf("%s %s → %d does not match the contract: %v\nbody: %s", req.Method, req.URL.Path, rec.Code, err, rec.Body.Bytes())
			return
		}
		validated[route.Operation.OperationID]++
	}
	return validated
}

func TestShopperRoutesConformAndMapUpstreams(t *testing.T) {
	bff, b := setup(t, testkit.Options{Content: true, Payments: true, Promise: true, Offers: true, Notifications: true})
	validated := validateAll(t, b)
	u := bff.Upstreams

	// Guests: public directory works, account routes need sign-in.
	r := b.do("GET", "/v1/services/search?q=braids", nil)
	mustStatus(t, r, 200)
	if got := r.json(t)["zip"]; got != "77042" {
		t.Fatalf("search should default to the deliver-to ZIP, got %v", got)
	}
	mustStatus(t, b.do("GET", "/v1/services/search?zip=7704", nil), 422)
	mustStatus(t, b.do("GET", "/v1/services/"+testkit.ShopperServiceID, nil), 200)
	mustStatus(t, b.do("GET", "/v1/services/nope", nil), 404)
	r = b.do("GET", "/v1/services/"+testkit.ShopperServiceID+"/days?days=14", nil)
	mustStatus(t, r, 200)
	mustStatus(t, b.do("GET", "/v1/services/"+testkit.ShopperServiceID+"/slots", nil), 422)
	mustStatus(t, b.do("GET", "/v1/services/"+testkit.ShopperServiceID+"/slots?date=2026-10-20", nil), 200)
	mustStatus(t, b.do("GET", "/v1/services/"+testkit.ShopperServiceID+"/summary?mode=IN_PERSON", nil), 200)
	r = b.do("GET", "/v1/service-providers/"+testkit.ShopperProviderID, nil)
	mustStatus(t, r, 200)
	design, _ := r.json(t)["design"].(map[string]any)
	if design == nil || design["layout"] != "GALLERY_FIRST" || len(design["social"].([]any)) != 1 {
		t.Fatalf("provider page should carry the published layout and only approved links: %v", design)
	}
	mustStatus(t, b.do("GET", "/v1/service-providers/org_nope", nil), 404)
	// The plain public page of a business (what Stripe is given as its website): public, with a provider's services.
	r = b.do("GET", "/v1/business-pages/braids-by-ama", nil)
	mustStatus(t, r, 200)
	page := r.json(t)
	if page["name"] != "Braids by Ama" || page["brandColor"] != "#8a3b12" || page["indexable"] != true || len(page["services"].([]any)) != 1 {
		t.Fatalf("business page = %v", page)
	}
	mustStatus(t, b.do("GET", "/v1/business-pages/nobody", nil), 404)
	mustStatus(t, b.do("GET", "/v1/business-pages/Not%20A%20Slug", nil), 404)
	mustStatus(t, b.do("GET", "/v1/account/security", nil), 401)
	mustStatus(t, b.do("POST", "/v1/lola/chat", map[string]any{"message": "hi"}), 401)

	mustStatus(t, signIn(t, bff, b, "/"), 302)

	// Payments.
	r = b.do("GET", "/v1/account/payment-methods", nil)
	mustStatus(t, r, 200)
	items := r.json(t)["items"].([]any)
	if first := items[0].(map[string]any); first["label"] != "Visa ending in 4242" || first["expires"] != "08/29" {
		t.Fatalf("card mapping: %v", first)
	}
	if second := items[1].(map[string]any); !strings.Contains(second["label"].(string), "Chase checking ending in 6789") {
		t.Fatalf("bank mapping: %v", second)
	}
	r = b.do("POST", "/v1/account/payment-methods/setup-intents", map[string]any{"types": []string{"card", "us_bank_account"}})
	mustStatus(t, r, 201)
	if r.json(t)["publishableKey"] != testkit.TestPublishableKey {
		t.Fatal("setup intent needs the publishable key")
	}
	mustStatus(t, b.do("POST", "/v1/account/payment-methods/setup-intents", map[string]any{"types": []string{"bitcoin"}}), 422)
	mustStatus(t, b.do("DELETE", "/v1/account/payment-methods/pm_visa", nil), 409)
	mustStatus(t, b.do("POST", "/v1/account/payment-methods/pm_bank/default", nil), 200)
	mustStatus(t, b.do("DELETE", "/v1/account/payment-methods/pm_visa", nil), 204)
	mustStatus(t, b.do("POST", "/v1/account/payment-methods/pm_nope/default", nil), 404)
	mustStatus(t, b.do("POST", "/v1/account/payment-sheet", nil), 503) // payments has no ephemeral keys yet

	// Orders, tracking, cancel.
	r = b.do("GET", "/v1/account/orders", nil)
	mustStatus(t, r, 200)
	o := r.json(t)["items"].([]any)[0].(map[string]any)
	if o["orderNumber"] != testkit.ShopperOrderNumber || r.json(t)["nextCursor"] != nil {
		t.Fatalf("orders list: %s", r.body)
	}
	r = b.do("GET", "/v1/account/orders/"+testkit.ShopperOrderNumber, nil)
	mustStatus(t, r, 200)
	sh := r.json(t)["shipments"].([]any)[0].(map[string]any)
	if sh["statusLabel"] != "Delivered" || sh["trackingNumber"] != "1Z999" || len(sh["timeline"].([]any)) != 2 {
		t.Fatalf("order detail: %v", sh)
	}
	if sh["returnable"] != true || sh["returnWindowEndsAt"] != "2026-11-02T17:00:00Z" || sh["problemReportOnly"] != false {
		t.Fatalf("order detail should carry the return window: %v", sh)
	}
	if line := sh["lines"].([]any)[0].(map[string]any); line["refundedMinor"] != float64(1353) {
		t.Fatalf("line refunds from payments: %v", line)
	}
	if pay, _ := r.json(t)["payment"].(map[string]any); pay == nil || pay["refundableMinor"] != float64(1353) || pay["refundedMinor"] != float64(1353) {
		t.Fatalf("order payment: %v", r.json(t)["payment"])
	}
	mustStatus(t, b.do("GET", "/v1/account/orders/AT-2026-NOPE", nil), 404)
	r = b.do("GET", "/v1/account/orders/"+testkit.ShopperOrderNumber+"/tracking", nil)
	mustStatus(t, r, 200)
	tr := r.json(t)["shipments"].([]any)[0].(map[string]any)
	if tr["handover"] != "door" || len(tr["timeline"].([]any)) != 3 { // 2 orders steps + 1 carrier scan
		t.Fatalf("tracking: %v", tr)
	}
	mustStatus(t, b.do("POST", "/v1/account/orders/"+testkit.ShopperOrderNumber+"/shipments/"+testkit.ShopperShipmentID+"/cancel", map[string]any{"reason": "late"}), 409)

	// Returns via tax-returns.
	r = b.do("GET", "/v1/account/orders/"+testkit.ShopperOrderNumber+"/returnable", nil)
	mustStatus(t, r, 200)
	mustStatus(t, b.do("POST", "/v1/account/returns", map[string]any{"orderNumber": testkit.ShopperOrderNumber, "shipmentId": testkit.ShopperShipmentID,
		"method": "DROP_OFF", "lines": []map[string]any{{"lineId": testkit.ShopperLineID, "quantity": 1, "reason": "BROKEN"}}}), 422)
	r = b.do("POST", "/v1/account/returns", map[string]any{"orderNumber": testkit.ShopperOrderNumber, "shipmentId": testkit.ShopperShipmentID,
		"method": "DROP_OFF", "lines": []map[string]any{{"lineId": testkit.ShopperLineID, "quantity": 1, "reason": "DAMAGED", "comment": "Torn"}}})
	mustStatus(t, r, 201)
	if r.json(t)["qrPayload"] == nil || r.json(t)["refundMinor"].(float64) != 1353 {
		t.Fatalf("start return: %s", r.body)
	}
	// The customer app's short form.
	mustStatus(t, b.do("POST", "/v1/account/returns", map[string]any{"orderNumber": testkit.ShopperOrderNumber, "atin": "ATJOLLOFV1", "reason": "NO_LONGER_NEEDED"}), 201)
	mustStatus(t, b.do("GET", "/v1/account/returns", nil), 200)
	r = b.do("GET", "/v1/account/returns?orderNumber="+testkit.ShopperOrderNumber, nil)
	mustStatus(t, r, 200)
	if n := len(r.json(t)["items"].([]any)); n != 1 {
		t.Fatalf("returns of the order: %d", n)
	}
	mustStatus(t, b.do("GET", "/v1/account/returns?orderNumber=AT-2026-NOPE", nil), 404)
	r = b.do("GET", "/v1/account/returns/RMA-0000ABCD", nil) // by number (QR)
	mustStatus(t, r, 200)
	if r.json(t)["rmaId"] != testkit.ShopperRMAID {
		t.Fatalf("by number: %s", r.body)
	}
	r = b.do("GET", "/v1/account/returns/"+testkit.ShopperRMAID, nil)
	mustStatus(t, r, 200)
	if r.json(t)["quote"] == nil {
		t.Fatal("return detail needs the refund quote")
	}
	mustStatus(t, b.do("POST", "/v1/account/returns/"+testkit.ShopperRMAID+"/cancel", nil), 200)
	mustStatus(t, b.do("GET", "/v1/account/returns/nope", nil), 404)

	// Login & security.
	r = b.do("GET", "/v1/account/security", nil)
	mustStatus(t, r, 200)
	if !strings.HasSuffix(r.json(t)["passkeyRegistrationUrl"].(string), "/webauthn/register") {
		t.Fatalf("security: %s", r.body)
	}
	mustStatus(t, b.do("PUT", "/v1/account/password", map[string]any{"currentPassword": "wrong", "newPassword": "A-much-longer-passw0rd"}), 422)
	mustStatus(t, b.do("PUT", "/v1/account/password", map[string]any{"currentPassword": testkit.ShopperCurrentPassword, "newPassword": "short"}), 422)
	mustStatus(t, b.do("PUT", "/v1/account/password", map[string]any{"currentPassword": testkit.ShopperCurrentPassword, "newPassword": "A-much-longer-passw0rd"}), 204)
	mustStatus(t, b.do("POST", "/v1/account/mfa/totp", nil), 200)
	mustStatus(t, b.do("POST", "/v1/account/mfa/totp/confirm", map[string]any{"code": "000000"}), 422)
	mustStatus(t, b.do("POST", "/v1/account/mfa/totp/confirm", map[string]any{"code": "123456"}), 200)
	mustStatus(t, b.do("DELETE", "/v1/account/mfa/totp", nil), 204)
	mustStatus(t, b.do("POST", "/v1/account/mfa/sms", nil), 202)
	mustStatus(t, b.do("POST", "/v1/account/mfa/sms/confirm", map[string]any{"code": "123456"}), 200)
	mustStatus(t, b.do("DELETE", "/v1/account/mfa/sms", nil), 204)
	mustStatus(t, b.do("POST", "/v1/account/mfa/recovery-codes", nil), 200)
	mustStatus(t, b.do("DELETE", "/v1/account/passkeys/last", nil), 409)
	mustStatus(t, b.do("DELETE", "/v1/account/passkeys/cred-1", nil), 204)
	mustStatus(t, b.do("DELETE", "/v1/account/sessions/s2", nil), 204)
	mustStatus(t, b.do("POST", "/v1/account/sessions/revoke-others", nil), 200)
	mustStatus(t, b.do("DELETE", "/v1/account/devices/d1", nil), 204)
	mustStatus(t, b.do("DELETE", "/v1/account/linked-accounts/google", nil), 200)
	mustStatus(t, b.do("GET", "/v1/account/notification-preferences", nil), 200)
	r = b.do("PUT", "/v1/account/notification-preferences", map[string]any{"email": true, "sms": false, "push": true, "marketingOptIn": true})
	mustStatus(t, r, 200)
	if r.json(t)["sms"] != false || r.json(t)["marketingOptIn"] != true {
		t.Fatalf("preferences: %s", r.body)
	}

	// Bookings.
	mustStatus(t, b.do("POST", "/v1/services/bookings", map[string]any{"serviceId": testkit.ShopperServiceID, "startAt": "tomorrow", "mode": "IN_PERSON"}), 422)
	mustStatus(t, b.do("POST", "/v1/services/bookings", map[string]any{"serviceId": testkit.ShopperServiceID, "startAt": "2026-10-20T11:00:00Z", "mode": "IN_PERSON"}), 409)
	r = b.do("POST", "/v1/services/bookings", map[string]any{"serviceId": testkit.ShopperServiceID, "startAt": "2026-10-20T15:00:00Z", "mode": "IN_PERSON", "notes": "Waist length"})
	mustStatus(t, r, 201)
	pay, _ := r.json(t)["payment"].(map[string]any)
	if pay == nil || pay["clientSecret"] != "pi_bk_secret_123" || r.json(t)["booking"].(map[string]any)["awaitingPayment"] != true {
		t.Fatalf("booking: %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/account/bookings/"+testkit.ShopperBookingID+"/payment/sync", nil), 200)
	r = b.do("GET", "/v1/account/bookings", nil)
	mustStatus(t, r, 200)
	mustStatus(t, b.do("GET", "/v1/account/bookings/"+testkit.ShopperBookingID, nil), 200)
	mustStatus(t, b.do("GET", "/v1/account/bookings/nope", nil), 404)
	r = b.do("GET", "/v1/account/bookings/"+testkit.ShopperBookingID+"/cancellation-quote", nil)
	mustStatus(t, r, 200)
	if r.json(t)["refundMinor"].(float64) != 3000 || r.json(t)["releaseHold"] != true {
		t.Fatalf("quote: %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/account/bookings/"+testkit.ShopperBookingID+"/reschedule", map[string]any{"startAt": "2026-10-22T15:00:00Z"}), 200)
	mustStatus(t, b.do("POST", "/v1/account/bookings/"+testkit.ShopperBookingID+"/new-time/accept", nil), 200)
	mustStatus(t, b.do("POST", "/v1/account/bookings/"+testkit.ShopperBookingID+"/new-time/maybe", nil), 404)
	mustStatus(t, b.do("POST", "/v1/account/bookings/"+testkit.ShopperBookingID+"/review", map[string]any{"rating": 6}), 422)
	mustStatus(t, b.do("POST", "/v1/account/bookings/"+testkit.ShopperBookingID+"/review", map[string]any{"rating": 5, "text": "Lovely"}), 201)
	r = b.do("POST", "/v1/account/bookings/"+testkit.ShopperBookingID+"/cancel", map[string]any{"reason": "Travelling"})
	mustStatus(t, r, 200)
	if r.json(t)["booking"].(map[string]any)["canCancel"] != false {
		t.Fatalf("cancel: %s", r.body)
	}

	// Lola (SSE relayed with the deliver-to ZIP), budget problems before the stream, history.
	r = b.do("POST", "/v1/lola/chat", map[string]any{"message": "Jollof for 20"})
	mustStatus(t, r, 200)
	body := string(r.body)
	if r.header.Get("Content-Type") != "text/event-stream" || !strings.Contains(body, "event:token") || !strings.Contains(body, "event:done") ||
		!strings.Contains(body, `"zip":"77042"`) || strings.Index(body, "action_proposal") > strings.Index(body, "event:done") {
		t.Fatalf("lola stream: %s", body)
	}
	mustStatus(t, b.do("POST", "/v1/lola/chat", map[string]any{"message": ""}), 422)
	r = b.do("POST", "/v1/lola/chat", map[string]any{"message": "budget"})
	mustStatus(t, r, 429)
	if r.header.Get("Retry-After") != "3600" {
		t.Fatalf("Retry-After: %v", r.header)
	}
	mustStatus(t, b.do("GET", "/v1/lola/sessions/lola_abcdef123456/messages", nil), 200)
	mustStatus(t, b.do("GET", "/v1/lola/sessions/x/messages", nil), 404)

	// Checkout: summary with delivery facts, placement returns the client secret, confirm.
	mustStatus(t, b.do("POST", "/v1/cart/items", map[string]any{"offerId": "ATJOLLOFV1", "quantity": 1}), 200)
	mustStatus(t, b.do("POST", "/v1/account/addresses", map[string]any{"name": "Adaeze Nwosu", "phone": "+17135550142", "line1": "9800 Westheimer Rd",
		"city": "Houston", "state": "TX", "zip": "77042", "isDefault": true}), 201)
	r = b.do("GET", "/v1/checkout/summary", nil)
	mustStatus(t, r, 200)
	sum := r.json(t)
	if sum["publishableKey"] != testkit.TestPublishableKey {
		t.Fatalf("summary needs the publishable key: %s", r.body)
	}
	pkgs := sum["packages"].([]any)
	var platform map[string]any
	for _, p := range pkgs {
		if pm := p.(map[string]any); pm["fulfilment"] == "AFROTRANSACT" {
			platform = pm
		}
	}
	if platform == nil || len(platform["pickupPoints"].([]any)) != 1 {
		t.Fatalf("pickup points: %s", r.body)
	}
	keys := []string{}
	for _, o := range platform["deliveryOptions"].([]any) {
		om := o.(map[string]any)
		keys = append(keys, om["key"].(string))
		if om["earliestDate"] != "2026-10-12" {
			t.Fatalf("promise dates missing: %v", om)
		}
	}
	if !strings.Contains(strings.Join(keys, ","), "pickup") {
		t.Fatalf("options: %v", keys)
	}
	b.headers = map[string]string{"Idempotency-Key": "checkout-key-0000000001"}
	mustStatus(t, b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "x", "paymentMethodId": "not-a-pm",
		"deliverySelections": []map[string]any{{"packageId": platform["packageId"], "option": "drone"}}}), 422)
	r = b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "addr-1", "paymentMethodId": "pm_new_from_sheet",
		"deliverySelections": []map[string]any{{"packageId": platform["packageId"], "option": "carrier", "preferences": map[string]any{"handover": "hand", "contact": "text"}}}})
	mustStatus(t, r, 201)
	conf := r.json(t)
	if conf["status"] != "awaiting_payment" || conf["paymentClientSecret"] != "pi_1_secret_xyz" {
		t.Fatalf("placement must always return the client secret: %s", r.body)
	}
	sent := u.LastCheckout()
	sel := sent["deliverySelections"].([]any)[0].(map[string]any)
	if sel["preferences"].(map[string]any)["handover"] != "HAND" || sent["paymentMethodId"] != "pm_new_from_sheet" || len(sent["lines"].([]any)) == 0 {
		t.Fatalf("orders checkout body: %v", sent)
	}
	// Stripe Payment Element (deferred intent): no saved method or wallet; saving asks orders for setup_future_usage.
	b.headers = map[string]string{"Idempotency-Key": "checkout-key-0000000002"}
	sels := []map[string]any{{"packageId": platform["packageId"], "option": "carrier"}}
	for _, bad := range []map[string]any{
		{"addressId": "addr-1", "deliverySelections": sels},
		{"addressId": "addr-1", "deliverySelections": sels, "newPaymentMethod": true, "paymentMethodId": "pm_saved_1"},
		{"addressId": "addr-1", "deliverySelections": sels, "paymentMethodId": "pm_saved_1", "savePaymentMethod": true},
	} {
		mustStatus(t, b.do("POST", "/v1/checkout/orders", bad), 422)
	}
	r = b.do("POST", "/v1/checkout/orders", map[string]any{"addressId": "addr-1", "deliverySelections": sels, "newPaymentMethod": true, "savePaymentMethod": true})
	mustStatus(t, r, 201)
	if r.json(t)["paymentClientSecret"] == nil {
		t.Fatalf("new payment method: the client secret confirms the Payment Element: %s", r.body)
	}
	if sent := u.LastCheckout(); sent["savePaymentMethod"] != true || sent["paymentMethodId"] != nil || sent["wallet"] != nil {
		t.Fatalf("orders checkout body for a saved new method: %v", sent)
	}
	b.headers = nil
	number := conf["orderNumber"].(string)
	mustStatus(t, b.do("GET", "/v1/checkout/orders/"+number, nil), 200)
	r = b.do("POST", "/v1/checkout/orders/"+number+"/confirm", nil)
	mustStatus(t, r, 200)
	if r.json(t)["status"] != "placed" {
		t.Fatalf("confirm: %s", r.body)
	}
	mustStatus(t, b.do("POST", "/v1/checkout/orders/AT-2026-DECLINE/confirm", nil), 402)

	for _, op := range []string{"searchServices", "getService", "getServiceDays", "getServiceSlots", "getBookingQuote", "getProviderPage",
		"listPaymentMethods", "createSetupIntent", "deletePaymentMethod", "setDefaultPaymentMethod", "createPaymentSheetKeys", "listOrders",
		"getOrder", "trackOrder", "cancelShipment", "getReturnEligibility", "startReturn", "listReturns", "getReturn", "cancelReturn",
		"getSecurity", "changePassword", "startTotp", "confirmTotp", "disableTotp", "startSmsMfa", "confirmSmsMfa", "disableSmsMfa",
		"regenerateRecoveryCodes", "removePasskey", "revokeSession", "revokeOtherSessions", "removeDevice", "unlinkAccount",
		"getNotificationPreferences", "saveNotificationPreferences", "bookService", "syncBookingPayment", "listMyBookings", "getMyBooking",
		"getBookingCancellationQuote", "rescheduleMyBooking", "answerProposedTime", "reviewMyBooking", "cancelMyBooking", "chatWithLola",
		"getLolaHistory", "getCheckoutSummary", "placeOrder", "getCheckoutStatus", "confirmCheckoutPayment"} {
		if validated[op] == 0 {
			t.Errorf("operation %s was not validated", op)
		}
	}
}
