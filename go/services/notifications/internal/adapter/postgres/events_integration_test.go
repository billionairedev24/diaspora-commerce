package postgres_test

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

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

// seededTemplatesRender renders the latest version of every seeded key, locale and channel (real migrations) with the
// key's sample data, as cmd/template-gallery does.
func seededTemplatesRender(t *testing.T, pool *pgxpool.Pool) {
	ctx := context.Background()
	samples, err := app.NewTemplateSamples(ctx, app.Links{Storefront: "https://afrotransact.com",
		SellerCenter: "https://seller.afrotransact.com", ProviderHub: "https://provider.afrotransact.com"})
	if err != nil {
		t.Fatal(err)
	}
	entries, err := app.NewTemplateGallery(postgres.NewTemplateStore(pool), app.NewRenderer(), samples).Entries(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) < 94 {
		t.Fatalf("%d seeded templates, want at least 94", len(entries))
	}
	for _, e := range entries {
		if e.Err != "" {
			t.Errorf("%s/%s/%s v%d: %s", e.Template.Key, e.Template.Locale, e.Template.Channel, e.Template.Version, e.Err)
		}
		if e.Template.Channel == domain.ChannelSMS && (e.SMS == nil || e.SMS.Segments == 0) {
			t.Errorf("%s/sms: no segment count", e.Template.Key)
		}
	}
}

