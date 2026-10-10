package app_test

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

func owners() *fakeResolver {
	bola := port.Contact{UserID: "u-2", FirstName: "Bola", Email: "bola@example.com", Phone: "+15550002222"}
	return &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada(), "u-2": bola},
		owners: map[string][]string{"org_1": {"u-2"}}}
}

func claimPayload(status string, outcome any) map[string]any {
	return map[string]any{"claimId": "c-1", "reference": "AZ-10001", "type": "DAMAGED_ITEM", "status": status, "orderId": "o-1",
		"orderNumber": nil, "sellerOrgId": "org_1", "customerId": "cust-1", "amountMinor": 2500, "currency": "USD",
		"outcome": outcome, "refundMinor": 1500, "dueAt": "2026-10-13T10:00:00Z", "escalated": false}
}

// emailBody returns the rendered email text of the template key (echoTemplates' subject is the key).
func emailBody(t *testing.T, rows []domain.Notification, key string) string {
	t.Helper()
	for _, r := range rows {
		if r.Subject == key && r.Channel == domain.ChannelEmail {
			return r.BodyText
		}
	}
	t.Fatalf("no %s email in %v", key, summary(rows))
	return ""
}

func TestClaimOpenedNotifiesCustomerAndSellerOwners(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, owners(), "firstName", "reference", "orderNumber", "claimType", "dueDate", "amount", "actionUrl", "claimsUrl")
	handle(t, ing, "trustops.claim.opened", claimPayload("AWAITING_SELLER", nil))
	got := strings.Join(summary(store.enqueued), ",")
	want := "claim.opened|email|ada@example.com,claim.opened|push|fcm:tok,claim.seller_response_due|email|bola@example.com," +
		"claim.seller_response_due|sms|+15550002222"
	if got != want {
		t.Fatalf("got %v", got)
	}
	seller := emailBody(t, store.enqueued, "claim.seller_response_due")
	for _, part := range []string{"orderNumber=o-1", "claimType=damaged item", "dueDate=Tue, Oct 13", "amount=$25.00",
		"actionUrl=https://seller.test/orders"} {
		if !strings.Contains(seller, part) {
			t.Errorf("%q lacks %q", seller, part)
		}
	}
	// A claim opened straight into review has no seller response window.
	store2 := newFakeStore()
	handle(t, newIngest(store2, owners(), "reference"), "trustops.claim.opened", claimPayload("UNDER_REVIEW", nil))
	if got := summary(store2.enqueued); len(got) != 2 || !strings.HasPrefix(got[0], "claim.opened|") {
		t.Fatalf("%v", got)
	}
}

func TestClaimDecidedNotifiesBothParties(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, owners(), "outcomeText", "sellerOutcomeText")
	handle(t, ing, "trustops.claim.decided", claimPayload("RESOLVED_REFUND", "REFUND"))
	got := strings.Join(summary(store.enqueued), ",")
	if got != "claim.decided.seller|email|bola@example.com,claim.decided|email|ada@example.com,claim.decided|push|fcm:tok" {
		t.Fatalf("got %v", got)
	}
	if b := emailBody(t, store.enqueued, "claim.decided"); !strings.Contains(b, "refunding $15.00") {
		t.Fatalf("%q", b)
	}
	store = newFakeStore()
	handle(t, newIngest(store, owners(), "outcomeText", "sellerOutcomeText"), "trustops.claim.decided", claimPayload("RESOLVED_DENIED", "DENY"))
	if b := emailBody(t, store.enqueued, "claim.decided.seller"); !strings.Contains(b, "without a refund") {
		t.Fatalf("%q", b)
	}
}

func TestTrustOpsClaimNotificationRequestsAreSuperseded(t *testing.T) {
	store := newFakeStore()
	res := owners()
	ing := newIngest(store, res, "reference")
	for _, key := range []string{"claim.opened", "claim.decided", "claim.withdrawn"} {
		handle(t, ing, app.GenericEventType, map[string]any{"templateKey": key, "channels": []string{"email", "push"},
			"recipient": map[string]any{"id": "cust-1"}, "data": map[string]any{"reference": "AZ-1"}})
	}
	if len(store.enqueued) != 0 || res.calls != 0 {
		t.Fatalf("typed trustops.claim.* rules cover these: %v", summary(store.enqueued))
	}
	// Other templates still go through the generic path.
	handle(t, ing, app.GenericEventType, map[string]any{"templateKey": "booking.reminder", "channels": []string{"email"},
		"recipient": map[string]any{"id": "cust-1"}, "data": map[string]any{"reference": "BK-1"}})
	if len(store.enqueued) != 1 {
		t.Fatalf("%v", summary(store.enqueued))
	}
}

