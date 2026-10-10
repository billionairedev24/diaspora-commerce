package postgres_test

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/postgres"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// identityStub answers recipient lookups for the producer-event fixtures.
type identityStub struct{}

func (identityStub) Contact(_ context.Context, id string) (port.Contact, error) {
	return port.Contact{UserID: id, FirstName: "Ada", Email: id + "@example.com", Phone: "+15550001111",
		PushTokens: []domain.PushToken{{Token: "tok-" + id, Platform: domain.PushFCM}}}, nil
}

func (identityStub) OrgOwners(context.Context, string) ([]string, error) {
	return []string{"owner-1", "owner-2"}, nil
}

// producerFixtures are payloads shaped like contracts/asyncapi/events.yaml, one per mapped event type (and one per
// rule for multi-rule types). Templates use missingkey=error, so a field the ingest forgets to derive fails here.
var producerFixtures = map[string][]map[string]any{
	"identity.verification.requested": {{"recipient": map[string]any{"id": "u-1", "email": "otp@example.com"}, "channel": "email",
		"code": "123456", "expiresInMinutes": 10, "expiresAt": "2026-10-10T10:10:00Z", "purpose": "EMAIL_VERIFICATION",
		"userId": "u-1", "firstName": "Ada", "transactional": true}, {"recipient": map[string]any{"id": "u-1", "phone": "+15550002222"},
		"channel": "sms", "code": "654321", "expiresInMinutes": 10, "expiresAt": "2026-10-10T10:10:00Z", "purpose": "PHONE_VERIFICATION",
		"userId": "u-1", "transactional": true}},
	"identity.invitation.created": {{"invitationId": uuid.NewString(), "orgId": "org_1", "orgName": "Mama Put", "orgKind": "SELLER",
		"email": "invitee@example.com", "roleKey": "seller.catalog", "roleName": "Catalog manager", "token": "tok",
		"acceptUrl": "https://seller.afrotransact.com/invitations/accept?token=tok", "expiresAt": "2026-10-17T10:00:00Z",
		"expiresInDays": 7, "invitedBy": "owner-1", "inviterName": nil,
		"recipient": map[string]any{"id": nil, "email": "invitee@example.com", "phone": nil}, "transactional": true}},
	"identity.user.registered": {{"userId": "u-1", "email": "ada@example.com", "firstName": "Ada", "lastName": "Obi", "intent": "SELL",
		"roles": []string{"account.seller_applicant"}, "method": "PASSWORD", "emailVerified": false, "smsConsent": false,
		"registeredAt": "2026-10-10T10:00:00Z"}},
	"orders.order.placed": {{"orderId": uuid.NewString(), "orderNumber": "AT-2026-7KQ3MD", "customerId": "cust-1", "currency": "USD",
		"itemsMinor": 4000, "shippingMinor": 500, "taxMinor": 300, "discountMinor": 0, "totalMinor": 4800,
		"placedAt": "2026-10-10T10:00:00Z", "shipments": []any{map[string]any{"shipmentId": uuid.NewString(), "sellerOrgId": "org_1",
			"fulfilmentType": "RETAIL_1P", "deliveryMethod": "CARRIER", "promisedBy": "2026-10-14", "totalMinor": 4800,
			"lines": []any{map[string]any{"variantAtin": "ATGARI0001", "quantity": 2}}}}}},
	"orders.order.cancelled": {{"orderId": uuid.NewString(), "orderNumber": "AT-1", "customerId": "cust-1",
		"code": "orders.all-shipments-cancelled", "wasPlaced": true, "cancelledAt": "2026-10-10T10:00:00Z"}},
	"orders.shipment.status_changed": {
		shipment("packed", "picked_up", "CARRIER"), shipment("picked_up", "out_for_delivery", "AFROTRANSACT_DELIVERY"),
		shipment("packed", "ready", "PICKUP"), shipment("out_for_delivery", "delivery_failed", "AFROTRANSACT_DELIVERY"),
	},
	"orders.shipment.delivered": {{"orderId": uuid.NewString(), "orderNumber": "AT-1", "customerId": "cust-1", "shipmentId": uuid.NewString(),
		"sellerOrgId": "org_1", "deliveredAt": "2026-10-10T10:00:00Z", "totalMinor": 4800, "lineIds": []string{}, "source": "driver"}},
	"orders.shipment.cancelled": {{"orderId": uuid.NewString(), "orderNumber": "AT-1", "customerId": "cust-1", "shipmentId": uuid.NewString(),
		"sellerOrgId": "org_1", "actorType": "CUSTOMER", "captured": true, "totalMinor": 1999, "currency": "USD",
		"cancelledAt": "2026-10-10T10:00:00Z"}},
	"seller.application.submitted":      {application("SUBMITTED", nil)},
	"seller.application.info_requested": {application("REQUEST_INFO", []string{"business_permit", "tax_id"})},
	"seller.application.approved":       {application("FINAL_APPROVE", nil)},
	"seller.application.rejected":       {application("REJECT", nil)},
	"seller.compliance.reminder": {{"orgId": "org_1", "accountId": "acct_1", "thresholdDays": 3, "manual": false,
		"openItems": []string{"external_account"}, "currentDeadline": "2026-10-13T00:00:00Z", "channels": []string{"email", "sms", "in_app"},
		"occurredAt": "2026-10-10T10:00:00Z"}},
	"seller.compliance.changed": {{"orgId": "org_1", "accountId": "acct_1", "status": "ACTION_NEEDED", "previousStatus": "COMPLIANT",
		"newItems": []string{"individual.id_number"}, "currentDeadline": nil, "chargesEnabled": true, "payoutsEnabled": true,
		"payoutsPaused": false, "trigger": "account.updated", "channels": []string{"email"}, "occurredAt": "2026-10-10T10:00:00Z"}},
	"seller.payouts.paused":    {enforcement("AUTO_PAST_DUE"), enforcement("SUSPENDED")},
	"seller.payouts.resumed":   {enforcement("CLEARED"), enforcement("REINSTATED")},
	"seller.listings.hidden":   {enforcement("CHARGES_DISABLED"), enforcement("SUSPENDED")},
	"seller.listings.restored": {enforcement("CLEARED"), enforcement("REINSTATED")},
	"payments.payout.paid":     {payout("PAID")},
	"payments.payout.failed":   {payout("FAILED")},
	"booking.confirmed":        {booking("CONFIRMED")},
	"booking.cancelled":        {booking("CANCELLED_PROVIDER")},
	"booking.declined":         {booking("DECLINED")},
	"booking.rescheduled":      {booking("CONFIRMED")},
	"booking.balance_unpaid":   {balanceUnpaid()},
	"payments.intent.action_required": {{"paymentId": uuid.NewString(), "orderId": "BK-24018", "parentPaymentId": uuid.NewString(),
		"customerId": "cust-1", "stripePaymentIntentId": nil, "amountMinor": 15000, "currency": "USD",
		"reason": "authentication_required", "lineIds": []string{}}},
	"trustops.claim.opened":            {claim("AWAITING_SELLER", nil), claim("AWAITING_SELLER", nil)},
	"trustops.claim.decided":           {claim("RESOLVED_REFUND", "REFUND"), claim("RESOLVED_DENIED", "DENY")},
	"trustops.claim.withdrawn":         {claim("WITHDRAWN", nil)},
	"trustops.claim.response_due_soon": {claim("AWAITING_SELLER", nil)},
	"trustops.claim.response_lapsed":   {claim("UNDER_REVIEW", nil)},
	"trustops.moderation.decided":      {moderation("NEW_LISTING"), moderation("LIVE_LISTING")},
	"trustops.seller.suspended": {{"orgId": "org_1", "status": "SUSPENDED", "reason": "POLICY_VIOLATION", "note": nil,
		"actorId": "staff-1", "payoutsHeld": true, "buyBoxRestricted": true, "listingsHidden": true, "changedAt": "2026-10-10T10:00:00Z"}},
	"trustops.seller.reinstated": {{"orgId": "org_1", "status": "ACTIVE", "reason": nil, "note": nil, "actorId": "staff-1",
		"payoutsHeld": false, "buyBoxRestricted": false, "listingsHidden": false, "changedAt": "2026-10-10T10:00:00Z"}},
	"content.career-application.received": {{"reference": "CAR-000123", "jobId": "j1", "jobTitle": "Backend Engineer", "team": "Platform",
		"location": "Remote", "name": "Kofi Mensah", "email": "kofi@example.com", "resumeAssetId": "a1", "resumeFileName": "cv.pdf",
		"submittedAt": "2026-10-10T10:00:00Z"}},
	"content.newsletter.confirmation_requested": {{"subscriptionId": uuid.NewString(), "email": "news@example.com", "locale": "fr",
		"confirmUrl": "https://afrotransact.com/newsletter/confirm?t=x", "unsubscribeUrl": "https://afrotransact.com/u", "expiresAt": "2026-10-11T10:00:00Z"}},
}

