package app_test

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/events"
	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

type fakeRecorder struct {
	mu       sync.Mutex
	searched []string
	clicked  []string
}

func (f *fakeRecorder) Searched(market, query string, zero bool, _ time.Time) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.searched = append(f.searched, fmt.Sprintf("%s|%s|%v", market, domain.AnalyticsKey(query), zero))
}

func (f *fakeRecorder) Clicked(market, query string, _ time.Time) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.clicked = append(f.clicked, market+"|"+domain.AnalyticsKey(query))
}

func (f *fakeRecorder) Flush(context.Context) error { return nil }

func (f *fakeRecorder) Run(context.Context, time.Duration, time.Duration, []string) error { return nil }

type fakeBus struct {
	mu     sync.Mutex
	events []events.Envelope
}

func (f *fakeBus) Publish(_ context.Context, e events.Envelope) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.events = append(f.events, e)
}

func (f *fakeBus) ofType(typ string) []events.Envelope {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []events.Envelope
	for _, e := range f.events {
		if e.Type == typ {
			out = append(out, e)
		}
	}
	return out
}

type fakeCampaigns struct {
	camps []domain.Campaign
	err   error
}

func (f fakeCampaigns) Eligible(context.Context, string) ([]domain.Campaign, error) {
	return f.camps, f.err
}

// fakeTokens "signs" by joining fields; good enough to test the use cases (the HMAC adapter has its own tests).
type fakeTokens struct{}

func (fakeTokens) Sign(t domain.AdToken) (string, error) {
	return fmt.Sprintf("%s~%s~%s~%s~%d~%s~%s~%d~%d", t.SearchID, t.CampaignID, t.SellerOrgID, t.ATIN, t.PriceMinor,
		t.Market, t.Query, t.Slot, t.ExpiresAt), nil
}

func (fakeTokens) Verify(token string) (domain.AdToken, error) {
	p := strings.Split(token, "~")
	if len(p) != 9 {
		return domain.AdToken{}, errors.New("forged")
	}
	var price, exp int64
	var slot int
	_, _ = fmt.Sscan(p[4], &price)
	_, _ = fmt.Sscan(p[7], &slot)
	_, _ = fmt.Sscan(p[8], &exp)
	return domain.AdToken{Version: 1, SearchID: p[0], CampaignID: p[1], SellerOrgID: p[2], ATIN: p[3], PriceMinor: price,
		Currency: "USD", Market: p[5], Query: p[6], Slot: slot, ExpiresAt: exp}, nil
}
