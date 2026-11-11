package app

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// ShippingSettingsEnsurer reads the shipping settings once when none was ever read, so the first cart or checkout after
// a start does not wait for the poll (nil-safe at every call site).
type ShippingSettingsEnsurer interface {
	Ensure(ctx context.Context)
}

// ShippingSettingsSync keeps the estimator's delivery fee on orders' staff-set shipping settings (listing rules D16):
// the one source orders charges by. It polls every interval (a short cache). When orders cannot be read it KEEPS THE LAST
// KNOWN VALUE and logs it clearly; when no value was ever read the estimator shows no fee (never $0) and checkout cannot
// be placed from the estimate.
type ShippingSettingsSync interface {
	ShippingSettingsEnsurer
	// Refresh reads the settings once and applies them.
	Refresh(ctx context.Context) error
	// Run refreshes immediately and then every interval until ctx ends.
	Run(ctx context.Context)
}

type shippingSettingsSyncImpl struct {
	reader   port.ShippingSettingsReader
	set      domain.ShippingSettingsSetter
	currency string
	interval time.Duration
	log      *slog.Logger
	now      func() time.Time

	mu          sync.Mutex
	lastAttempt time.Time
	lastGood    time.Time
}

var _ ShippingSettingsSync = (*shippingSettingsSyncImpl)(nil)

// ensureBackoff is the least time between reads triggered by requests while nothing was ever read.
const ensureBackoff = 3 * time.Second

// NewShippingSettingsSync returns the sync for one currency; interval <= 0 means every 15 seconds.
func NewShippingSettingsSync(reader port.ShippingSettingsReader, set domain.ShippingSettingsSetter, currency string, interval time.Duration, log *slog.Logger) ShippingSettingsSync {
	if interval <= 0 {
		interval = 15 * time.Second
	}
	if log == nil {
		log = slog.Default()
	}
	return &shippingSettingsSyncImpl{reader: reader, set: set, currency: currency, interval: interval, log: log, now: time.Now}
}

func (s *shippingSettingsSyncImpl) Refresh(ctx context.Context) error {
	s.mu.Lock()
	s.lastAttempt = s.now()
	s.mu.Unlock()
	cur, err := s.reader.Current(ctx, s.currency)
	if err != nil {
		if last, ok := s.set.ShippingSettings(); ok {
			s.mu.Lock()
			age := s.now().Sub(s.lastGood).Round(time.Second)
			s.mu.Unlock()
			s.log.WarnContext(ctx, "shipping settings unavailable; keeping the last known delivery fee", "error", err,
				"lastKnownFlatFeeMinor", last.FlatFeeMinor, "lastKnownVersion", last.Version, "lastReadAgo", age.String())
		} else {
			s.log.ErrorContext(ctx, "shipping settings never read; delivery fee is shown as unknown and checkout is blocked until orders answers", "error", err)
		}
		return fmt.Errorf("shipping settings: %w", err)
	}
	s.set.SetShippingSettings(&cur)
	s.mu.Lock()
	s.lastGood = s.now()
	s.mu.Unlock()
	return nil
}

func (s *shippingSettingsSyncImpl) Ensure(ctx context.Context) {
	if _, ok := s.set.ShippingSettings(); ok {
		return
	}
	s.mu.Lock()
	recent := s.now().Sub(s.lastAttempt) < ensureBackoff
	s.mu.Unlock()
	if recent {
		return
	}
	_ = s.Refresh(ctx) // logged inside; the caller shows an unknown fee
}

func (s *shippingSettingsSyncImpl) Run(ctx context.Context) {
	_ = s.Refresh(ctx)
	tick := time.NewTicker(s.interval)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			_ = s.Refresh(ctx)
		}
	}
}