func shipment(from, to, method string) map[string]any {
	return map[string]any{"orderId": uuid.NewString(), "orderNumber": "AT-1", "customerId": "cust-1", "shipmentId": uuid.NewString(),
		"sellerOrgId": "org_1", "fulfilmentType": "SELLER_FULFILLED", "deliveryMethod": method, "from": from, "to": to,
		"actorType": "CARRIER", "actorId": "ups", "source": "carrier", "occurredAt": "2026-10-10T10:00:00Z",
		"carrier": "ups", "trackingNumber": "1Z999", "promisedBy": "2026-10-14"}
}

func application(action string, items []string) map[string]any {
	return map[string]any{"applicationId": "app_1", "reference": "APP-30201", "orgId": "org_1", "kind": "SELLER",
		"applicantUserId": "applicant-1", "status": "PENDING_REVIEW", "action": action, "actorId": "staff-1",
		"requestedItems": items, "displayName": "Mama Put Foods", "note": nil, "submissionCount": 1, "occurredAt": "2026-10-10T10:00:00Z"}
}

func enforcement(reason string) map[string]any {
	e := map[string]any{"orgId": "org_1", "accountId": "acct_1", "reason": reason, "pastDue": []string{"external_account"},
		"actorId": nil, "note": nil, "occurredAt": "2026-10-10T10:00:00Z"}
	if reason == "SUSPENDED" {
		e["accountId"], e["actorId"], e["note"], e["pastDue"] = nil, "trust-ops-service", "[PROHIBITED_ITEMS] Repeated prohibited listings", []string{}
	}
	return e
}

