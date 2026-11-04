package httpapi_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// fakeStockAlerts records calls; the use case itself is tested in internal/app.
type fakeStockAlerts struct {
	subscribed []domain.StockAlert
	deleted    []string
	guests     []domain.StockAlert
	removed    []string
	unsub      app.Unsubscriber
}

func (f *fakeStockAlerts) Subscribe(_ context.Context, a domain.StockAlert) (domain.StockAlert, bool, error) {
	a, err := a.Validate()
	if err != nil {
		return a, false, err
	}
	for _, s := range f.subscribed {
		if s.UserID == a.UserID && s.Atin == a.Atin {
			return s, false, nil
		}
	}
	a.ID, a.Status = "sa-1", domain.StockAlertActive
	f.subscribed = append(f.subscribed, a)
	return a, true, nil
}

func (f *fakeStockAlerts) Unsubscribe(_ context.Context, userID, atin string) error {
	if atin != "ATGARI0001" {
		return domain.ErrNotFound
	}
	f.deleted = append(f.deleted, userID+"/"+atin)
	return nil
}

func (f *fakeStockAlerts) List(_ context.Context, userID string) ([]domain.StockAlert, error) {
	var out []domain.StockAlert
	for _, s := range f.subscribed {
		if s.UserID == userID {
			out = append(out, s)
		}
	}
	return out, nil
}

func (f *fakeStockAlerts) HandleAvailability(context.Context, events.Envelope) error { return nil }

func (f *fakeStockAlerts) SubscribeGuest(_ context.Context, a domain.StockAlert) error {
	a, err := a.Validate()
	if err != nil {
		return err
	}
	f.guests = append(f.guests, a)
	return nil
}

func (f *fakeStockAlerts) UnsubscribeByToken(_ context.Context, token string) error {
	c, err := f.unsub.VerifyStockAlert(token)
	if err != nil {
		return err
	}
	f.removed = append(f.removed, c.AlertID)
	return nil
}

func (f *fakeStockAlerts) Purge(context.Context) (int64, error) { return 0, nil }

