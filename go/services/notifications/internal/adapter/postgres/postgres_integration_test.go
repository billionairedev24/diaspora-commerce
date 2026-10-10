package postgres_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	tcpostgres "github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/postgres"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/port"
)

// The platform's local Postgres image (PostgreSQL 17); override with NOTIFICATIONS_TEST_PG_IMAGE.
const defaultImage = "postgis/postgis:17-3.5"

var discard = slog.New(slog.NewTextHandler(io.Discard, nil))

func startPostgres(t *testing.T) *pgxpool.Pool {
	t.Helper()
	if testing.Short() || os.Getenv("SKIP_DOCKER_TESTS") == "1" {
		t.Skip("integration test needs Docker")
	}
	image := os.Getenv("NOTIFICATIONS_TEST_PG_IMAGE")
	if image == "" {
		image = defaultImage
	}
	ctx := context.Background()
	ctr, err := tcpostgres.Run(ctx, image, tcpostgres.WithDatabase("notifications"), tcpostgres.WithUsername("notifications"),
		tcpostgres.WithPassword("notifications"), tcpostgres.BasicWaitStrategies())
	if err != nil {
		t.Fatalf("start postgres: %v", err)
	}
	t.Cleanup(func() { _ = ctr.Terminate(context.Background()) })
	dsn, err := ctr.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		t.Fatal(err)
	}
	pool, err := postgres.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	for range 2 { // idempotent
		if err := postgres.Migrate(ctx, pool); err != nil {
			t.Fatal(err)
		}
	}
	return pool
}

// scriptedSender is an email+SMS sender whose outcome per address is set by the test.
type scriptedSender struct {
	name string
	mu   sync.Mutex
	fail map[string]error
	sent []string
}

func (s *scriptedSender) Name() string { return s.name }

func (s *scriptedSender) outcome(to string) (domain.Receipt, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.fail[to]; err != nil {
		return domain.Receipt{}, err
	}
	s.sent = append(s.sent, to)
	return domain.Receipt{ProviderMessageID: s.name + "-" + uuid.NewString()}, nil
}

func (s *scriptedSender) setFail(to string, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.fail[to] = err
}

type emailSender struct{ *scriptedSender }

func (e emailSender) Send(_ context.Context, m domain.EmailMessage) (domain.Receipt, error) {
	return e.outcome(m.To)
}

type smsSender struct{ *scriptedSender }

func (s smsSender) Send(_ context.Context, m domain.SmsMessage) (domain.Receipt, error) {
	return s.outcome(m.To)
}

type env struct {
	pool       *pgxpool.Pool
	store      port.NotificationStore
	templates  port.TemplateStore
	consent    port.ConsentStore
	notifier   app.Notifier
	dispatcher app.Dispatcher
	email      *scriptedSender
	sms        *scriptedSender
	clock      *time.Time
}

func newEnv(t *testing.T, pool *pgxpool.Pool) *env {
	t.Helper()
	now := time.Date(2026, 10, 10, 16, 0, 0, 0, time.UTC) // noon in New York
	e := &env{pool: pool, store: postgres.NewNotificationStore(pool), templates: postgres.NewTemplateStore(pool),
		consent: postgres.NewConsentStore(pool), email: &scriptedSender{name: "resend", fail: map[string]error{}},
		sms: &scriptedSender{name: "twilio", fail: map[string]error{}}, clock: &now}
	checker := postgres.NewConsentCheckerWithClock(pool, domain.DefaultQuietHours, map[string]string{"us": "America/New_York"},
		func() time.Time { return *e.clock })
	router := app.NewRouter(app.Routes{
		Email: map[string][]port.EmailSender{"default": {emailSender{e.email}}},
		SMS:   map[string][]port.SmsSender{"default": {smsSender{e.sms}}},
	}, domain.NewBreakers(domain.BreakerSettings{FailureThreshold: 1000}), time.Second, discard)
	e.notifier = app.NewNotifier(e.templates, app.NewRenderer(), e.store, app.NewUnsubscriber("https://n", "", []byte("k")))
	e.dispatcher = app.NewDispatcher(app.DispatcherConfig{Batch: 50, Lease: time.Minute,
		Retry: domain.RetryPolicy{Base: time.Minute, Max: time.Hour, MaxAttempts: 2}, EmailFrom: "AT <no-reply@at.com>"},
		e.store, checker, router, discard)
	return e
}

