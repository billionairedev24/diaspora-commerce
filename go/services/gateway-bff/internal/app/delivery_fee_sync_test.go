package app

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

type fakeFees struct {
	body json.RawMessage
	err  error
	seen string
}

func (f *fakeFees) Rules(_ context.Context, currency string) (json.RawMessage, error) {
	f.seen = currency
	return f.body, f.err
}

type recordedRule struct {
	calls int
	rule  *domain.CarrierRule
}

func (r *recordedRule) SetCarrierRule(c *domain.CarrierRule) { r.calls++; r.rule = c }

func TestCarrierRuleSyncAppliesTheStaffCarrierRule(t *testing.T) {
	fees := &fakeFees{body: json.RawMessage(`{"items":[
	  {"id":"d","method":"AFROTRANSACT_DELIVERY","version":3,"bands":[]},
	  {"id":"c-1","method":"CARRIER","version":4,"handlingMinor":200,"fallbackMinor":1350,"freeAboveMinor":7500},
	  {"id":"p","method":"PICKUP","version":2,"feeMinor":0}]}`)}
	set := &recordedRule{}
	s := NewCarrierRuleSync(fees, set, "USD", 0, slog.Default())

	if err := s.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	if fees.seen != "USD" || set.rule == nil || set.rule.RuleID != "c-1" || set.rule.Version != 4 ||
		set.rule.FallbackMinor != 1350 || set.rule.FreeAboveMinor != 7500 {
		t.Fatalf("applied %+v for %q", set.rule, fees.seen)
	}

	fees.body = json.RawMessage(`{"items":[{"id":"c-1","method":"CARRIER","version":5,"fallbackMinor":500}]}`)
	if err := s.Refresh(context.Background()); err != nil || set.rule.Version != 5 || set.rule.FreeAboveMinor != 0 {
		t.Fatalf("no threshold means never free: %+v %v", set.rule, err)
	}

	fees.body = json.RawMessage(`{"items":[{"id":"p","method":"PICKUP","version":2}]}`)
	if err := s.Refresh(context.Background()); err != nil || set.rule != nil {
		t.Fatalf("no carrier rule falls back to the flat policy: %+v %v", set.rule, err)
	}
}

func TestCarrierRuleSyncKeepsTheLastRuleWhenDeliveryRunsFails(t *testing.T) {
	fees := &fakeFees{body: json.RawMessage(`{"items":[{"id":"c-1","method":"CARRIER","version":4,"fallbackMinor":1350}]}`)}
	set := &recordedRule{}
	s := NewCarrierRuleSync(fees, set, "USD", time.Hour, nil)
	if err := s.Refresh(context.Background()); err != nil {
		t.Fatal(err)
	}
	fees.err = errors.New("delivery-runs down")
	if err := s.Refresh(context.Background()); err == nil || set.calls != 1 || set.rule == nil || set.rule.Version != 4 {
		t.Fatalf("an outage must not clear the rule: %+v calls=%d err=%v", set.rule, set.calls, err)
	}
	fees.err, fees.body = nil, json.RawMessage(`not json`)
	if err := s.Refresh(context.Background()); err == nil || set.calls != 1 {
		t.Fatalf("an unreadable answer must not clear the rule: calls=%d err=%v", set.calls, err)
	}
}

func TestCarrierRuleSyncRunStopsWithItsContext(t *testing.T) {
	fees := &fakeFees{body: json.RawMessage(`{"items":[{"id":"c-1","method":"CARRIER","version":4,"fallbackMinor":1350}]}`)}
	set := &syncedRule{}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { NewCarrierRuleSync(fees, set, "USD", time.Hour, nil).Run(ctx); close(done) }()
	deadline := time.After(2 * time.Second)
	for !set.has() {
		select {
		case <-deadline:
			t.Fatal("the first refresh never ran")
		case <-time.After(5 * time.Millisecond):
		}
	}
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not stop")
	}
}

// syncedRule is a recordedRule that is safe to read while Run writes it.
type syncedRule struct {
	mu   sync.Mutex
	rule *domain.CarrierRule
}

func (r *syncedRule) SetCarrierRule(c *domain.CarrierRule) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rule = c
}

func (r *syncedRule) has() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.rule != nil
}
