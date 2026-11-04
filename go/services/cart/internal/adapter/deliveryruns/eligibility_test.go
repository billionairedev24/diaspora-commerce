package deliveryruns_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/adapter/deliveryruns"
	"github.com/billionairedev24/diaspora-commerce/go/services/cart/internal/domain"
)

func TestEligibilityUsesAServiceTokenAndDefaultsFees(t *testing.T) {
	tokens := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/oauth2/token":
			tokens++
			if u, p, ok := r.BasicAuth(); !ok || u != "cart-service" || p != "secret" ||
				r.FormValue("scope") != "delivery-runs:reserve" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			_, _ = w.Write([]byte(`{"access_token":"tok","expires_in":3600}`))
		case "/v1/service/preorders/eligibility":
			if r.Header.Get("Authorization") != "Bearer tok" || r.URL.Query().Get("city") != "Lubbock" ||
				r.URL.Query().Get("atins") != "A1,A2,A3" {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			_, _ = w.Write([]byte(`{"items":[
			  {"atin":"A1","preorder":true,"windowId":"w1","stage":"OPEN","deliversOn":"2026-10-25","pickup":true},
			  {"atin":"A2","preorder":true,"window":{"id":"w1","name":"Lubbock #7","stage":"open","doorFee":650,
			   "currency":"USD"},"allowedMethods":["CARD"]},
			  {"atin":"A3","preorder":false}]}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()
	e := deliveryruns.New(deliveryruns.Options{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token",
		ClientID: "cart-service", ClientSecret: "secret"}, srv.Client())
	loc := domain.Location{Country: "US", Region: "TX", City: "Lubbock"}
	for range 2 {
		got, err := e.Eligibility(context.Background(), loc, []string{"A1", "A2", "A3"})
		if err != nil {
			t.Fatal(err)
		}
		a1, a2, a3 := got["A1"], got["A2"], got["A3"]
		if !a1.Preorder || a1.DoorFeeMinor != 799 || a1.PickupFeeMinor != 0 || !a1.Door || !a1.Pickup || a1.Currency != "USD" {
			t.Fatalf("A1 defaults: %+v", a1)
		}
		if !a2.Preorder || a2.WindowID != "w1" || a2.Stage != "OPEN" || a2.DoorFeeMinor != 650 || a2.WindowName != "Lubbock #7" ||
			len(a2.AllowedMethods) != 1 {
			t.Fatalf("A2 nested window: %+v", a2)
		}
		if a3.Preorder {
			t.Fatalf("A3 ships now: %+v", a3)
		}
	}
	if tokens != 1 {
		t.Fatalf("token cached: %d requests", tokens)
	}
}

func TestEligibilityKeepsTheFeeRulesBehindTheDoorAndPickupFees(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/oauth2/token" {
			_, _ = w.Write([]byte(`{"access_token":"tok","expires_in":3600}`))
			return
		}
		_, _ = w.Write([]byte(`{"items":[
		  {"atin":"A1","preorder":true,"windowId":"w1","stage":"OPEN","door":true,"pickup":true,
		   "doorFeeMinor":899,"pickupFeeMinor":100,"currency":"USD",
		   "doorFeeRule":{"method":"AFROTRANSACT_DELIVERY","ruleId":"r-1","ruleVersion":3,"currency":"USD",
		    "amountMinor":899,"source":"RULE","band":"10–40 mi","distanceMiles":32},
		   "pickupFeeRule":{"method":"PICKUP","ruleVersion":2,"amountMinor":100,"source":"WINDOW_OVERRIDE"}},
		  {"atin":"A2","preorder":true,"windowId":"w2","stage":"OPEN","doorFeeMinor":799,
		   "window":{"doorFeeRule":{"method":"AFROTRANSACT_DELIVERY","ruleVersion":1,"amountMinor":799,"source":"RULE"}}},
		  {"atin":"A3","preorder":true,"windowId":"w3","stage":"OPEN","doorFeeMinor":799}]}`))
	}))
	defer srv.Close()
	e := deliveryruns.New(deliveryruns.Options{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token",
		ClientID: "cart-service", ClientSecret: "secret"}, srv.Client())
	got, err := e.Eligibility(context.Background(), domain.Location{Country: "US", Region: "TX", City: "Lubbock"},
		[]string{"A1", "A2", "A3"})
	if err != nil {
		t.Fatal(err)
	}
	door := got["A1"].DoorFeeRule
	if door == nil || door.Method != "AFROTRANSACT_DELIVERY" || door.RuleID != "r-1" || door.RuleVersion != 3 ||
		door.Band != "10–40 mi" || door.DistanceMiles == nil || *door.DistanceMiles != 32 || door.AmountMinor != 899 {
		t.Fatalf("door rule: %+v", door)
	}
	if p := got["A1"].PickupFeeRule; p == nil || p.Method != "PICKUP" || p.Source != "WINDOW_OVERRIDE" || p.RuleVersion != 2 {
		t.Fatalf("pickup rule: %+v", p)
	}
	if r := got["A2"].DoorFeeRule; r == nil || r.RuleVersion != 1 {
		t.Fatalf("rule inside window: %+v", r)
	}
	if got["A3"].DoorFeeRule != nil || got["A3"].PickupFeeRule != nil {
		t.Fatalf("no rules sent, none kept: %+v", got["A3"])
	}
}
