package httpapi_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/httpapi"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

// fakeStockAlerts records calls; the use case itself is tested in internal/app.
type fakeStockAlerts struct {
	subscribed []domain.StockAlert
	deleted    []string
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