func claim(status string, outcome any) map[string]any {
	return map[string]any{"claimId": uuid.NewString(), "reference": "AZ-10001", "type": "ITEM_NOT_RECEIVED", "status": status,
		"orderId": uuid.NewString(), "orderNumber": "AT-2026-7KQ3MD", "shipmentId": nil, "sellerOrgId": "org_1", "customerId": "cust-1",
		"amountMinor": 4800, "currency": "USD", "outcome": outcome, "refundMinor": nil, "refundId": nil,
		"dueAt": "2026-10-13T10:00:00Z", "escalated": false}
}

func moderation(itemType string) map[string]any {
	return map[string]any{"itemId": uuid.NewString(), "productId": uuid.NewString(), "atin": nil, "sellerOrgId": "org_1",
		"title": "Zobo leaves 200g", "status": "REJECTED", "priority": "NORMAL", "reasonCode": "UNAPPROVED_HEALTH_CLAIM",
		"note": nil, "escalationReason": nil, "aiRiskLevel": nil, "itemType": itemType, "reportSource": nil, "reportReason": nil,
		"reports": 0, "decidedBy": "staff-1", "dueAt": "2026-10-11T10:00:00Z"}
}

func balanceUnpaid() map[string]any {
	b := booking("CONFIRMED")
	b["balanceMinor"], b["balanceState"], b["balanceFailure"] = 15000, "FAILED", "card_declined"
	return b
}

func payout(status string) map[string]any {
	return map[string]any{"payoutRequestId": uuid.NewString(), "orgId": "org_1", "amountMinor": 250000, "currency": "USD",
		"status": status, "requestedAt": "2026-10-10T10:00:00Z"}
}

func booking(status string) map[string]any {
	return map[string]any{"recipient": map[string]any{"id": "cust-1", "email": nil, "phone": nil, "timeZone": "America/Chicago"},
		"firstName": "Ada", "serviceName": "Knotless braids", "providerName": "Kemi's Studio", "startsAt": "Sat, Oct 17 · 10:00 AM",
		"location": "Houston, TX", "bookingReference": "BK-24018", "manageUrl": "https://afrotransact.com/bookings/BK-24018",
		"bookingId": uuid.NewString(), "orgId": "org_2", "memberId": uuid.NewString(), "serviceId": uuid.NewString(), "status": status,
		"startAt": "2026-10-17T15:00:00Z", "endAt": "2026-10-17T18:00:00Z", "priceMinor": 18000, "depositMinor": 3000,
		"currency": "USD", "policy": "MODERATE", "refundedMinor": 3000}
}

