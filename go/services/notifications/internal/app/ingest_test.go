package app_test

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// fakeResolver serves contacts and org owners from memory.
type fakeResolver struct {
	contacts map[string]port.Contact
	owners   map[string][]string
	err      error
	calls    int
}

func (f *fakeResolver) Contact(_ context.Context, id string) (port.Contact, error) {
	f.calls++
	if f.err != nil {
		return port.Contact{}, f.err
	}
	c, ok := f.contacts[id]
	if !ok {
		return port.Contact{}, domain.ErrRecipientNotFound
	}
	return c, nil
}

func (f *fakeResolver) OrgOwners(_ context.Context, org string) ([]string, error) {
	if f.err != nil {
		return nil, f.err
	}
	o, ok := f.owners[org]
	if !ok {
		return nil, domain.ErrRecipientNotFound
	}
	return o, nil
}

// echoTemplates has a template for every key and channel whose text renders the given fields (missing fields fail,
// as in production) and whose subject is the key, so tests see which rule fired.
type echoTemplates struct{ fields []string }

func (e echoTemplates) Find(_ context.Context, key string, _ []string, ch domain.Channel, _ int) (domain.Template, error) {
	var b strings.Builder
	for _, f := range e.fields {
		b.WriteString(f + "={{." + f + "};")
	}
	body := strings.ReplaceAll(b.String(), "};", "}};")
	return domain.Template{Key: key, Locale: "en", Channel: ch, Version: 1, Category: domain.CategoryTransactional,
		Subject: key, BodyText: body}, nil
}
func (echoTemplates) Create(context.Context, domain.Template) (domain.Template, error) {
	return domain.Template{}, nil
}
func (echoTemplates) List(context.Context, string) ([]domain.Template, error) { return nil, nil }

var links = app.Links{Storefront: "https://shop.test", SellerCenter: "https://seller.test", ProviderHub: "https://provider.test"}

func ada() port.Contact {
	return port.Contact{UserID: "cust-1", FirstName: "Ada", Email: "ada@example.com", Phone: "+15550001111",
		PushTokens: []domain.PushToken{{Token: "tok", Platform: domain.PushFCM}}, Market: "ca", TimeZone: "America/Toronto"}
}

func newIngest(store *fakeStore, res port.RecipientResolver, fields ...string) app.Ingest {
	n := app.NewNotifier(echoTemplates{fields: fields}, app.NewRenderer(), store, nil)
	return app.NewIngest(n, app.IngestConfig{Resolver: res, Links: links}, quietLog())
}

func handle(t *testing.T, ing app.Ingest, typ string, payload any) {
	t.Helper()
	raw, _ := json.Marshal(payload)
	if err := ing.Handle(context.Background(), events.Envelope{ID: "evt-" + typ + string(raw[:min(len(raw), 40)]), Type: typ, Payload: raw}); err != nil {
		t.Fatal(err)
	}
}

func summary(rows []domain.Notification) []string {
	out := make([]string, 0, len(rows))
	for _, r := range rows {
		out = append(out, r.Subject+"|"+string(r.Channel)+"|"+r.Address)
	}
	sort.Strings(out)
	return out
}

func TestOrderPlacedResolvesCustomerFromIdentity(t *testing.T) {
	store := newFakeStore()
	res := &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada()}}
	ing := newIngest(store, res, "firstName", "orderNumber", "total", "itemCount", "estimatedDelivery", "orderUrl")
	handle(t, ing, "orders.order.placed", map[string]any{"orderId": "o-1", "orderNumber": "AT-2026-7KQ3MD",
		"customerId": "cust-1", "currency": "USD", "totalMinor": 123456, "placedAt": "2026-10-10T10:00:00Z",
		"shipments": []any{
			map[string]any{"shipmentId": "s1", "promisedBy": "2026-10-16", "lines": []any{map[string]any{"quantity": 2}}},
			map[string]any{"shipmentId": "s2", "promisedBy": "2026-10-14", "lines": []any{map[string]any{"quantity": 1}}},
		}})
	got := summary(store.enqueued)
	want := []string{"order.confirmed|email|ada@example.com", "order.confirmed|push|fcm:tok", "order.confirmed|sms|+15550001111"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got %v", got)
	}
	row := store.enqueued[0]
	for _, part := range []string{"firstName=Ada", "total=$1,234.56", "itemCount=3", "estimatedDelivery=by Wed, Oct 14", "orderUrl=https://shop.test/orders"} {
		if !strings.Contains(row.BodyText, part) {
			t.Errorf("body %q lacks %q", row.BodyText, part)
		}
	}
	if row.RecipientID != "cust-1" || row.Market != "ca" || row.TimeZone != "America/Toronto" {
		t.Fatalf("%+v", row)
	}
}