func TestModerationDecisionsGoToSellerOwners(t *testing.T) {
	cases := map[string]string{"NEW_LISTING": "listing.rejected", "LIVE_LISTING": "listing.taken_down"}
	for itemType, key := range cases {
		store := newFakeStore()
		ing := newIngest(store, owners(), "title", "reasonText", "note", "actionUrl")
		handle(t, ing, "trustops.moderation.decided", map[string]any{"itemId": "i", "productId": "p", "sellerOrgId": "org_1",
			"title": "Zobo", "status": "REJECTED", "priority": "NORMAL", "reasonCode": "COUNTERFEIT", "note": nil,
			"itemType": itemType, "dueAt": "2026-10-11T10:00:00Z"})
		if len(store.enqueued) == 0 || store.enqueued[0].Subject != key || store.enqueued[0].Address != "bola@example.com" {
			t.Fatalf("%s: %v", itemType, summary(store.enqueued))
		}
		if b := store.enqueued[0].BodyText; !strings.Contains(b, "reasonText=counterfeit") || strings.Contains(b, "<no value>") {
			t.Fatalf("%q", b)
		}
	}
	for _, status := range []string{"APPROVED", "CLOSED", "PENDING"} {
		store := newFakeStore()
		handle(t, newIngest(store, owners(), "title"), "trustops.moderation.decided", map[string]any{"sellerOrgId": "org_1",
			"status": status, "itemType": "LIVE_LISTING", "title": status})
		if len(store.enqueued) != 0 {
			t.Fatalf("%s must not notify: %v", status, summary(store.enqueued))
		}
	}
}

func TestSuspensionWordingVariants(t *testing.T) {
	cases := []struct{ typ, reason, want string }{
		{"seller.payouts.paused", "SUSPENDED", "seller.payouts.paused.suspended"},
		{"seller.payouts.paused", "AUTO_PAST_DUE", "seller.payouts.paused"},
		{"seller.listings.hidden", "SUSPENDED", "seller.listings.hidden.suspended"},
		{"seller.listings.hidden", "CHARGES_DISABLED", "seller.listings.hidden"},
		{"seller.payouts.resumed", "REINSTATED", "seller.payouts.resumed.reinstated"},
		{"seller.payouts.resumed", "CLEARED", "seller.payouts.resumed"},
		{"seller.listings.restored", "REINSTATED", "seller.listings.restored.reinstated"},
		{"seller.listings.restored", "CLEARED", "seller.listings.restored"},
	}
	for _, c := range cases {
		store := newFakeStore()
		ing := newIngest(store, owners(), "actionUrl")
		handle(t, ing, c.typ, map[string]any{"orgId": "org_1", "accountId": nil, "reason": c.reason,
			"note": "[FRAUD_RISK] Chargebacks", "pastDue": []string{}})
		keys := map[string]bool{}
		for _, r := range store.enqueued {
			keys[r.Subject] = true
		}
		if len(keys) != 1 || !keys[c.want] {
			t.Errorf("%s %s: got %v want %s", c.typ, c.reason, summary(store.enqueued), c.want)
		}
	}
	store := newFakeStore()
	handle(t, newIngest(store, owners(), "reasonText", "note"), "seller.listings.hidden", map[string]any{"orgId": "org_1",
		"reason": "SUSPENDED", "note": "[FRAUD_RISK] Chargebacks"})
	if b := store.enqueued[0].BodyText; !strings.Contains(b, "reasonText=fraud risk;note=Chargebacks") {
		t.Fatalf("%q", b)
	}
	if len(store.enqueued) != 1 || store.enqueued[0].Channel != domain.ChannelEmail {
		t.Fatalf("suspension holds are email only (trustops.seller.suspended sends the sms): %v", summary(store.enqueued))
	}
}

func TestSellerSuspendedAndReinstated(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, owners(), "reasonText", "note", "actionUrl")
	handle(t, ing, "trustops.seller.suspended", map[string]any{"orgId": "org_1", "status": "SUSPENDED", "reason": "PROHIBITED_ITEMS",
		"note": nil, "payoutsHeld": true, "buyBoxRestricted": true, "changedAt": "2026-10-10T10:00:00Z"})
	handle(t, ing, "trustops.seller.reinstated", map[string]any{"orgId": "org_1", "status": "ACTIVE", "reason": nil,
		"payoutsHeld": false, "buyBoxRestricted": false, "changedAt": "2026-10-11T10:00:00Z"})
	got := strings.Join(summary(store.enqueued), ",")
	if got != "seller.reinstated|email|bola@example.com,seller.suspended|email|bola@example.com,seller.suspended|sms|+15550002222" {
		t.Fatalf("got %v", got)
	}
	if b := emailBody(t, store.enqueued, "seller.suspended"); !strings.Contains(b, "reasonText=prohibited items;note=;") {
		t.Fatalf("%q", b)
	}
}

