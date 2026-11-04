package app_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// memAlerts is an in-memory StockAlertStore with the same semantics as Postgres: (user, atin) and (email, atin)
// uniqueness, an open-alert cap per owner, re-arming of sent alerts, claim by event id, purge.
type memAlerts struct {
	rows    []domain.StockAlert
	created map[string]time.Time
	eventOf map[string]string
	seq     int
}

func newMemAlerts() *memAlerts {
	return &memAlerts{eventOf: map[string]string{}, created: map[string]time.Time{}}
}

func (m *memAlerts) sameOwner(r, a domain.StockAlert) bool {
	if a.Guest() {
		return r.Guest() && r.Email == a.Email
	}
	return r.UserID == a.UserID
}

func (m *memAlerts) Subscribe(_ context.Context, a domain.StockAlert, maxOpen int) (domain.StockAlert, bool, error) {
	open := 0
	for _, r := range m.rows {
		if m.sameOwner(r, a) && r.Status == domain.StockAlertActive {
			open++
		}
	}
	for i, r := range m.rows {
		if m.sameOwner(r, a) && r.Atin == a.Atin {
			if r.Status == domain.StockAlertActive {
				return r, false, nil
			}
			if maxOpen > 0 && open >= maxOpen {
				return a, false, domain.ErrAlertLimit
			}
			m.rows[i].Status, m.rows[i].NotifiedAt = domain.StockAlertActive, nil
			return m.rows[i], true, nil
		}
	}
	if maxOpen > 0 && open >= maxOpen {
		return a, false, domain.ErrAlertLimit
	}
	m.seq++
	a.ID, a.Status = "sa-"+string(rune('a'+m.seq)), domain.StockAlertActive
	m.rows = append(m.rows, a)
	return a, true, nil
}

func (m *memAlerts) Unsubscribe(_ context.Context, userID, atin string) error {
	for i, r := range m.rows {
		if r.UserID == userID && r.Atin == atin {
			m.rows = append(m.rows[:i], m.rows[i+1:]...)
			return nil
		}
	}
	return domain.ErrNotFound
}

func (m *memAlerts) DeleteByID(_ context.Context, id string) error {
	for i, r := range m.rows {
		if r.ID == id {
			m.rows = append(m.rows[:i], m.rows[i+1:]...)
			return nil
		}
	}
	return nil
}

func (m *memAlerts) ListByUser(_ context.Context, userID string) ([]domain.StockAlert, error) {
	var out []domain.StockAlert
	for _, r := range m.rows {
		if r.UserID == userID {
			out = append(out, r)
		}
	}
	return out, nil
}

func (m *memAlerts) ClaimForRestock(_ context.Context, atin, eventID string, limit int) ([]domain.StockAlert, error) {
	var out []domain.StockAlert
	now := time.Now()
	for i, r := range m.rows {
		if r.Atin != atin || len(out) >= limit {
			continue
		}
		if r.Status == domain.StockAlertActive || m.eventOf[r.ID] == eventID {
			m.rows[i].Status, m.rows[i].NotifiedAt = domain.StockAlertNotified, &now
			m.eventOf[r.ID] = eventID
			out = append(out, m.rows[i])
		}
	}
	return out, nil
}

func (m *memAlerts) Purge(_ context.Context, openBefore, notifiedBefore time.Time) (int64, error) {
	var keep []domain.StockAlert
	var n int64
	for _, r := range m.rows {
		created := m.created[r.ID]
		switch {
		case r.Status == domain.StockAlertActive && created.Before(openBefore):
			n++
		case r.Status == domain.StockAlertNotified && r.NotifiedAt.Before(notifiedBefore):
			n++
		default:
			keep = append(keep, r)
		}
	}
	m.rows = keep
	return n, nil
}

var _ port.StockAlertStore = (*memAlerts)(nil)

// alertTemplates renders what the real back-in-stock template reads, with firstName optional as in production.
type alertTemplates struct{}

