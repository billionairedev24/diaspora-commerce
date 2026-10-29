package app

import (
	"context"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/port"
)

// MaintenanceService is the platform's maintenance switch: staff turn it on with what customers should be told, the
// HTTP gate refuses customer and seller calls while it is on, and the web and mobile apps read its public status.
type MaintenanceService interface {
	// Current is the switch (read through a few seconds of cache so the gate costs no Valkey call per request).
	Current(ctx context.Context) domain.Maintenance
	// Status is the public view for GET /v1/maintenance.
	Status(ctx context.Context) domain.MaintenanceStatus
	// Set turns maintenance on or off; backIn (0 = unknown) is the expected duration from now. It records who and why.
	Set(ctx context.Context, actor string, in MaintenanceChange) (domain.Maintenance, error)
}

// MaintenanceChange is a staff request to switch maintenance.
type MaintenanceChange struct {
	Enabled bool
	Scope   string
	Title   string
	Message string
	BackIn  time.Duration
}

// maintenanceTTL bounds how long a replica keeps serving a stale switch.
const maintenanceTTL = 5 * time.Second

const maxMaintenanceHistory = 20

const defaultMaintenanceTitle = "We’ll be right back"

type maintenanceImpl struct {
	store port.MaintenanceStore
	clock Clock
	log   *slog.Logger

	mu      sync.Mutex
	cached  domain.Maintenance
	fetched time.Time
}

var _ MaintenanceService = (*maintenanceImpl)(nil)

// NewMaintenanceService returns the MaintenanceService.
func NewMaintenanceService(store port.MaintenanceStore, clock Clock, log *slog.Logger) MaintenanceService {
	if clock == nil {
		clock = time.Now
	}
	if log == nil {
		log = slog.Default()
	}
	return &maintenanceImpl{store: store, clock: clock, log: log}
}

// Current fails open: when the switch cannot be read, customers are served rather than locked out by a Valkey blip
// (the last known value is kept for the TTL).
func (s *maintenanceImpl) Current(ctx context.Context) domain.Maintenance {
	s.mu.Lock()
	defer s.mu.Unlock()
	if now := s.clock(); now.Sub(s.fetched) < maintenanceTTL {
		return s.cached
	}
	m, err := s.store.Get(ctx)
	if err != nil {
		s.log.WarnContext(ctx, "maintenance switch unreadable; keeping the last known value", "error", err)
		s.fetched = s.clock()
		return s.cached
	}
	s.cached, s.fetched = m, s.clock()
	return m
}

func (s *maintenanceImpl) Status(ctx context.Context) domain.MaintenanceStatus {
	m := s.Current(ctx)
	// An expected time that has passed is not shown (the page then says we are still working on it).
	st := m.Status()
	if st.ExpectedBackAt != nil && st.ExpectedBackAt.Before(s.clock()) {
		st.ExpectedBackAt = nil
	}
	return st
}

func (s *maintenanceImpl) Set(ctx context.Context, actor string, in MaintenanceChange) (domain.Maintenance, error) {
	scope := strings.ToUpper(strings.TrimSpace(in.Scope))
	if scope == "" {
		scope = domain.MaintenanceShoppers
	}
	if scope != domain.MaintenanceShoppers && scope != domain.MaintenanceAll {
		return domain.Maintenance{}, domain.ValidationProblem(map[string]string{"scope": "Choose who is paused: shoppers, or shoppers and sellers."})
	}
	msg := strings.TrimSpace(in.Message)
	if in.Enabled && len(msg) < 10 {
		return domain.Maintenance{}, domain.ValidationProblem(map[string]string{"message": "Tell customers what is happening, in at least 10 characters."})
	}
	cur, err := s.store.Get(ctx)
	if err != nil {
		return domain.Maintenance{}, mapErr("maintenance", err)
	}
	now := s.clock()
	next := domain.Maintenance{Enabled: in.Enabled, Scope: scope, Title: strings.TrimSpace(in.Title), Message: msg, UpdatedAt: now, UpdatedBy: actor}
	if next.Title == "" {
		next.Title = defaultMaintenanceTitle
	}
	if in.Enabled && in.BackIn > 0 {
		back := now.Add(in.BackIn)
		next.ExpectedBackAt = &back
	}
	change := domain.MaintenanceChange{At: now, By: actor, Enabled: next.Enabled, Scope: scope, Message: msg, ExpectedBackAt: next.ExpectedBackAt}
	next.History = append([]domain.MaintenanceChange{change}, cur.History...)
	if len(next.History) > maxMaintenanceHistory {
		next.History = next.History[:maxMaintenanceHistory]
	}
	if err := s.store.Put(ctx, next); err != nil {
		return domain.Maintenance{}, mapErr("maintenance", err)
	}
	s.mu.Lock()
	s.cached, s.fetched = next, now
	s.mu.Unlock()
	s.log.WarnContext(ctx, "maintenance mode changed", "enabled", next.Enabled, "scope", scope, "by", actor)
	return next, nil
}