func TestInvitationUsesPayloadEmailOnly(t *testing.T) {
	store := newFakeStore()
	res := &fakeResolver{err: errors.New("identity must not be called")}
	ing := newIngest(store, res, "orgName", "roleName", "inviterName", "acceptUrl", "expiresInDays")
	handle(t, ing, "identity.invitation.created", map[string]any{"invitationId": "inv-1", "orgId": "org_1", "orgName": "Mama Put",
		"orgKind": "SELLER", "email": "new@example.com", "roleKey": "seller.catalog", "roleName": "Catalog manager", "token": "t",
		"acceptUrl": "https://seller.test/invitations/accept?token=t", "expiresInDays": 7, "invitedBy": "u-1", "inviterName": nil,
		"recipient": map[string]any{"id": nil, "email": "new@example.com", "phone": nil}, "transactional": true})
	if res.calls != 0 || strings.Join(summary(store.enqueued), ",") != "org.invitation|email|new@example.com" {
		t.Fatalf("calls=%d %v", res.calls, summary(store.enqueued))
	}
	b := store.enqueued[0].BodyText
	if !strings.Contains(b, "inviterName=A teammate;") || !strings.Contains(b, "expiresInDays=7;") || store.enqueued[0].RecipientID != "" {
		t.Fatalf("%q", b)
	}
}

func TestPhoneVerificationVariant(t *testing.T) {
	store := newFakeStore()
	res := &fakeResolver{err: errors.New("identity must not be called")}
	ing := newIngest(store, res, "code")
	handle(t, ing, "identity.verification.requested", map[string]any{"recipient": map[string]any{"id": "u-1", "phone": "+15550003333"},
		"channel": "sms", "code": "123456", "purpose": "PHONE_VERIFICATION", "userId": "u-1"})
	handle(t, ing, "identity.verification.requested", map[string]any{"recipient": map[string]any{"id": "u-1", "phone": "+15550003333"},
		"channel": "sms", "code": "654321", "purpose": "SIGN_IN_SMS", "userId": "u-1"})
	got := strings.Join(summary(store.enqueued), ",")
	if res.calls != 0 || got != "identity.verification.phone|sms|+15550003333,identity.verification|sms|+15550003333" {
		t.Fatalf("calls=%d %v", res.calls, got)
	}
}

func TestBalanceUnpaidAndAuthenticationRequired(t *testing.T) {
	store := newFakeStore()
	handle(t, newIngest(store, owners(), "firstName", "balance"), "booking.balance_unpaid", map[string]any{"recipient": map[string]any{"id": "cust-1", "email": nil, "phone": nil},
		"firstName": "Ada", "serviceName": "Braids", "currency": "USD", "priceMinor": 18000, "depositMinor": 3000, "balanceState": "FAILED"})
	ing := newIngest(store, owners(), "firstName", "amount", "reference", "actionUrl")
	handle(t, ing, "payments.intent.action_required", map[string]any{"paymentId": "p", "orderId": "BK-24018", "parentPaymentId": "pp",
		"customerId": "cust-1", "amountMinor": 15000, "currency": "USD", "reason": "authentication_required"})
	handle(t, ing, "payments.intent.action_required", map[string]any{"paymentId": "p2", "orderId": "BK-1", "amountMinor": 1, "currency": "USD"})
	got := strings.Join(summary(store.enqueued), ",")
	want := "booking.balance_unpaid|email|ada@example.com,booking.balance_unpaid|push|fcm:tok," +
		"payment.authentication_required|email|ada@example.com,payment.authentication_required|push|fcm:tok"
	if got != want {
		t.Fatalf("got %v", got)
	}
	if b := emailBody(t, store.enqueued, "booking.balance_unpaid"); !strings.Contains(b, "balance=$150.00") {
		t.Fatalf("%q", b)
	}
	if b := emailBody(t, store.enqueued, "payment.authentication_required"); !strings.Contains(b,
		"amount=$150.00") || !strings.Contains(b, "actionUrl=https://shop.test/bookings/BK-24018") {
		t.Fatalf("%q", b)
	}
}

type fakeInvalidator struct{ forgot []string }

func (f *fakeInvalidator) Forget(id string) { f.forgot = append(f.forgot, id) }

func TestContactChangesDropTheCachedContact(t *testing.T) {
	store := newFakeStore()
	inv := &fakeInvalidator{}
	n := app.NewNotifier(echoTemplates{}, app.NewRenderer(), store, nil)
	ing := app.NewIngest(n, app.IngestConfig{Resolver: owners(), Contacts: inv}, quietLog())
	for i, typ := range app.ContactChangedTypes {
		raw, _ := json.Marshal(map[string]any{"userId": "u-" + string(rune('1'+i)), "phone": "+15550001111"})
		if err := ing.Handle(context.Background(), events.Envelope{ID: "e" + typ, Type: typ, Payload: raw}); err != nil {
			t.Fatal(err)
		}
	}
	if strings.Join(inv.forgot, ",") != "u-1,u-2" || len(store.enqueued) != 0 {
		t.Fatalf("%v %v", inv.forgot, summary(store.enqueued))
	}
	// Without a cache (identity disabled) the events are simply ignored.
	ing = app.NewIngest(n, app.IngestConfig{}, quietLog())
	if err := ing.Handle(context.Background(), events.Envelope{ID: "x", Type: app.ContactChangedTypes[0], Payload: []byte(`{}`)}); err != nil {
		t.Fatal(err)
	}
}
