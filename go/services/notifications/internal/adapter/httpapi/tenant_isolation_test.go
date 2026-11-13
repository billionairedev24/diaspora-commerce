package httpapi_test

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/tenanttest"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// tenantPrefs keeps preferences per recipient, like the store does.
type tenantPrefs struct {
	app.Preferences
	by map[string][]domain.Preference
}

func (p *tenantPrefs) Get(_ context.Context, id string) ([]domain.Preference, error) {
	return append([]domain.Preference{}, p.by[id]...), nil
}

func (p *tenantPrefs) Set(_ context.Context, id string, prefs []domain.Preference) error {
	p.by[id] = prefs
	return nil
}

type tenantAlerts struct {
	app.StockAlerts
	by map[string][]domain.StockAlert
}

func (a *tenantAlerts) List(_ context.Context, user string) ([]domain.StockAlert, error) {
	return a.by[user], nil
}

type tenantAlertPrefs struct{ app.AlertPreferences }

func (tenantAlertPrefs) Get(context.Context, string, domain.AlertAudience) (domain.AlertSettings, error) {
	return domain.AlertSettings{}, nil
}

func TestCrossTenantPreferencesAlertsAndStaffRoutes(t *testing.T) {
	// No notifications permission goes to sellers or shoppers: the routes below are staff and service only.
	w := tenanttest.NewWorld(tenanttest.StaffPerms(httpapi.PermRead, httpapi.PermManage, httpapi.PermSend),
		tenanttest.ServicePerms(httpapi.PermSend, httpapi.PermStockAlertGuest))
	prefs := &tenantPrefs{by: map[string][]domain.Preference{w.CustomerB.Actor.Subject: {{Channel: "email", Category: "marketing", Enabled: true}}}}
	alerts := &tenantAlerts{by: map[string][]domain.StockAlert{w.CustomerB.Actor.Subject: {{UserID: w.CustomerB.Actor.Subject, Atin: "AT-B-SECRET"}}}}
	mux := http.NewServeMux()
	httpapi.New(httpapi.Deps{Preferences: prefs, Alerts: tenantAlertPrefs{}, StockAlerts: alerts, Log: &fakeLog{},
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}).Register(mux, w.Auth(false))

	b := w.CustomerB.Actor.Subject
	attackers := []tenanttest.Principal{w.CustomerA, w.SellerA, w.SellerB, w.Anonymous}
	tenanttest.CrossTenant(t, mux, attackers, []tenanttest.Request{
		tenanttest.Get("/v1/preferences/" + b),
		tenanttest.Send(http.MethodPut, "/v1/preferences/"+b, `{"preferences":[{"channel":"email","category":"marketing","enabled":false}]}`),
		tenanttest.Get("/v1/preferences/" + b + "/alerts"),
		tenanttest.Send(http.MethodPut, "/v1/preferences/"+b+"/alerts", `{"preferences":[]}`),
	}, "marketing", "AT-B-SECRET")
	if got := prefs.by[b]; len(got) != 1 || !got[0].Enabled {
		t.Fatalf("another caller changed the preferences: %+v", got)
	}

	// Staff-only and service-only routes refuse every tenant principal.
	tenanttest.CrossTenant(t, mux, []tenanttest.Principal{w.CustomerA, w.SellerA, w.Anonymous}, []tenanttest.Request{
		tenanttest.Get("/v1/notifications"),
		tenanttest.Get("/v1/notifications/n1"),
		tenanttest.Get("/v1/notifications?recipient=" + b),
		tenanttest.Get("/v1/dead-letters"),
		tenanttest.Send(http.MethodPost, "/v1/dead-letters/d1/replay", ""),
		tenanttest.Send(http.MethodPost, "/v1/notifications", `{}`).WithHeader("Idempotency-Key", "k"),
		tenanttest.Get("/v1/consents/sms/+15551230000"),
		tenanttest.Send(http.MethodPost, "/v1/consents/sms", `{"phone":"+15551230000"}`),
		tenanttest.Get("/v1/suppressions"),
		tenanttest.Send(http.MethodDelete, "/v1/suppressions/x@example.com", ""),
		tenanttest.Get("/v1/templates"),
		tenanttest.Send(http.MethodPost, "/v1/templates", `{}`),
		tenanttest.Send(http.MethodPost, "/v1/stock-alerts/guest", `{"atin":"AT1","email":"a@example.com"}`),
	})

	tenanttest.Run(t, mux, []tenanttest.Case{
		{Name: "own preferences", As: w.CustomerB, Req: tenanttest.Get("/v1/preferences/" + b), Expect: tenanttest.Contains("marketing")},
		{Name: "staff with notifications:manage", As: w.Staff, Req: tenanttest.Get("/v1/preferences/" + b), Expect: tenanttest.Allowed()},
		{Name: "own stock alerts", As: w.CustomerB, Req: tenanttest.Get("/v1/stock-alerts"), Expect: tenanttest.Contains("AT-B-SECRET")},
		{Name: "a shopper's stock alerts are keyed by token, not by query", As: w.CustomerA,
			Req: tenanttest.Get("/v1/stock-alerts?userId=" + b), Expect: tenanttest.All(tenanttest.Allowed(), tenanttest.Leaks("AT-B-SECRET"))},
		{Name: "services hold no shopper identity", As: w.Service, Req: tenanttest.Get("/v1/stock-alerts"), Expect: tenanttest.Status(http.StatusForbidden)},
	})
}

func TestWebhooksAndUnsubscribeNeedTheirCredential(t *testing.T) {
	w := tenanttest.NewWorld()
	mux := http.NewServeMux()
	// A nil verifier means the webhook is not configured: it answers 503 and applies nothing.
	httpapi.New(httpapi.Deps{StockAlerts: &tenantAlerts{}, Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}).Register(mux, w.Auth(false))
	for _, path := range []string{"/v1/webhooks/resend", "/v1/webhooks/sendgrid", "/v1/webhooks/twilio/status", "/v1/webhooks/twilio/inbound"} {
		res := tenanttest.Do(mux, w.Anonymous, tenanttest.Send(http.MethodPost, path, `{"type":"email.bounced"}`))
		if res.Status != http.StatusServiceUnavailable && res.Status != http.StatusUnauthorized {
			t.Fatalf("%s accepted an unsigned webhook: %d", path, res.Status)
		}
	}
}

func TestRouteMatrix(t *testing.T) {
	m, err := notifications.Files.ReadFile("tenant_isolation_matrix.txt")
	if err != nil {
		t.Fatal(err)
	}
	tenanttest.CheckRoutes(t, tenanttest.PatternsFromSource(t, "."), string(m), ".")
}