func (e *env) makeDue(t *testing.T, id string) {
	t.Helper()
	if _, err := e.pool.Exec(context.Background(), `UPDATE notification SET next_attempt_at = now() - interval '1 second' WHERE id = $1`, id); err != nil {
		t.Fatal(err)
	}
}

func (e *env) get(t *testing.T, id string) domain.Notification {
	t.Helper()
	n, err := e.store.Get(context.Background(), id)
	if err != nil {
		t.Fatal(err)
	}
	return n
}

func (e *env) drain(t *testing.T) {
	t.Helper()
	if _, err := e.dispatcher.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func orderData() map[string]any {
	return map[string]any{"firstName": "Ada", "orderNumber": "AT-1001", "total": "$42.00", "estimatedDelivery": "Tue 14 Oct", "orderUrl": "https://afrotransact.com/o/1"}
}

func TestPostgresIntegration(t *testing.T) {
	pool := startPostgres(t)
	ctx := context.Background()

	t.Run("seeded templates with locale fallback and versioning", func(t *testing.T) {
		e := newEnv(t, pool)
		tpl, err := e.templates.Find(ctx, "identity.verification", domain.LocaleFallbacks("fr-CA"), domain.ChannelSMS, 0)
		if err != nil || tpl.Locale != "en" || tpl.Category != domain.CategorySecurity || tpl.Version != 1 {
			t.Fatalf("%+v %v", tpl, err)
		}
		for _, key := range []string{"order.confirmed", "shipment.out_for_delivery", "payout.requirement_due", "booking.confirmed"} {
			if _, err := e.templates.Find(ctx, key, []string{"en"}, domain.ChannelEmail, 0); err != nil {
				t.Errorf("seed %s: %v", key, err)
			}
		}
		v2, err := e.templates.Create(ctx, domain.Template{Key: "identity.verification", Locale: "en", Channel: domain.ChannelSMS,
			Category: domain.CategorySecurity, BodyText: "Code: {{.code}}", CreatedBy: "staff-1"})
		if err != nil || v2.Version != 2 {
			t.Fatalf("%+v %v", v2, err)
		}
		latest, _ := e.templates.Find(ctx, "identity.verification", []string{"en"}, domain.ChannelSMS, 0)
		v1, _ := e.templates.Find(ctx, "identity.verification", []string{"en"}, domain.ChannelSMS, 1)
		if latest.Version != 2 || v1.Version != 1 || latest.CreatedBy != "staff-1" {
			t.Fatalf("latest %d v1 %d", latest.Version, v1.Version)
		}
		if _, err := e.templates.Find(ctx, "nope", []string{"en"}, domain.ChannelSMS, 0); !errors.Is(err, domain.ErrTemplateNotFound) {
			t.Fatal(err)
		}
	})

	t.Run("submit is idempotent", func(t *testing.T) {
		e := newEnv(t, pool)
		req := domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "orders:AT-1001", Channels: []domain.Channel{domain.ChannelEmail},
			Recipient: domain.Recipient{ID: "u-idem", Email: "idem@example.com"}, Data: orderData()}
		a, err := e.notifier.Submit(ctx, req)
		if err != nil {
			t.Fatal(err)
		}
		b, err := e.notifier.Submit(ctx, req)
		if err != nil || len(a) != 1 || len(b) != 1 || a[0].ID != b[0].ID {
			t.Fatalf("%v %v %v", a, b, err)
		}
		if a[0].Subject != "Order AT-1001 confirmed" || a[0].TemplateVersion != 1 {
			t.Fatalf("%+v", a[0])
		}
	})

	t.Run("inbox de-duplicates redelivered events", func(t *testing.T) {
		e := newEnv(t, pool)
		ingest := app.NewIngest(e.notifier, nil, discard)
		payload, _ := json.Marshal(map[string]any{"recipient": map[string]any{"email": "otp@example.com"}, "code": "246810", "expiresInMinutes": 10})
		ev := events.Envelope{ID: "evt-" + uuid.NewString(), Type: "identity.verification.requested", AggregateID: "user-otp", Payload: payload}
		for range 3 {
			if err := ingest.Handle(ctx, ev); err != nil {
				t.Fatal(err)
			}
		}
		rows, err := e.store.List(ctx, domain.ListFilter{Recipient: "otp@example.com", Limit: 10})
		if err != nil || len(rows) != 1 || rows[0].EventID != ev.ID || rows[0].Subject != "Your AfroTransact verification code: 246810" {
			t.Fatalf("%+v %v", rows, err)
		}
		inbox := postgres.NewInbox(pool)
		first, _ := inbox.FirstDelivery(ctx, "other-consumer", ev.ID)
		again, _ := inbox.FirstDelivery(ctx, "other-consumer", ev.ID)
		if !first || again {
			t.Fatal("events.Inbox semantics")
		}
	})

	t.Run("claim skips rows locked by another worker", func(t *testing.T) {
		e := newEnv(t, pool)
		if _, err := pool.Exec(ctx, `UPDATE notification SET status = 'sent' WHERE status = 'queued'`); err != nil {
			t.Fatal(err)
		}
		var ids []string
		for i := range 10 {
			out, err := e.notifier.Submit(ctx, domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "lock-" + uuid.NewString(),
				Channels: []domain.Channel{domain.ChannelEmail}, Recipient: domain.Recipient{Email: "lock" + string(rune('a'+i)) + "@example.com"}, Data: orderData()})
			if err != nil {
				t.Fatal(err)
			}
			ids = append(ids, out[0].ID)
		}
		tx, err := pool.Begin(ctx)
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = tx.Rollback(ctx) }()
		if _, err := tx.Exec(ctx, `SELECT id FROM notification WHERE id = ANY($1) FOR UPDATE`, ids[:4]); err != nil {
			t.Fatal(err)
		}
		claimed, err := e.store.Claim(ctx, 100, time.Minute)
		if err != nil {
			t.Fatal(err)
		}
		if len(claimed) != 6 {
			t.Fatalf("want the 6 unlocked rows, got %d", len(claimed))
		}
		for _, c := range claimed {
			for _, locked := range ids[:4] {
				if c.ID == locked {
					t.Fatal("claimed a row locked by another transaction")
				}
			}
		}
		if again, _ := e.store.Claim(ctx, 100, time.Minute); len(again) != 0 {
			t.Fatalf("leased rows must not be re-claimed, got %d", len(again))
		}
		_ = tx.Rollback(ctx)

		// Concurrent workers claim disjoint sets.
		if _, err := pool.Exec(ctx, `UPDATE notification SET next_attempt_at = now() - interval '1s' WHERE id = ANY($1)`, ids); err != nil {
			t.Fatal(err)
		}
		var mu sync.Mutex
		var all []string
		var wg sync.WaitGroup
		for range 4 {
			wg.Go(func() {
				got, err := e.store.Claim(ctx, 3, time.Minute)
				if err != nil {
					t.Error(err)
					return
				}
				mu.Lock()
				for _, g := range got {
					all = append(all, g.ID)
				}
				mu.Unlock()
			})
		}
		wg.Wait()
		sort.Strings(all)
		for i := 1; i < len(all); i++ {
			if all[i] == all[i-1] {
				t.Fatal("two workers claimed the same notification")
			}
		}
		if len(all) != 10 {
			t.Fatalf("claimed %d of 10", len(all))
		}
		if _, err := pool.Exec(ctx, `UPDATE notification SET status = 'sent' WHERE id = ANY($1)`, ids); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("dispatcher sends, retries with backoff, dead-letters and replays", func(t *testing.T) {
		e := newEnv(t, pool)
		submit := func(email string) string {
			out, err := e.notifier.Submit(ctx, domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "d-" + uuid.NewString(),
				Channels: []domain.Channel{domain.ChannelEmail}, Recipient: domain.Recipient{Email: email}, Data: orderData()})
			if err != nil {
				t.Fatal(err)
			}
			return out[0].ID
		}
		ok, flaky, bad := submit("ok@example.com"), submit("flaky@example.com"), submit("bad@example.com")
		e.email.setFail("flaky@example.com", domain.RetryableError("resend", 503, "", nil))
		e.email.setFail("bad@example.com", domain.PermanentError("resend", 422, "validation_error", nil))
		e.drain(t)

		if n := e.get(t, ok); n.Status != domain.StatusSent || n.Provider != "resend" || n.ProviderMessageID == "" || n.SentAt == nil {
			t.Fatalf("ok: %+v", n)
		}
		f := e.get(t, flaky)
		if f.Status != domain.StatusQueued || f.Attempts != 1 || !f.NextAttemptAt.After(time.Now().Add(30*time.Second)) || f.LastError == "" {
			t.Fatalf("flaky must be re-queued with backoff: %+v", f)
		}
		if b := e.get(t, bad); b.Status != domain.StatusFailed || b.Attempts != 1 {
			t.Fatalf("bad: %+v", b)
		}

		e.makeDue(t, flaky)
		e.drain(t)
		if f := e.get(t, flaky); f.Status != domain.StatusFailed || f.Attempts != 2 {
			t.Fatalf("flaky must be dead-lettered after max attempts: %+v", f)
		}
		dls, err := e.store.DeadLetters(ctx, 50)
		if err != nil {
			t.Fatal(err)
		}
		byNotification := map[string]domain.DeadLetter{}
		for _, d := range dls {
			byNotification[d.NotificationID] = d
		}
		if _, okBad := byNotification[bad]; !okBad {
			t.Fatal("permanent failure missing from DLQ")
		}
		dl, okFlaky := byNotification[flaky]
		if !okFlaky {
			t.Fatal("exhausted retry missing from DLQ")
		}

		e.email.setFail("flaky@example.com", nil)
		replayed, err := e.store.Replay(ctx, dl.ID)
		if err != nil || replayed.Status != domain.StatusQueued || replayed.Attempts != 0 {
			t.Fatalf("%+v %v", replayed, err)
		}
		if _, err := e.store.Replay(ctx, dl.ID); !errors.Is(err, domain.ErrNotFound) {
			t.Fatal("a dead letter replays once")
		}
		e.drain(t)
		if f := e.get(t, flaky); f.Status != domain.StatusSent {
			t.Fatalf("replayed: %+v", f)
		}
	})

	t.Run("consent: SMS needs TCPA consent, STOP/START, quiet hours, OTP bypass", func(t *testing.T) {
		e := newEnv(t, pool)
		phone := "+15557770001"
		submitSms := func(key string, data map[string]any) string {
			out, err := e.notifier.Submit(ctx, domain.Request{TemplateKey: key, IdempotencyKey: "c-" + uuid.NewString(), Market: "us",
				Channels: []domain.Channel{domain.ChannelSMS}, Recipient: domain.Recipient{ID: "user-c", Phone: phone}, Data: data})
			if err != nil {
				t.Fatal(err)
			}
			return out[0].ID
		}
		noConsent := submitSms("order.confirmed", orderData())
		e.drain(t)
		if n := e.get(t, noConsent); n.Status != domain.StatusSuppressed || n.StatusDetail != "no SMS consent on record" {
			t.Fatalf("%+v", n)
		}

		prefs := app.NewPreferences(e.consent, app.NewUnsubscriber("https://n", "", []byte("k")))
		if err := prefs.RecordSmsConsent(ctx, domain.SmsConsent{Phone: phone, RecipientID: "user-c", Source: "web-signup", TextVersion: "sms-terms-2026-01"}); err != nil {
			t.Fatal(err)
		}
		withConsent := submitSms("order.confirmed", orderData())
		e.drain(t)
		if n := e.get(t, withConsent); n.Status != domain.StatusSent {
			t.Fatalf("%+v", n)
		}

		compliance := app.NewSmsCompliance(e.consent, app.ComplianceTexts{Stop: "bye", Start: "hi", Help: "help", TextVersion: "sms-terms-2026-01"})
		if reply, err := compliance.HandleInbound(ctx, phone, "Stop"); err != nil || reply != "bye" {
			t.Fatal(reply, err)
		}
		afterStop := submitSms("order.confirmed", orderData())
		otp := submitSms("identity.verification", map[string]any{"code": "123456", "expiresInMinutes": 10})
		e.drain(t)
		if n := e.get(t, afterStop); n.Status != domain.StatusSuppressed {
			t.Fatalf("after STOP: %+v", n)
		}
		if n := e.get(t, otp); n.Status != domain.StatusSent {
			t.Fatalf("OTP must always send: %+v", n)
		}
		c, _ := e.consent.SmsConsent(ctx, phone)
		if c == nil || c.OptedIn || c.RevokedVia != "sms-STOP" || c.Source != "web-signup" {
			t.Fatalf("%+v", c)
		}
		var auditRows int
		_ = pool.QueryRow(ctx, `SELECT count(*) FROM sms_consent_event WHERE phone = $1`, phone).Scan(&auditRows)
		if auditRows != 2 {
			t.Fatalf("TCPA audit trail rows: %d", auditRows)
		}

		if _, err := compliance.HandleInbound(ctx, phone, "START"); err != nil {
			t.Fatal(err)
		}
		*e.clock = time.Date(2026, 10, 11, 2, 0, 0, 0, time.UTC) // 22:00 in New York
		reminder := submitSms("payout.requirement_due", map[string]any{"dueDate": "Oct 20", "requirement": "ID document", "actionUrl": "https://s"})
		e.drain(t)
		n := e.get(t, reminder)
		wantUntil := time.Date(2026, 10, 11, 12, 0, 0, 0, time.UTC) // 08:00 EDT
		if n.Status != domain.StatusQueued || !n.NextAttemptAt.Equal(wantUntil) || n.Attempts != 0 {
			t.Fatalf("reminder must wait for the end of quiet hours: %+v", n)
		}
	})

	t.Run("receipts never move status backwards and suppress on bounce", func(t *testing.T) {
		e := newEnv(t, pool)
		out, err := e.notifier.Submit(ctx, domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "r-" + uuid.NewString(),
			Channels: []domain.Channel{domain.ChannelEmail}, Recipient: domain.Recipient{Email: "receipt@example.com"}, Data: orderData()})
		if err != nil {
			t.Fatal(err)
		}
		id := out[0].ID
		e.drain(t)
		n := e.get(t, id)
		receipts := app.NewReceipts(e.store, e.consent, discard)
		apply := func(st domain.Status) {
			if _, err := receipts.Apply(ctx, []domain.DeliveryEvent{{Provider: "resend", ProviderMessageID: n.ProviderMessageID,
				Status: st, Address: "receipt@example.com"}}); err != nil {
				t.Fatal(err)
			}
		}
		apply(domain.StatusDelivered)
		apply(domain.StatusSent) // late, out of order
		if got := e.get(t, id).Status; got != domain.StatusDelivered {
			t.Fatalf("status regressed to %s", got)
		}
		apply(domain.StatusComplained)
		if got := e.get(t, id).Status; got != domain.StatusComplained {
			t.Fatal(got)
		}
		sup, _ := e.consent.Suppressions(ctx, "receipt@example.com", 10)
		if len(sup) != 1 || sup[0].Reason != "complained" {
			t.Fatalf("%+v", sup)
		}
		next, _ := e.notifier.Submit(ctx, domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "r2-" + uuid.NewString(),
			Channels: []domain.Channel{domain.ChannelEmail}, Recipient: domain.Recipient{Email: "receipt@example.com"}, Data: orderData()})
		e.drain(t)
		if got := e.get(t, next[0].ID); got.Status != domain.StatusSuppressed {
			t.Fatalf("suppressed address must not receive mail: %+v", got)
		}

		// A webhook that beats MarkSent is kept: the receipt is stored and MarkSent picks it up.
		early := "early-" + uuid.NewString()
		if _, err := e.store.ApplyDeliveryEvent(ctx, domain.DeliveryEvent{Provider: "twilio", ProviderMessageID: early,
			Status: domain.StatusDelivered, OccurredAt: time.Now()}); err != nil {
			t.Fatal(err)
		}
		other, _ := e.notifier.Submit(ctx, domain.Request{TemplateKey: "order.confirmed", IdempotencyKey: "r3-" + uuid.NewString(),
			Channels: []domain.Channel{domain.ChannelEmail}, Recipient: domain.Recipient{Email: "race@example.com"}, Data: orderData()})
		if err := e.store.MarkSent(ctx, other[0].ID, domain.Receipt{Provider: "twilio", ProviderMessageID: early, AcceptedAt: time.Now()}); err != nil {
			t.Fatal(err)
		}
		if got := e.get(t, other[0].ID).Status; got != domain.StatusDelivered {
			t.Fatalf("early receipt lost: %s", got)
		}
	})

	t.Run("preferences and one-click unsubscribe", func(t *testing.T) {
		e := newEnv(t, pool)
		unsub := app.NewUnsubscriber("https://n", "", []byte("k"))
		prefs := app.NewPreferences(e.consent, unsub)
		if _, err := e.templates.Create(ctx, domain.Template{Key: "promo.weekly", Locale: "en", Channel: domain.ChannelEmail,
			Category: domain.CategoryMarketing, Subject: "Deals", BodyText: "Deals", BodyHTML: "<p>Deals</p>"}); err != nil {
			t.Fatal(err)
		}
		send := func() domain.Notification {
			out, err := e.notifier.Submit(ctx, domain.Request{TemplateKey: "promo.weekly", IdempotencyKey: "m-" + uuid.NewString(),
				Channels: []domain.Channel{domain.ChannelEmail}, Recipient: domain.Recipient{ID: "user-m", Email: "m@example.com"}})
			if err != nil {
				t.Fatal(err)
			}
			e.drain(t)
			return e.get(t, out[0].ID)
		}
		if n := send(); n.Status != domain.StatusSuppressed {
			t.Fatalf("marketing without opt-in: %+v", n)
		}
		if err := prefs.Set(ctx, "user-m", []domain.Preference{{Channel: domain.ChannelEmail, Category: domain.CategoryMarketing, Enabled: true}}); err != nil {
			t.Fatal(err)
		}
		n := send()
		if n.Status != domain.StatusSent || n.Headers["List-Unsubscribe-Post"] != "List-Unsubscribe=One-Click" {
			t.Fatalf("%+v", n)
		}
		link := n.Headers["List-Unsubscribe"]
		tok := link[len("<https://n/v1/unsubscribe?t=") : len(link)-1]
		if _, err := prefs.Unsubscribe(ctx, tok); err != nil {
			t.Fatal(err)
		}
		if n := send(); n.Status != domain.StatusSuppressed {
			t.Fatalf("after one-click unsubscribe: %+v", n)
		}
		got, _ := prefs.Get(ctx, "user-m")
		for _, p := range got {
			if p.Channel == domain.ChannelEmail && p.Category == domain.CategoryMarketing && p.Enabled {
				t.Fatal("preference not updated")
			}
		}
	})
}