func (alertTemplates) Find(_ context.Context, key string, locales []string, ch domain.Channel, _ int) (domain.Template, error) {
	return domain.Template{Key: key, Locale: locales[0], Channel: ch, Version: 1, Category: domain.CategoryTransactional, Subject: key,
		BodyText: `first={{with index . "firstName"}}{{.}}{{end}};title={{.productTitle}};url={{.productUrl}};unsub={{.alertUnsubscribeUrl}}`}, nil
}
func (alertTemplates) Create(context.Context, domain.Template) (domain.Template, error) {
	return domain.Template{}, nil
}
func (alertTemplates) List(context.Context, string) ([]domain.Template, error) { return nil, nil }

const alertBase = "https://api.test/notifications"

func newAlerts(t *testing.T, res port.RecipientResolver, opts ...app.StockAlertOption) (app.StockAlerts, *memAlerts, *fakeStore, app.Unsubscriber) {
	t.Helper()
	store, alerts := newFakeStore(), newMemAlerts()
	unsub := app.NewUnsubscriber(alertBase, "", []byte("test-key"))
	n := app.NewNotifier(alertTemplates{}, app.NewRenderer(), store, unsub)
	return app.NewStockAlerts(alerts, n, res, links, unsub, quietLog(), opts...), alerts, store, unsub
}

func availabilityEvent(id string, available int) events.Envelope {
	const sku = "ATGARI0001"
	raw, _ := json.Marshal(map[string]any{"sku": sku, "nodeId": "HOU-FC", "ownerOrg": "retail", "available": available,
		"onHand": available, "reserved": 0, "version": 3})
	return events.Envelope{ID: id, Type: app.AvailabilityChangedType, AggregateID: sku, Payload: raw}
}

func TestStockAlertsSubscribeOnceAndSendOnce(t *testing.T) {
	ctx := context.Background()
	res := &fakeResolver{contacts: map[string]port.Contact{"shopper-1": ada()}}
	sa, _, store, _ := newAlerts(t, res)
	ing := app.NewIngest(nil, app.IngestConfig{Resolver: res, Links: links, StockAlerts: sa}, quietLog())

	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "nope"}); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Fatalf("bad atin: %v", err)
	}
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{Atin: "ATGARI0001"}); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Fatalf("no user: %v", err)
	}
	a, created, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "atgari0001", ProductTitle: "Gari 1kg"})
	if err != nil || !created || a.Atin != "ATGARI0001" || a.Market != "us" || a.Locale != "en" {
		t.Fatalf("%+v %v %v", a, created, err)
	}
	if _, created, _ := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "ATGARI0001"}); created {
		t.Fatal("a second subscription for the same variant must not create another alert")
	}
	// A signed-in shopper unknown to identity who types an address.
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-2", Atin: "ATGARI0001", Email: "Two@Example.com"}); err != nil {
		t.Fatal(err)
	}
	// A signed-in shopper's address override must be a real address too.
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-3", Atin: "ATGARI0001", Email: "not an email"}); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Fatalf("bad override: %v", err)
	}

	// No units: nothing is sent.
	if err := ing.Handle(ctx, availabilityEvent("inv-1", 0)); err != nil || len(store.enqueued) != 0 {
		t.Fatalf("%v %d", err, len(store.enqueued))
	}
	// Restock: each alert fires once, redelivery and later restocks do not repeat it.
	for _, id := range []string{"inv-2", "inv-2", "inv-3"} {
		if err := ing.Handle(ctx, availabilityEvent(id, 12)); err != nil {
			t.Fatal(err)
		}
	}
	got := summary(store.enqueued)
	want := []string{app.StockAlertTemplate + "|email|ada@example.com", app.StockAlertTemplate + "|email|two@example.com",
		app.StockAlertTemplate + "|push|fcm:tok"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got %v", got)
	}
	for _, r := range store.enqueued {
		if r.Channel != domain.ChannelEmail {
			continue
		}
		if r.Address == "ada@example.com" && (!strings.Contains(r.BodyText, "title=Gari 1kg") ||
			!strings.Contains(r.BodyText, "url=https://shop.test/p/ATGARI0001") || !strings.Contains(r.BodyText, "first=Ada")) {
			t.Fatalf("%q", r.BodyText)
		}
		if r.Address == "two@example.com" && !strings.Contains(r.BodyText, "first=;") {
			t.Fatalf("an unknown first name must stay empty, not 'there': %q", r.BodyText)
		}
		// Every email carries the one-click link and the List-Unsubscribe headers.
		if !strings.Contains(r.BodyText, "unsub="+alertBase+"/v1/stock-alerts/unsubscribe?t=") ||
			!strings.HasPrefix(r.Headers["List-Unsubscribe"], "<"+alertBase+"/v1/stock-alerts/unsubscribe?t=") ||
			r.Headers["List-Unsubscribe-Post"] != "List-Unsubscribe=One-Click" {
			t.Fatalf("no unsubscribe link: %q %v", r.BodyText, r.Headers)
		}
	}
	mine, _ := sa.List(ctx, "shopper-1")
	if len(mine) != 1 || mine[0].Status != domain.StockAlertNotified {
		t.Fatalf("%+v", mine)
	}
	// Subscribing again after a notification re-arms the alert.
	if _, created, _ := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "ATGARI0001"}); !created {
		t.Fatal("re-subscribe after notification must re-arm")
	}
	if err := sa.Unsubscribe(ctx, "shopper-1", "atgari0001"); err != nil {
		t.Fatal(err)
	}
	if err := sa.Unsubscribe(ctx, "shopper-1", "ATGARI0001"); !errors.Is(err, domain.ErrNotFound) {
		t.Fatalf("second delete: %v", err)
	}
}