func producerEvents(t *testing.T, pool *pgxpool.Pool) {
	e := newEnv(t, pool)
	ctx := context.Background()
	ing := app.NewIngest(e.notifier, app.IngestConfig{Resolver: identityStub{}, Audience: app.SampleAudience(),
		Links: app.Links{Storefront: "https://afrotransact.com", SellerCenter: "https://seller.afrotransact.com",
			ProviderHub: "https://provider.afrotransact.com"}}, discard)
	producerFixtures := app.SampleEvents(app.Links{Storefront: "https://afrotransact.com", SellerCenter: "https://seller.afrotransact.com"}) // contract-shaped payloads, one per rule (shared with the template gallery)
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
		if r.TemplateKey == "order.confirmed" && strings.Contains(r.Subject, "AT-2026-7KQ3MD") {
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
			"booking.balance_unpaid": "$150.00 balance", "payment.authentication_required": "https://afrotransact.com/bookings/BK-24018",
			"preorder.confirmed": "You're in: Lubbock Pre-order #7", "preorder.delivery_missed": "choice=refund&src=email&po=LBK-7&cr=missed",
			"preorder.announce": "Door delivery: $7.99 flat", "preorder.waitlist.opened": "Leave the waitlist"},
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

// noIdentity fails every lookup: retail's supplier contacts must never reach identity.
type noIdentity struct{}

func (noIdentity) Contact(context.Context, string) (port.Contact, error) {
	return port.Contact{}, errors.New("identity must not be called for supplier contacts")
}

func (noIdentity) OrgOwners(context.Context, string) ([]string, error) {
	return nil, errors.New("identity must not be called for supplier contacts")
}

// attachingSender captures emails and can attach files.
type attachingSender struct {
	mu  sync.Mutex
	got []domain.EmailMessage
}

func (*attachingSender) Name() string              { return "resend" }
func (*attachingSender) SupportsAttachments() bool { return true }
func (s *attachingSender) Send(_ context.Context, m domain.EmailMessage) (domain.Receipt, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.got = append(s.got, m)
	return domain.Receipt{ProviderMessageID: "re-" + uuid.NewString()}, nil
}

// memFetcher serves attachment content from memory (no network).
type memFetcher map[string][]byte

func (m memFetcher) Fetch(_ context.Context, ref domain.AttachmentRef) (domain.Attachment, error) {
	b, ok := m[ref.URL]
	if !ok {
		return domain.Attachment{}, errors.New("status 403")
	}
	return domain.Attachment{FileName: ref.FileName, ContentType: ref.ContentType, Content: b}, nil
}

// retailPurchaseOrder: retail's notification.requested (at.retail.events) renders the seeded supplier PO email to the
// payload address only, stores the attachment reference, and the dispatcher attaches the fetched PDF.
func retailPurchaseOrder(t *testing.T, pool *pgxpool.Pool) {
	e := newEnv(t, pool)
	ctx := context.Background()
	ing := app.NewIngest(e.notifier, app.IngestConfig{Resolver: noIdentity{}, Links: app.Links{Storefront: "https://afrotransact.com"}}, discard)
	assetURL := "https://files.example/po/" + uuid.NewString() + ".pdf?sig=abc"
	payload := map[string]any{"templateKey": "retail.purchase_order.sent", "channels": []string{"email"},
		"recipient": map[string]any{"email": "efua@accrapack.example", "name": "Efua Mensah"}, "locale": "en",
		"data": map[string]any{"poId": uuid.NewString(), "poNumber": "PO-1P-323", "supplierName": "Accra Pack Ltd",
			"supplierCode": "SUP-ACC", "contactName": "Efua Mensah", "orderDate": "10 Oct 2026", "deliverBy": "24 Oct 2026",
			"incoterm": "FOB", "paymentTerms": nil, "shipTo": "HOU-FC", "lineCount": 1, "units": 480, "totalMinor": 1665600,
			"currency": "GHS", "total": "GHS 16656.00", "documentUrl": assetURL, "documentExpiresAt": "2026-10-24T10:00:00Z",
			"documentAssetId": "a1", "attachments": []any{map[string]any{"assetId": "a1", "fileName": "PO-1P-323.pdf",
				"contentType": "application/pdf", "url": assetURL, "expiresAt": "2026-10-24T10:00:00Z"}}}}
	raw, _ := json.Marshal(payload)
	if err := ing.Handle(ctx, events.Envelope{ID: "retail-" + uuid.NewString(), Type: app.GenericEventType, Payload: raw}); err != nil {
		t.Fatal(err)
	}
	rows, err := e.store.List(ctx, domain.ListFilter{Recipient: "efua@accrapack.example", Limit: 10})
	if err != nil || len(rows) != 1 {
		t.Fatalf("%d rows: %v", len(rows), err)
	}
	r := rows[0]
	if r.TemplateKey != "retail.purchase_order.sent" || r.Subject != "Purchase order PO-1P-323 from AfroTransact Retail" ||
		r.Category != domain.CategoryTransactional || r.RecipientID != "" {
		t.Fatalf("%+v", r)
	}
	for _, part := range []string{"Hello Efua Mensah", "to Accra Pack Ltd", "Deliver by: 24 Oct 2026", "Incoterm: FOB",
		"Total: GHS 16656.00", assetURL, "valid until Oct 24, 2026"} {
		if !strings.Contains(r.BodyText, part) {
			t.Errorf("text lacks %q:\n%s", part, r.BodyText)
		}
	}
	if strings.Contains(r.BodyText+r.BodyHTML, "Payment terms") || strings.Contains(r.BodyText+r.BodyHTML, "<no value>") {
		t.Errorf("null payment terms must drop the row:\n%s", r.BodyText)
	}
	if !strings.Contains(r.BodyHTML, `href="`+strings.ReplaceAll(assetURL, "&", "&amp;")+`"`) {
		t.Errorf("html link:\n%s", r.BodyHTML)
	}
	if len(r.Attachments) != 1 || r.Attachments[0].URL != assetURL || r.Attachments[0].FileName != "PO-1P-323.pdf" ||
		r.Attachments[0].AssetID != "a1" {
		t.Fatalf("stored attachments: %+v", r.Attachments)
	}

	// Send it: the PDF is fetched and attached; other queued rows are parked so only this one is claimed.
	if _, err := pool.Exec(ctx, `UPDATE notification SET next_attempt_at = now() + interval '1 day'
		WHERE status = 'queued' AND id <> $1`, r.ID); err != nil {
		t.Fatal(err)
	}
	e.makeDue(t, r.ID)
	sender := &attachingSender{}
	checker := postgres.NewConsentCheckerWithClock(pool, domain.DefaultQuietHours, map[string]string{"us": "America/New_York"},
		func() time.Time { return *e.clock })
	router := app.NewRouter(app.Routes{Email: map[string][]port.EmailSender{"default": {sender}}},
		domain.NewBreakers(domain.BreakerSettings{FailureThreshold: 1000}), time.Second, discard)
	d := app.NewDispatcher(app.DispatcherConfig{Batch: 10, Lease: time.Minute, EmailFrom: "AT <no-reply@at.com>",
		Attachments: memFetcher{assetURL: []byte("%PDF-1.7 PO-1P-323")}}, e.store, checker, router, discard)
	if _, err := d.RunOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if got := e.get(t, r.ID); got.Status != domain.StatusSent {
		t.Fatalf("status %s: %s", got.Status, got.LastError)
	}
	if len(sender.got) != 1 || len(sender.got[0].Attachments) != 1 || sender.got[0].Attachments[0].FileName != "PO-1P-323.pdf" ||
		string(sender.got[0].Attachments[0].Content) != "%PDF-1.7 PO-1P-323" || sender.got[0].To != "efua@accrapack.example" {
		t.Fatalf("%+v", sender.got)
	}
}