func producerEvents(t *testing.T, pool *pgxpool.Pool) {
	e := newEnv(t, pool)
	ctx := context.Background()
	ing := app.NewIngest(e.notifier, app.IngestConfig{Resolver: identityStub{},
		Links: app.Links{Storefront: "https://afrotransact.com", SellerCenter: "https://seller.afrotransact.com",
			ProviderHub: "https://provider.afrotransact.com"}}, discard)
	types := make([]string, 0, len(app.DefaultEventRules))
	for typ := range app.DefaultEventRules {
		types = append(types, typ)
	}
	sort.Strings(types)
	for _, typ := range types {
		fixtures := producerFixtures[typ]
		if len(fixtures) < len(app.DefaultEventRules[typ]) {
			t.Errorf("%s: add a fixture per rule to producerFixtures", typ)
			continue
		}
		for i, payload := range fixtures {
			raw, _ := json.Marshal(payload)
			ev := events.Envelope{ID: "fixture-" + uuid.NewString(), Type: typ, Payload: raw}
			if err := ing.Handle(ctx, ev); err != nil {
				t.Fatalf("%s[%d]: %v", typ, i, err)
			}
			var rows []domain.Notification
			for _, addr := range []string{"otp@example.com", "u-1@example.com", "ada@example.com", "cust-1@example.com",
				"applicant-1@example.com", "owner-1@example.com", "owner-2@example.com", "kofi@example.com", "news@example.com",
				"invitee@example.com", "+15550001111", "+15550002222"} {
				got, err := e.store.List(ctx, domain.ListFilter{Recipient: addr, Limit: 100})
				if err != nil {
					t.Fatal(err)
				}
				for _, r := range got {
					if r.EventID == ev.ID {
						rows = append(rows, r)
					}
				}
			}
			if len(rows) == 0 {
				t.Errorf("%s[%d]: no email rendered (missing template field?)", typ, i)
				continue
			}
			for _, r := range rows {
				if strings.Contains(r.Subject+r.BodyText+r.BodyHTML, "<no value>") || (r.Subject == "" && r.Channel != domain.ChannelSMS) {
					t.Errorf("%s[%d]: %q / %q", typ, i, r.Subject, r.BodyText)
				}
			}
		}
	}
	// Spot-check the copy for one event end to end.
	rows, _ := e.store.List(ctx, domain.ListFilter{Recipient: "cust-1@example.com", Limit: 100})
	found := false
	for _, r := range rows {
		if r.TemplateKey == "order.confirmed" {
			found = true
			if r.Subject != "Order AT-2026-7KQ3MD is confirmed" || !strings.Contains(r.BodyText, "$48.00") ||
				!strings.Contains(r.BodyText, "by Wed, Oct 14") || !strings.Contains(r.BodyText, "https://afrotransact.com/orders") {
				t.Fatalf("%q / %q", r.Subject, r.BodyText)
			}
		}
	}
	if !found {
		t.Fatal("order.confirmed not rendered")
	}
	// Variants: phone verification wording, invitation defaults, suspension wording, both parties of a claim.
	want := map[string]map[string]string{
		"+15550002222":        {"identity.verification.phone": "Your AfroTransact code is 654321"},
		"invitee@example.com": {"org.invitation": "A teammate invited you to join Mama Put on AfroTransact as Catalog manager"},
		"owner-1@example.com": {"seller.payouts.paused.suspended": "suspended (reason: prohibited items)",
			"claim.seller_response_due": "Respond by Tue, Oct 13", "claim.decided.seller": "We refunded the customer $48.00",
			"listing.taken_down": "reason: unapproved health claim", "seller.listings.restored.reinstated": "reinstated"},
		"cust-1@example.com": {"claim.opened": "claim AZ-10001 (item not received)", "claim.decided": "refunding $48.00",
			"booking.balance_unpaid": "$150.00 balance", "payment.authentication_required": "https://afrotransact.com/bookings/BK-24018"},
	}
	for addr, byKey := range want {
		rows, _ := e.store.List(ctx, domain.ListFilter{Recipient: addr, Limit: 200})
		for key, part := range byKey {
			ok := false
			for _, r := range rows {
				if r.TemplateKey == key && strings.Contains(r.Subject+" "+r.BodyText, part) {
					ok = true
				}
			}
			if !ok {
				t.Errorf("%s: no %s containing %q", addr, key, part)
			}
		}
	}
}