func TestGuestStockAlertNormalisesAndIsIdempotent(t *testing.T) {
	ctx := context.Background()
	sa, mem, _, _ := newAlerts(t, nil)
	in := domain.StockAlert{Atin: "atgari0001", Email: "  Ada.Obi@EXAMPLE.Com ", Locale: "FR-CA", Market: "CA", ProductTitle: "Gari\r\n1kg"}
	if err := sa.SubscribeGuest(ctx, in); err != nil {
		t.Fatal(err)
	}
	// The same address in another spelling, many times: still one alert, and every call answers the same nil.
	for _, email := range []string{"ada.obi@example.com", "ADA.OBI@example.com", " ada.obi@Example.COM"} {
		in.Email = email
		if err := sa.SubscribeGuest(ctx, in); err != nil {
			t.Fatalf("repeat must be a quiet success: %v", err)
		}
	}
	if len(mem.rows) != 1 {
		t.Fatalf("one alert per (email, atin), got %d", len(mem.rows))
	}
	r := mem.rows[0]
	if r.Email != "ada.obi@example.com" || r.Atin != "ATGARI0001" || r.Locale != "fr-CA" || r.Market != "ca" || r.UserID != "" ||
		r.ProductTitle != "Gari1kg" {
		t.Fatalf("stored %+v", r)
	}
	// A second variant is a second alert; an account alert for the same address is separate.
	in.Atin = "ATGARI0002"
	if err := sa.SubscribeGuest(ctx, in); err != nil || len(mem.rows) != 2 {
		t.Fatalf("%v %d", err, len(mem.rows))
	}
}

