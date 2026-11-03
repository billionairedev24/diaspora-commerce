package app

import (
	"context"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

type memMaintenance struct{ m domain.Maintenance }

func (s *memMaintenance) Get(context.Context) (domain.Maintenance, error) { return s.m, nil }
func (s *memMaintenance) Put(_ context.Context, m domain.Maintenance) error {
	s.m = m
	return nil
}

func TestMaintenanceSetRecordsWhoWhenAndWhy(t *testing.T) {
	now := time.Date(2026, 10, 24, 10, 0, 0, 0, time.UTC)
	store := &memMaintenance{}
	svc := NewMaintenanceService(store, func() time.Time { return now }, nil)

	m, err := svc.Set(context.Background(), "staff-1", MaintenanceChange{Enabled: true, Message: "Upgrading our database tonight.", BackIn: 30 * time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	if !m.Enabled || m.Scope != domain.MaintenanceShoppers || m.Title != defaultMaintenanceTitle {
		t.Fatalf("defaults not applied: %+v", m)
	}
	if m.ExpectedBackAt == nil || !m.ExpectedBackAt.Equal(now.Add(30*time.Minute)) {
		t.Fatalf("expected back: %v", m.ExpectedBackAt)
	}
	if len(m.History) != 1 || m.History[0].By != "staff-1" {
		t.Fatalf("history: %+v", m.History)
	}
	if st := svc.Status(context.Background()); !st.Active || st.Message == "" {
		t.Fatalf("status: %+v", st)
	}

	off, err := svc.Set(context.Background(), "staff-2", MaintenanceChange{Enabled: false})
	if err != nil {
		t.Fatal(err)
	}
	if off.Enabled || len(off.History) != 2 || off.History[0].By != "staff-2" {
		t.Fatalf("off: %+v", off)
	}
	if st := svc.Status(context.Background()); st.Active {
		t.Fatalf("still active: %+v", st)
	}
}

func TestMaintenanceNeedsAMessageToTurnOn(t *testing.T) {
	svc := NewMaintenanceService(&memMaintenance{}, nil, nil)
	if _, err := svc.Set(context.Background(), "s", MaintenanceChange{Enabled: true, Message: "short"}); err == nil {
		t.Fatal("expected a validation error")
	}
	if _, err := svc.Set(context.Background(), "s", MaintenanceChange{Enabled: true, Scope: "EVERYONE", Message: "A long enough message."}); err == nil {
		t.Fatal("expected a scope error")
	}
}

func TestMaintenanceHidesAnExpectedTimeThatHasPassed(t *testing.T) {
	now := time.Date(2026, 10, 24, 10, 0, 0, 0, time.UTC)
	past := now.Add(-time.Minute)
	store := &memMaintenance{m: domain.Maintenance{Enabled: true, Scope: domain.MaintenanceAll, Message: "m", ExpectedBackAt: &past}}
	st := NewMaintenanceService(store, func() time.Time { return now }, nil).Status(context.Background())
	if !st.Active || st.ExpectedBackAt != nil {
		t.Fatalf("status: %+v", st)
	}
}

func TestMaintenanceBlocksBySide(t *testing.T) {
	shoppers := domain.Maintenance{Enabled: true, Scope: domain.MaintenanceShoppers}
	all := domain.Maintenance{Enabled: true, Scope: domain.MaintenanceAll}
	if !shoppers.Blocks(false) || shoppers.Blocks(true) || !all.Blocks(true) || (domain.Maintenance{}).Blocks(false) {
		t.Fatal("scope rules wrong")
	}
}