func TestOrderCancelledOnlyWhenPlaced(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada()}}, "orderNumber")
	handle(t, ing, "orders.order.cancelled", map[string]any{"orderNumber": "AT-1", "customerId": "cust-1", "wasPlaced": false})
	if len(store.enqueued) != 0 {
		t.Fatal("a checkout that never completed must not notify")
	}
	handle(t, ing, "orders.order.cancelled", map[string]any{"orderNumber": "AT-2", "customerId": "cust-1", "wasPlaced": true})
	if got := summary(store.enqueued); len(got) != 2 || !strings.HasPrefix(got[0], "order.cancelled|email") {
		t.Fatalf("%v", got)
	}
}

func TestShipmentStatusPicksTemplate(t *testing.T) {
	cases := []struct {
		from, to, method, want string
	}{
		{"packed", "picked_up", "CARRIER", "shipment.shipped"},
		{"picked_up", "in_transit", "CARRIER", ""}, // already announced at pickup
		{"ready", "in_transit", "CARRIER", "shipment.shipped"},
		{"picked_up", "out_for_delivery", "AFROTRANSACT_DELIVERY", "shipment.out_for_delivery"},
		{"packed", "ready", "PICKUP", "shipment.ready_for_pickup"},
		{"packed", "ready", "CARRIER", ""},
		{"out_for_delivery", "delivery_failed", "AFROTRANSACT_DELIVERY", "shipment.delivery_failed"},
		{"out_for_delivery", "delivered", "AFROTRANSACT_DELIVERY", ""}, // orders.shipment.delivered covers it
		{"placed", "confirmed", "CARRIER", ""},
	}
	for _, c := range cases {
		store := newFakeStore()
		ing := newIngest(store, &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada()}}, "orderNumber", "eta", "trackingLine")
		handle(t, ing, "orders.shipment.status_changed", map[string]any{"orderNumber": "AT-9", "customerId": "cust-1",
			"from": c.from, "to": c.to, "deliveryMethod": c.method, "carrier": "ups", "trackingNumber": "1Z9"})
		got := ""
		if len(store.enqueued) > 0 {
			got = store.enqueued[0].Subject
		}
		if got != c.want {
			t.Errorf("%s→%s (%s): got %q want %q", c.from, c.to, c.method, got, c.want)
		}
		if c.want == "shipment.shipped" && !strings.Contains(store.enqueued[0].BodyText, "trackingLine=UPS tracking: 1Z9") {
			t.Errorf("tracking line: %q", store.enqueued[0].BodyText)
		}
	}
}

func TestComplianceGoesToEveryOwnerOnPayloadChannels(t *testing.T) {
	store := newFakeStore()
	bola := port.Contact{UserID: "u-2", FirstName: "Bola", Email: "bola@example.com", Phone: "+15550002222",
		PushTokens: []domain.PushToken{{Token: "t2", Platform: domain.PushAPNs}}, Disabled: []domain.Channel{domain.ChannelSMS}}
	noContact := "u-3" // unknown to identity: skipped, the others still go out
	res := &fakeResolver{contacts: map[string]port.Contact{"u-1": ada(), "u-2": bola},
		owners: map[string][]string{"org_1": {"u-1", "u-2", noContact}}}
	ing := newIngest(store, res, "firstName", "itemsText", "deadline", "actionUrl")
	handle(t, ing, "seller.compliance.reminder", map[string]any{"orgId": "org_1", "accountId": "acct_1", "manual": false,
		"openItems": []string{"individual.verification.document", "external_account"}, "currentDeadline": "2026-10-20T00:00:00Z",
		"channels": []string{"email", "sms", "in_app"}, "thresholdDays": 7})
	got := summary(store.enqueued)
	want := []string{"seller.compliance.reminder|email|ada@example.com", "seller.compliance.reminder|email|bola@example.com",
		"seller.compliance.reminder|push|apns:t2", "seller.compliance.reminder|push|fcm:tok", "seller.compliance.reminder|sms|+15550001111"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got %v", got)
	}
	if !strings.Contains(store.enqueued[0].BodyText, "itemsText=individual verification document and external account") ||
		!strings.Contains(store.enqueued[0].BodyText, "deadline=Tue, Oct 20") {
		t.Fatalf("%q", store.enqueued[0].BodyText)
	}
	// An unknown organisation is skipped, not retried.
	before := len(store.enqueued)
	handle(t, ing, "seller.payouts.paused", map[string]any{"orgId": "org_nope", "accountId": "a", "reason": "MANUAL"})
	if len(store.enqueued) != before {
		t.Fatal("unknown org must be skipped")
	}
}