func TestGuestStockAlertRejectsHostileEmail(t *testing.T) {
	sa, mem, _, _ := newAlerts(t, nil)
	for _, bad := range []string{"", "plain", "a@b", "a@@b.com", "a b@example.com", "a@exa mple.com", "<a@example.com>",
		"Ada <a@example.com>", "a@example.com\r\nBcc: victim@example.com", "a@example.com,b@example.com", "a@example.com;b@example.com",
		"\"quoted\"@example.com", "a@[127.0.0.1]", "a@1.2.3.4", ".a@example.com", "a.@example.com", "a..b@example.com", "a@-example.com",
		"a@example-.com", "a@example..com", "a@example.c", "é@example.com", "a@exämple.com", strings.Repeat("a", 65) + "@example.com",
		"a@" + strings.Repeat("a", 64) + ".com", "a@" + strings.Repeat("b.", 130) + "com", "a@example.com\x00"} {
		err := sa.SubscribeGuest(context.Background(), domain.StockAlert{Atin: "ATGARI0001", Email: bad})
		if !errors.Is(err, domain.ErrInvalidRequest) {
			t.Errorf("%q accepted: %v", bad, err)
		}
	}
	if len(mem.rows) != 0 {
		t.Fatalf("nothing may be stored for a bad address: %+v", mem.rows)
	}
	if err := sa.SubscribeGuest(context.Background(), domain.StockAlert{Atin: "bad", Email: "a@example.com"}); !errors.Is(err, domain.ErrInvalidRequest) {
		t.Fatalf("bad atin: %v", err)
	}
	// Garbage in the optional fields never fails the call and never reaches storage.
	if err := sa.SubscribeGuest(context.Background(), domain.StockAlert{Atin: "ATGARI0001", Email: "ok@example.com",
		Locale: "xx\r\nInjected: 1", Market: "zz<script>"}); err != nil {
		t.Fatal(err)
	}
	if r := mem.rows[0]; r.Locale != "en" || r.Market != "us" {
		t.Fatalf("%+v", r)
	}
}

func TestGuestStockAlertCapIsSilent(t *testing.T) {
	ctx := context.Background()
	var logs bytes.Buffer
	res := &fakeResolver{}
	store, mem := newFakeStore(), newMemAlerts()
	unsub := app.NewUnsubscriber(alertBase, "", []byte("k"))
	n := app.NewNotifier(alertTemplates{}, app.NewRenderer(), store, unsub)
	sa := app.NewStockAlerts(mem, n, res, links, unsub, slog.New(slog.NewTextHandler(&logs, nil)),
		app.WithStockAlertLimits(app.StockAlertLimits{MaxOpen: 3}))
	for _, atin := range []string{"ATAAAAAA01", "ATAAAAAA02", "ATAAAAAA03", "ATAAAAAA04", "ATAAAAAA05"} {
		if err := sa.SubscribeGuest(ctx, domain.StockAlert{Atin: atin, Email: "full@example.com"}); err != nil {
			t.Fatalf("%s: the cap must not be visible to the caller: %v", atin, err)
		}
	}
	if len(mem.rows) != 3 {
		t.Fatalf("cap of 3 open alerts per address, got %d", len(mem.rows))
	}
	// Another address is unaffected.
	if err := sa.SubscribeGuest(ctx, domain.StockAlert{Atin: "ATAAAAAA04", Email: "other@example.com"}); err != nil || len(mem.rows) != 4 {
		t.Fatalf("%v %d", err, len(mem.rows))
	}
	if strings.Contains(logs.String(), "full@example.com") {
		t.Fatalf("the address must never be logged in clear: %s", logs.String())
	}
	if !strings.Contains(logs.String(), "f***@example.com") {
		t.Fatalf("the cap hit is logged masked: %s", logs.String())
	}
	// Signed-in shoppers get an honest error at the cap.
	for _, atin := range []string{"ATAAAAAA01", "ATAAAAAA02", "ATAAAAAA03"} {
		if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "u1", Atin: atin}); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "u1", Atin: "ATAAAAAA04"}); !errors.Is(err, domain.ErrAlertLimit) {
		t.Fatalf("signed-in cap: %v", err)
	}
}