func TestStockAlertRoutes(t *testing.T) {
	sa := &fakeStockAlerts{}
	shopper := auth.Actor{Subject: "shopper-1", Type: "CUSTOMER"}
	do := func(a auth.Actor, method, path, body string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux(httpapi.Deps{StockAlerts: sa}, a).ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), method, path, strings.NewReader(body)))
		return rec
	}
	if rec := do(shopper, "POST", "/v1/stock-alerts", `{"atin":"atgari0001","email":"ada@example.com","productTitle":"Gari"}`); rec.Code != http.StatusCreated {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	if rec := do(shopper, "POST", "/v1/stock-alerts", `{"atin":"ATGARI0001"}`); rec.Code != http.StatusOK {
		t.Fatalf("repeat subscribe must be 200: %d", rec.Code)
	}
	if rec := do(shopper, "POST", "/v1/stock-alerts", `{"atin":"BAD"}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("invalid atin: %d", rec.Code)
	}
	if rec := do(auth.Actor{Subject: "gateway-bff", Type: "SERVICE"}, "POST", "/v1/stock-alerts", `{"atin":"ATGARI0001"}`); rec.Code != http.StatusForbidden {
		t.Fatalf("service tokens are refused: %d", rec.Code)
	}
	rec := do(shopper, "GET", "/v1/stock-alerts", "")
	var list struct {
		Items []map[string]any `json:"items"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &list)
	if rec.Code != http.StatusOK || len(list.Items) != 1 || list.Items[0]["atin"] != "ATGARI0001" || list.Items[0]["status"] != "active" ||
		list.Items[0]["userId"] != nil {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	if rec := do(auth.Actor{Subject: "someone-else", Type: "CUSTOMER"}, "GET", "/v1/stock-alerts", ""); !strings.Contains(rec.Body.String(), `"items":null`) &&
		!strings.Contains(rec.Body.String(), `"items":[]`) {
		t.Fatalf("only my alerts: %s", rec.Body)
	}
	if rec := do(shopper, "DELETE", "/v1/stock-alerts/ATGARI0001", ""); rec.Code != http.StatusNoContent || sa.deleted[0] != "shopper-1/ATGARI0001" {
		t.Fatalf("%d %v", rec.Code, sa.deleted)
	}
	if rec := do(shopper, "DELETE", "/v1/stock-alerts/ATNOPE0000", ""); rec.Code != http.StatusNotFound {
		t.Fatalf("%d", rec.Code)
	}
}

func TestGuestSubscribeNeedsTheServiceScope(t *testing.T) {
	sa := &fakeStockAlerts{}
	body := `{"atin":"atgari0001","email":"Guest@Example.com","locale":"fr","productTitle":"Gari"}`
	do := func(a auth.Actor, body string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux(httpapi.Deps{StockAlerts: sa}, a).ServeHTTP(rec, httptest.NewRequestWithContext(context.Background(), "POST", "/v1/stock-alerts/guest", strings.NewReader(body)))
		return rec
	}
	bff := auth.Actor{Subject: "gateway-bff", Type: "SERVICE", Permissions: []string{httpapi.PermStockAlertGuest}}
	rec := do(bff, body)
	if rec.Code != http.StatusCreated || strings.TrimSpace(rec.Body.String()) != `{"atin":"ATGARI0001","status":"subscribed"}` {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	if len(sa.guests) != 1 || sa.guests[0].Email != "guest@example.com" || sa.guests[0].UserID != "" || sa.guests[0].Locale != "fr" {
		t.Fatalf("%+v", sa.guests)
	}
	// Same answer for a repeat, and the address is never echoed.
	if rec := do(bff, body); rec.Code != http.StatusCreated || strings.Contains(strings.ToLower(rec.Body.String()), "guest") {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	// A bad address is a 400 problem that does not repeat the address.
	if rec := do(bff, `{"atin":"ATGARI0001","email":"secret@@nope"}`); rec.Code != http.StatusBadRequest || strings.Contains(rec.Body.String(), "secret") {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	// Shoppers, other services and anonymous callers are refused.
	for name, a := range map[string]auth.Actor{
		"shopper":       {Subject: "shopper-1", Type: "CUSTOMER"},
		"other service": {Subject: "orders", Type: "SERVICE", Permissions: []string{"notifications:send"}},
		"staff":         {Subject: "s", Type: "STAFF", Permissions: []string{httpapi.PermManage}},
	} {
		if rec := do(a, body); rec.Code != http.StatusForbidden {
			t.Errorf("%s: %d", name, rec.Code)
		}
	}
	if len(sa.guests) != 2 {
		t.Fatalf("refused calls must store nothing: %+v", sa.guests)
	}
}

func TestStockAlertUnsubscribeLink(t *testing.T) {
	unsub := app.NewUnsubscriber("https://n.test", "", []byte("k"))
	sa := &fakeStockAlerts{unsub: unsub}
	link := func(id string, at time.Time) string {
		return strings.TrimPrefix(unsub.StockAlertURL(id, at), "https://n.test")
	}
	do := func(method, target, accept, lang string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		req := httptest.NewRequestWithContext(context.Background(), method, target, strings.NewReader("List-Unsubscribe=One-Click"))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("Accept", accept)
		req.Header.Set("Accept-Language", lang)
		// No authentication at all: the signed token is the credential.
		mux(httpapi.Deps{StockAlerts: sa, Unsubscriber: unsub}, auth.Actor{}).ServeHTTP(rec, req)
		return rec
	}
	good := link("alert-1", time.Now())

	// GET only asks: link scanners must not remove anything.
	rec := do("GET", good, "text/html", "fr-FR,fr;q=0.9")
	if rec.Code != http.StatusOK || len(sa.removed) != 0 || !strings.Contains(rec.Body.String(), `lang="fr"`) ||
		!strings.Contains(rec.Body.String(), "Arrêter l&#39;alerte") || rec.Header().Get("Referrer-Policy") != "no-referrer" ||
		rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("%d removed=%v %s %v", rec.Code, sa.removed, rec.Body, rec.Header())
	}
	// The mail client's RFC 8058 POST removes it with no login and answers 200.
	if rec := do("POST", good, "*/*", ""); rec.Code != http.StatusOK || len(sa.removed) != 1 || sa.removed[0] != "alert-1" {
		t.Fatalf("%d %v", rec.Code, sa.removed)
	}
	// The page's own form shows the localised confirmation (Arabic is right-to-left).
	rec = do("POST", good, "text/html", "ar")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `dir="rtl"`) || len(sa.removed) != 2 {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	// Tampered, foreign and expired links change nothing and say so in plain words.
	for name, bad := range map[string]string{
		"tampered": good[:len(good)-3] + "AAA", "garbage": "/v1/stock-alerts/unsubscribe?t=abc", "missing": "/v1/stock-alerts/unsubscribe",
		"expired": link("alert-2", time.Now().Add(-400*24*time.Hour)),
		"prefs token": strings.Replace(unsub.URL(app.UnsubscribeClaims{RecipientKey: "x", Address: "x@example.com",
			Channel: domain.ChannelEmail, Category: domain.CategoryMarketing, IssuedAt: time.Now().Unix()}), "https://n.test/v1/unsubscribe", "/v1/stock-alerts/unsubscribe", 1),
	} {
		if rec := do("GET", bad, "text/html", "en"); rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "invalid or has expired") {
			t.Errorf("GET %s: %d %s", name, rec.Code, rec.Body)
		}
		if rec := do("POST", bad, "text/html", "en"); rec.Code != http.StatusBadRequest {
			t.Errorf("POST html %s: %d", name, rec.Code)
		}
		if rec := do("POST", bad, "application/json", "en"); rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "notifications.invalid-token") {
			t.Errorf("POST json %s: %d %s", name, rec.Code, rec.Body)
		}
	}
	if len(sa.removed) != 2 {
		t.Fatalf("a rejected link removed something: %v", sa.removed)
	}
}
