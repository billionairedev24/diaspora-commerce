package app

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// CarrierRuleSync keeps the storefront's carrier shipping estimate on the staff-managed carrier rule (ADR 0005 §3):
// the cart and checkout show the fee orders-service will charge (the rule's fallback fee, free over its threshold).
// A currency without a carrier rule, or delivery-runs being unreachable, keeps the last rule (or the flat policy).
type CarrierRuleSync interface {
	// Refresh reads the rules once and applies the carrier rule.
	Refresh(ctx context.Context) error
	// Run refreshes every interval until ctx ends.
	Run(ctx context.Context)
}

type carrierRuleSyncImpl struct {
	fees     port.PublicDeliveryFees
	set      domain.CarrierRuleSetter
	currency string
	interval time.Duration
	log      *slog.Logger
}

var _ CarrierRuleSync = (*carrierRuleSyncImpl)(nil)

// NewCarrierRuleSync returns the sync for one currency; interval <= 0 means every 30 seconds.
func NewCarrierRuleSync(fees port.PublicDeliveryFees, set domain.CarrierRuleSetter, currency string, interval time.Duration, log *slog.Logger) CarrierRuleSync {
	if interval <= 0 {
		interval = 30 * time.Second
	}
	if log == nil {
		log = slog.Default()
	}
	return &carrierRuleSyncImpl{fees: fees, set: set, currency: currency, interval: interval, log: log}
}

func (s *carrierRuleSyncImpl) Refresh(ctx context.Context) error {
	raw, err := s.fees.Rules(ctx, s.currency)
	if err != nil {
		return fmt.Errorf("delivery fee rules: %w", err)
	}
	var out struct {
		Items []struct {
			ID             string `json:"id"`
			Method         string `json:"method"`
			Version        int64  `json:"version"`
			FallbackMinor  int64  `json:"fallbackMinor"`
			FreeAboveMinor *int64 `json:"freeAboveMinor"`
		} `json:"items"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return fmt.Errorf("delivery fee rules: %w", err)
	}
	for _, r := range out.Items {
		if r.Method != "CARRIER" {
			continue
		}
		rule := &domain.CarrierRule{RuleID: r.ID, Version: r.Version, FallbackMinor: r.FallbackMinor}
		if r.FreeAboveMinor != nil {
			rule.FreeAboveMinor = *r.FreeAboveMinor
		}
		s.set.SetCarrierRule(rule)
		return nil
	}
	s.set.SetCarrierRule(nil) // staff removed the rule (or never set one): the configured flat policy applies
	return nil
}

func (s *carrierRuleSyncImpl) Run(ctx context.Context) {
	tick := time.NewTicker(s.interval)
	defer tick.Stop()
	for {
		rctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		if err := s.Refresh(rctx); err != nil {
			s.log.Warn("carrier rule not refreshed; keeping the last one", "err", err)
		}
		cancel()
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