func TestBackInStockFansOutToGuestsAndAccountsExactlyOnce(t *testing.T) {
	ctx := context.Background()
	res := &fakeResolver{contacts: map[string]port.Contact{"shopper-1": ada()}}
	sa, mem, store, _ := newAlerts(t, res)
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	_, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "ATGARI0001", ProductTitle: "Gari 1kg", Locale: "fr"})
	must(err)
	must(sa.SubscribeGuest(ctx, domain.StockAlert{Atin: "ATGARI0001", Email: "guest@example.com", Locale: "yo", ProductTitle: "Gari 1kg"}))
	must(sa.SubscribeGuest(ctx, domain.StockAlert{Atin: "ATGARI0001", Email: "guest2@example.com"}))
	must(sa.SubscribeGuest(ctx, domain.StockAlert{Atin: "ATOTHER001", Email: "guest@example.com"}))

	for _, id := range []string{"inv-1", "inv-1", "inv-2"} { // redelivery and a later restock
		must(sa.HandleAvailability(ctx, availabilityEvent(id, 4)))
	}
	got := summary(store.enqueued)
	want := []string{app.StockAlertTemplate + "|email|ada@example.com", app.StockAlertTemplate + "|email|guest2@example.com",
		app.StockAlertTemplate + "|email|guest@example.com", app.StockAlertTemplate + "|push|fcm:tok"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got %v", got)
	}
	locales := map[string]string{}
	links := map[string]bool{}
	for _, r := range store.enqueued {
		if r.Channel != domain.ChannelEmail {
			continue
		}
		locales[r.Address] = r.Locale
		if strings.Contains(r.BodyText, "first=Ada") != (r.Address == "ada@example.com") {
			t.Errorf("only the account has a first name: %s %q", r.Address, r.BodyText)
		}
		_, after, _ := strings.Cut(r.BodyText, "unsub=")
		links[after] = true
	}
	if locales["guest@example.com"] != "yo" || locales["ada@example.com"] != "fr" || locales["guest2@example.com"] != "en" {
		t.Fatalf("each email uses the locale from subscription time: %v", locales)
	}
	if len(links) != 3 {
		t.Fatalf("every alert has its own unsubscribe link: %v", links)
	}
	// Sent alerts are closed; the alert for another variant stays open.
	open := 0
	for _, r := range mem.rows {
		if r.Status == domain.StockAlertActive {
			open++
			if r.Atin != "ATOTHER001" {
				t.Errorf("still open: %+v", r)
			}
		}
	}
	if open != 1 {
		t.Fatalf("open alerts = %d", open)
	}
	// Guests never get a push (they have no device).
	for _, r := range store.enqueued {
		if r.Channel == domain.ChannelPush && r.RecipientID == "" {
			t.Fatalf("push to a guest: %+v", r)
		}
	}
}

func TestStockAlertsIdentityOutageRedelivers(t *testing.T) {
	ctx := context.Background()
	res := &fakeResolver{err: errors.New("timeout")}
	sa, _, store, _ := newAlerts(t, res)
	if _, _, err := sa.Subscribe(ctx, domain.StockAlert{UserID: "shopper-1", Atin: "ATGARI0001"}); err != nil {
		t.Fatal(err)
	}
	if err := sa.HandleAvailability(ctx, availabilityEvent("inv-9", 5)); err == nil {
		t.Fatal("identity failure must be returned")
	}
	res.err, res.contacts = nil, map[string]port.Contact{"shopper-1": ada()}
	// The redelivered event finds the alerts it already claimed and sends them.
	if err := sa.HandleAvailability(ctx, availabilityEvent("inv-9", 5)); err != nil || len(store.enqueued) != 2 {
		t.Fatalf("%v %v", err, summary(store.enqueued))
	}
}

func alertToken(t *testing.T, link string) string {
	t.Helper()
	u, err := url.Parse(link)
	if err != nil || !strings.HasSuffix(u.Path, "/v1/stock-alerts/unsubscribe") {
		t.Fatalf("link %q: %v", link, err)
	}
	return u.Query().Get("t")
}

