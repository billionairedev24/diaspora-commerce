package app_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

func adToken(t *testing.T, exp time.Time) string {
	t.Helper()
	tok, _ := fakeTokens{}.Sign(domain.AdToken{SearchID: "srch-0001-test", CampaignID: "cmp_mama", SellerOrgID: "org_mama",
		ATIN: "AT00000AD1", PriceMinor: 35, Market: "us", Query: "egusi", Slot: 1, ExpiresAt: exp.Unix()})
	return tok
}

func TestSponsoredClickIsBillableOnce(t *testing.T) {
	bus, rec := &fakeBus{}, &fakeRecorder{}
	clicks := app.NewClickRecorder(fakeTokens{}, bus, rec, time.Hour, quiet)
	click := domain.Click{SearchID: "srch-0001-test", ATIN: "AT00000AD1", Position: 1, Query: "tampered",
		AdToken: adToken(t, time.Now().Add(time.Hour)), CustomerID: "cus_1"}

	res, err := clicks.Record(context.Background(), click)
	if err != nil {
		t.Fatal(err)
	}
	if !res.Recorded || !res.Sponsored || res.AdStatus != app.AdBillable || res.Duplicate {
		t.Fatalf("first click: %+v", res)
	}
	adClicks := bus.ofType(domain.EventAdClick)
	if len(adClicks) != 1 || adClicks[0].Topic() != "at.ads.events" || adClicks[0].AggregateID != "cmp_mama" {
		t.Fatalf("ad click events: %+v", adClicks)
	}
	var payload domain.AdClick
	_ = json.Unmarshal(adClicks[0].Payload, &payload)
	if payload.PriceMinor != 35 || payload.CustomerID != "cus_1" || payload.ClickID != adClicks[0].ID || payload.Query != "egusi" {
		t.Fatalf("payload: %+v", payload)
	}
	// The query comes from the signed token, not the request body.
	if len(rec.clicked) != 1 || rec.clicked[0] != "us|egusi" {
		t.Fatalf("analytics: %+v", rec.clicked)
	}

	again, err := clicks.Record(context.Background(), click)
	if err != nil {
		t.Fatal(err)
	}
	if again.Recorded || !again.Duplicate || again.AdStatus != app.AdDuplicate {
		t.Fatalf("second click: %+v", again)
	}
	if len(bus.ofType(domain.EventAdClick)) != 1 || len(bus.ofType(domain.EventResultClicked)) != 1 {
		t.Fatal("duplicates must not publish")
	}
}

func TestExpiredAdClickIsNotBilledButCounted(t *testing.T) {
	bus := &fakeBus{}
	clicks := app.NewClickRecorder(fakeTokens{}, bus, &fakeRecorder{}, time.Hour, quiet)
	res, err := clicks.Record(context.Background(), domain.Click{SearchID: "srch-0001-test", ATIN: "AT00000AD1",
		Position: 1, AdToken: adToken(t, time.Now().Add(-time.Minute))})
	if err != nil {
		t.Fatal(err)
	}
	if !res.Recorded || res.AdStatus != app.AdExpired || len(bus.ofType(domain.EventAdClick)) != 0 ||
		len(bus.ofType(domain.EventResultClicked)) != 1 {
		t.Fatalf("expired: %+v", res)
	}
}

func TestForgedOrMismatchedAdTokensAreRejected(t *testing.T) {
	clicks := app.NewClickRecorder(fakeTokens{}, &fakeBus{}, nil, time.Hour, quiet)
	for _, c := range []domain.Click{
		{SearchID: "srch-0001-test", ATIN: "AT00000AD1", Position: 1, AdToken: "forged"},
		{SearchID: "srch-0001-test", ATIN: "AT00000ZZZ", Position: 1, AdToken: adToken(t, time.Now().Add(time.Hour))},
	} {
		if _, err := clicks.Record(context.Background(), c); !errors.Is(err, app.ErrInvalidAdToken) {
			t.Fatalf("expected ErrInvalidAdToken, got %v", err)
		}
	}
	noKey := app.NewClickRecorder(nil, &fakeBus{}, nil, time.Hour, quiet)
	if _, err := noKey.Record(context.Background(), domain.Click{SearchID: "srch-0001-test", ATIN: "AT00000AD1",
		Position: 1, AdToken: "x"}); !errors.Is(err, app.ErrInvalidAdToken) {
		t.Fatalf("without a key ad tokens are rejected: %v", err)
	}
}

func TestOrganicClickPublishesStableEvent(t *testing.T) {
	bus := &fakeBus{}
	clicks := app.NewClickRecorder(fakeTokens{}, bus, nil, time.Hour, quiet)
	res, err := clicks.Record(context.Background(), domain.Click{SearchID: "srch-0002-test", ATIN: "AT0000000A",
		Position: 3, Query: "Garri"})
	if err != nil || !res.Recorded || res.Sponsored {
		t.Fatalf("organic: %+v %v", res, err)
	}
	e := bus.ofType(domain.EventResultClicked)
	if len(e) != 1 || e[0].Topic() != "at.search.events" || e[0].AggregateID != "srch-0002-test" {
		t.Fatalf("event: %+v", e)
	}
	other := &fakeBus{}
	_, _ = app.NewClickRecorder(fakeTokens{}, other, nil, time.Hour, quiet).Record(context.Background(),
		domain.Click{SearchID: "srch-0002-test", ATIN: "AT0000000A", Position: 3})
	if other.events[0].ID != e[0].ID {
		t.Fatal("the same click on another replica must carry the same event id")
	}
	if _, err := clicks.Record(context.Background(), domain.Click{SearchID: "x", ATIN: "nope"}); !errors.Is(err, domain.ErrInvalidClick) {
		t.Fatalf("invalid click: %v", err)
	}
}