func stockAlerts(t *testing.T, pool *pgxpool.Pool) {
	ctx := context.Background()
	store := postgres.NewStockAlertStore(pool)
	a, created, err := store.Subscribe(ctx, domain.StockAlert{UserID: "s-1", Atin: "ATSTCK0001", Email: "s1@example.com",
		Market: "us", Locale: "en", ProductTitle: "Palm oil 1L"})
	if err != nil || !created || a.Status != domain.StockAlertActive || a.ProductTitle != "Palm oil 1L" {
		t.Fatalf("%+v %v %v", a, created, err)
	}
	again, created, err := store.Subscribe(ctx, domain.StockAlert{UserID: "s-1", Atin: "ATSTCK0001", Market: "us", Locale: "en"})
	if err != nil || created || again.ID != a.ID {
		t.Fatalf("subscribe once: %+v %v %v", again, created, err)
	}
	if _, _, err := store.Subscribe(ctx, domain.StockAlert{UserID: "s-2", Atin: "ATSTCK0001", Market: "us", Locale: "en"}); err != nil {
		t.Fatal(err)
	}
	claimed, err := store.ClaimForRestock(ctx, "ATSTCK0001", "inv-a", 10)
	if err != nil || len(claimed) != 2 || claimed[0].Status != domain.StockAlertNotified {
		t.Fatalf("%+v %v", claimed, err)
	}
	redelivered, _ := store.ClaimForRestock(ctx, "ATSTCK0001", "inv-a", 10)
	other, _ := store.ClaimForRestock(ctx, "ATSTCK0001", "inv-b", 10)
	if len(redelivered) != 2 || len(other) != 0 {
		t.Fatalf("same event re-claims, a new event finds none: %d %d", len(redelivered), len(other))
	}
	// Re-arm after a notification keeps the stored email when none is given.
	rearmed, created, err := store.Subscribe(ctx, domain.StockAlert{UserID: "s-1", Atin: "ATSTCK0001", Market: "ca", Locale: "fr"})
	if err != nil || !created || rearmed.Status != domain.StockAlertActive || rearmed.Email != "s1@example.com" || rearmed.Market != "ca" {
		t.Fatalf("%+v %v %v", rearmed, created, err)
	}
	mine, err := store.ListByUser(ctx, "s-1")
	if err != nil || len(mine) != 1 || mine[0].NotifiedAt != nil {
		t.Fatalf("%+v %v", mine, err)
	}
	if err := store.Unsubscribe(ctx, "s-1", "ATSTCK0001"); err != nil {
		t.Fatal(err)
	}
	if err := store.Unsubscribe(ctx, "s-1", "ATSTCK0001"); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("%v", err)
	}

	// End to end: restock event → back-in-stock email rendered from the seeded template, once.
	e := newEnv(t, pool)
	sa := app.NewStockAlerts(store, e.notifier, identityStub{}, app.Links{Storefront: "https://afrotransact.com"}, discard)
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "s-3", Atin: "ATSTCK0002", ProductTitle: "Ogbono 500g"}); err != nil {
		t.Fatal(err)
	}
	ing := app.NewIngest(e.notifier, app.IngestConfig{StockAlerts: sa}, discard)
	raw, _ := json.Marshal(map[string]any{"sku": "ATSTCK0002", "nodeId": "HOU-FC", "ownerOrg": "retail", "available": 4,
		"onHand": 4, "reserved": 0, "version": 7})
	for range 2 {
		if err := ing.Handle(ctx, events.Envelope{ID: "inv-e2e", Type: app.AvailabilityChangedType, Payload: raw}); err != nil {
			t.Fatal(err)
		}
	}
	rows, _ := e.store.List(ctx, domain.ListFilter{Recipient: "s-3@example.com", Limit: 10})
	if len(rows) != 1 || rows[0].Subject != "It's back: Ogbono 500g" || !strings.Contains(rows[0].BodyText, "https://afrotransact.com/p/ATSTCK0002") {
		t.Fatalf("%+v", rows)
	}
}