func TestStockAlertUnsubscribeToken(t *testing.T) {
	ctx := context.Background()
	sa, mem, _, unsub := newAlerts(t, nil)
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	must(sa.SubscribeGuest(ctx, domain.StockAlert{Atin: "ATGARI0001", Email: "a@example.com"}))
	must(sa.SubscribeGuest(ctx, domain.StockAlert{Atin: "ATGARI0002", Email: "a@example.com"}))
	first, second := mem.rows[0].ID, mem.rows[1].ID

	tok := alertToken(t, unsub.StockAlertURL(first, time.Now()))
	must(sa.UnsubscribeByToken(ctx, tok))
	if len(mem.rows) != 1 || mem.rows[0].ID != second {
		t.Fatalf("only the named alert goes: %+v", mem.rows)
	}
	must(sa.UnsubscribeByToken(ctx, tok)) // clicking twice is fine
	if len(mem.rows) != 1 {
		t.Fatalf("%+v", mem.rows)
	}

	good := alertToken(t, unsub.StockAlertURL(second, time.Now()))
	payload, sig, _ := strings.Cut(good, ".")
	other := alertToken(t, app.NewUnsubscriber(alertBase, "", []byte("other-key")).StockAlertURL(second, time.Now()))
	prefs := func() string {
		u, _ := url.Parse(unsub.URL(app.UnsubscribeClaims{RecipientKey: "a@example.com", Address: "a@example.com",
			Channel: domain.ChannelEmail, Category: domain.CategoryMarketing, IssuedAt: time.Now().Unix()}))
		return u.Query().Get("t")
	}()
	for name, bad := range map[string]string{
		"empty": "", "garbage": "x.y", "no signature": payload, "tampered payload": payload + "A." + sig,
		"tampered signature": payload + "." + sig[:len(sig)-2] + "AA", "other key": other, "preferences token": prefs,
		"expired": alertToken(t, unsub.StockAlertURL(second, time.Now().Add(-400*24*time.Hour))),
	} {
		if err := sa.UnsubscribeByToken(ctx, bad); !errors.Is(err, app.ErrInvalidToken) {
			t.Errorf("%s: %v", name, err)
		}
	}
	if len(mem.rows) != 1 {
		t.Fatalf("a rejected token must remove nothing: %+v", mem.rows)
	}
	// And a stock-alert token is no good for the preferences endpoint.
	if _, err := unsub.Verify(good); !errors.Is(err, app.ErrInvalidToken) {
		t.Fatalf("stock-alert token accepted as a preferences token: %v", err)
	}
	must(sa.UnsubscribeByToken(ctx, good))
	if len(mem.rows) != 0 {
		t.Fatalf("%+v", mem.rows)
	}
}

func TestStockAlertPurge(t *testing.T) {
	ctx := context.Background()
	now := time.Date(2026, 11, 4, 12, 0, 0, 0, time.UTC)
	sa, mem, _, _ := newAlerts(t, nil, app.WithStockAlertClock(func() time.Time { return now }))
	sentAt := func(d time.Duration) *time.Time { t := now.Add(-d); return &t }
	day := 24 * time.Hour
	mem.rows = []domain.StockAlert{
		{ID: "open-old", Atin: "ATGARI0001", Email: "a@example.com", Status: domain.StockAlertActive},
		{ID: "open-new", Atin: "ATGARI0002", Email: "a@example.com", Status: domain.StockAlertActive},
		{ID: "sent-old", Atin: "ATGARI0003", Email: "a@example.com", Status: domain.StockAlertNotified, NotifiedAt: sentAt(15 * day)},
		{ID: "sent-new", Atin: "ATGARI0004", Email: "a@example.com", Status: domain.StockAlertNotified, NotifiedAt: sentAt(2 * day)},
	}
	mem.created["open-old"], mem.created["open-new"] = now.Add(-181*day), now.Add(-179*day)
	n, err := sa.Purge(ctx)
	if err != nil || n != 2 {
		t.Fatalf("purged %d: %v", n, err)
	}
	ids := []string{}
	for _, r := range mem.rows {
		ids = append(ids, r.ID)
	}
	if strings.Join(ids, ",") != "open-new,sent-new" {
		t.Fatalf("kept %v", ids)
	}
}
