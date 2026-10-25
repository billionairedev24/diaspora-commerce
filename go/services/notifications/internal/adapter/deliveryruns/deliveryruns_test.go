package deliveryruns_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/adapter/deliveryruns"
	"github.com/billionairedev24/diaspora-commerce/go/services/notifications/internal/domain"
)

func TestAudienceUsesClientCredentialsAndPages(t *testing.T) {
	var gotAuth, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/oauth2/token":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"access_token":"tok","token_type":"Bearer","expires_in":300}`))
		case "/v1/service/preorders/win-7/audience":
			gotAuth, gotQuery = r.Header.Get("Authorization"), r.URL.RawQuery
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"members":[{"userId":"cust-1","unsubscribeUrl":"https://u"},{"email":"a@example.com"}],"next":"m-2"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	a := deliveryruns.New(deliveryruns.Config{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token", ClientID: "notifications-service",
		ClientSecret: "s"}, &http.Client{Timeout: 3 * time.Second})
	page, err := a.Audience(context.Background(), "win-7", domain.ChannelSMS, "SUBSCRIBERS", "m-1", 200)
	if err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer tok" || gotQuery != "after=m-1&channel=SMS&limit=200&segment=SUBSCRIBERS" {
		t.Fatalf("auth %q query %q", gotAuth, gotQuery)
	}
	if len(page.Members) != 2 || page.Next != "m-2" || page.Members[0].UnsubscribeURL != "https://u" {
		t.Fatalf("page = %+v", page)
	}
	if _, err := a.Audience(context.Background(), "nope", domain.ChannelEmail, "WAITLIST", "", 0); !errors.Is(err, domain.ErrRecipientNotFound) {
		t.Fatalf("unknown window = %v", err)
	}
}

// A location's audience: WhatsApp is chat here and WHATSAPP in delivery-runs, paging is keyset (after = the last
// subscription id), and an unknown location is not an error to retry.
func TestLocationAudienceNamesWhatsAppForDeliveryRunsAndPages(t *testing.T) {
	var gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/oauth2/token":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"access_token":"tok","token_type":"Bearer","expires_in":300}`))
		case r.URL.Path == "/v1/service/locations/loc-1/audience":
			gotPath, gotQuery = r.URL.Path, r.URL.RawQuery
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"members":[{"subscriptionId":"s-1","userId":"cust-1","unsubscribeUrl":"https://u"}],"next":"s-1"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	a := deliveryruns.New(deliveryruns.Config{BaseURL: srv.URL, TokenURL: srv.URL + "/oauth2/token", ClientID: "notifications-service",
		ClientSecret: "s"}, &http.Client{Timeout: 3 * time.Second})
	page, err := a.LocationAudience(context.Background(), "loc-1", domain.ChannelChat, "s-0", 100)
	if err != nil {
		t.Fatal(err)
	}
	if gotPath != "/v1/service/locations/loc-1/audience" || gotQuery != "after=s-0&channel=WHATSAPP&limit=100" {
		t.Fatalf("path %q query %q", gotPath, gotQuery)
	}
	if len(page.Members) != 1 || page.Members[0].UserID != "cust-1" || page.Next != "s-1" || page.Members[0].SubscriptionID != "s-1" {
		t.Fatalf("page = %+v", page)
	}
	if _, err := a.LocationAudience(context.Background(), "nope", domain.ChannelEmail, "", 0); !errors.Is(err, domain.ErrRecipientNotFound) {
		t.Fatalf("unknown location = %v", err)
	}
}