func TestSellerApplicationUsesApplicant(t *testing.T) {
	store := newFakeStore()
	res := &fakeResolver{contacts: map[string]port.Contact{"app-user": ada()}}
	ing := newIngest(store, res, "firstName", "programName", "portalUrl", "requestedItemsText", "reference")
	handle(t, ing, "seller.application.info_requested", map[string]any{"applicationId": "app_1", "reference": "APP-30201",
		"orgId": "org_1", "kind": "PROVIDER", "applicantUserId": "app-user", "status": "INFO_REQUESTED",
		"action": "REQUEST_INFO", "actorId": "staff", "requestedItems": []string{"business_permit"}})
	if len(store.enqueued) != 2 {
		t.Fatalf("%v", summary(store.enqueued))
	}
	body := store.enqueued[0].BodyText
	for _, part := range []string{"programName=Provider Hub", "portalUrl=https://provider.test", "requestedItemsText=business permit"} {
		if !strings.Contains(body, part) {
			t.Errorf("%q lacks %q", body, part)
		}
	}
}

func TestBookingPayloadRecipientFilledFromIdentity(t *testing.T) {
	store := newFakeStore()
	res := &fakeResolver{contacts: map[string]port.Contact{"cust-1": ada()}}
	ing := newIngest(store, res, "firstName", "serviceName", "refundNote")
	handle(t, ing, "booking.cancelled", map[string]any{"recipient": map[string]any{"id": "cust-1", "email": "booked@example.com",
		"timeZone": "America/Chicago"}, "firstName": "Adaeze", "serviceName": "Braids", "currency": "USD", "refundedMinor": 2500})
	got := summary(store.enqueued)
	// The payload's own email wins; phone and push come from identity.
	want := []string{"booking.cancelled|email|booked@example.com", "booking.cancelled|push|fcm:tok", "booking.cancelled|sms|+15550001111"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got %v", got)
	}
	if !strings.Contains(store.enqueued[0].BodyText, "firstName=Adaeze") || !strings.Contains(store.enqueued[0].BodyText, "$25.00") ||
		store.enqueued[0].TimeZone != "America/Chicago" {
		t.Fatalf("%+v", store.enqueued[0])
	}
}

func TestVerificationNeverCallsIdentity(t *testing.T) {
	store := newFakeStore()
	res := &fakeResolver{err: errors.New("identity down")}
	ing := newIngest(store, res, "code")
	handle(t, ing, "identity.verification.requested", map[string]any{"recipient": map[string]any{"id": "u-1", "email": "new@example.com"},
		"channel": "email", "code": "123456", "userId": "u-1"})
	if res.calls != 0 || len(store.enqueued) != 1 || store.enqueued[0].Address != "new@example.com" {
		t.Fatalf("calls=%d %v", res.calls, summary(store.enqueued))
	}
}

func TestTopLevelEmailForCareersAndWelcome(t *testing.T) {
	store := newFakeStore()
	ing := newIngest(store, nil, "firstName", "jobTitle")
	handle(t, ing, "content.career-application.received", map[string]any{"reference": "CAR-000123", "jobTitle": "Engineer",
		"name": "Kofi Mensah", "email": "kofi@example.com"})
	if got := summary(store.enqueued); len(got) != 1 || got[0] != "careers.application_received|email|kofi@example.com" ||
		!strings.Contains(store.enqueued[0].BodyText, "firstName=Kofi") {
		t.Fatalf("%v", got)
	}
	handle(t, ing, "identity.user.registered", map[string]any{"userId": "u-9", "email": "staff@example.com", "firstName": "S", "intent": "STAFF"})
	if len(store.enqueued) != 1 {
		t.Fatal("staff provisioning gets no welcome email")
	}
}

func TestIdentityOutageRedeliversAndUnknownUserSkips(t *testing.T) {
	store := newFakeStore()
	res := &fakeResolver{err: errors.New("connection refused")}
	ing := newIngest(store, res, "orderNumber")
	raw, _ := json.Marshal(map[string]any{"orderNumber": "AT-1", "customerId": "cust-1"})
	if err := ing.Handle(context.Background(), events.Envelope{ID: "e1", Type: "orders.shipment.delivered", Payload: raw}); err == nil {
		t.Fatal("a transient identity failure must be returned so the event is redelivered")
	}
	res.err = nil // identity is back but does not know the user: skip without error
	if err := ing.Handle(context.Background(), events.Envelope{ID: "e1", Type: "orders.shipment.delivered", Payload: raw}); err != nil {
		t.Fatal(err)
	}
	if len(store.enqueued) != 0 {
		t.Fatal("no address, nothing to send")
	}
}
