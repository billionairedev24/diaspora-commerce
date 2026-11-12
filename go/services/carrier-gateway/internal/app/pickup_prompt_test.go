package app_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/app"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
)

type memPrompts struct {
	prompts  map[string]domain.PickupPrompt
	settings map[string]int
}

func newMemPrompts() *memPrompts {
	return &memPrompts{prompts: map[string]domain.PickupPrompt{}, settings: map[string]int{}}
}

func (m *memPrompts) GetPrompt(_ context.Context, org string) (domain.PickupPrompt, error) {
	if p, ok := m.prompts[org]; ok {
		return p, nil
	}
	return domain.PickupPrompt{OwnerOrg: org, State: domain.PromptNone}, nil
}

func (m *memPrompts) SavePrompt(_ context.Context, p domain.PickupPrompt) error {
	m.prompts[p.OwnerOrg] = p
	return nil
}

func (m *memPrompts) Setting(_ context.Context, key string) (int, bool, error) {
	v, ok := m.settings[key]
	return v, ok, nil
}

func (m *memPrompts) SaveSetting(_ context.Context, key string, v int, _ string, _ time.Time) error {
	m.settings[key] = v
	return nil
}

func TestPickupPromptSnoozeReappearsAfterTheSetting(t *testing.T) {
	repo := newMemPrompts()
	clock := time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)
	svc := app.NewPickupPromptService(repo, func() time.Time { return clock })
	ctx := context.Background()
	seller := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_1", Permissions: []string{app.PermLabel}}
	if v, err := svc.Get(ctx, seller); err != nil || !v.Due || v.State != domain.PromptNone || v.ReappearDays != 30 {
		t.Fatalf("a new organisation is asked, default 30 days: %+v %v", v, err)
	}
	if v, err := svc.Answer(ctx, seller, "snooze"); err != nil || v.Due || v.State != domain.PromptSnoozed {
		t.Fatalf("not now hides the card: %+v %v", v, err)
	}
	clock = clock.AddDate(0, 0, 29)
	if v, _ := svc.Get(ctx, seller); v.Due {
		t.Fatalf("still hidden on day 29: %+v", v)
	}
	clock = clock.AddDate(0, 0, 1)
	if v, _ := svc.Get(ctx, seller); !v.Due {
		t.Fatalf("asks again on day 30: %+v", v)
	}
	// Another member of the same organisation sees the same answer; another organisation does not.
	other := auth.Actor{Subject: "u9", Type: "SELLER_MEMBER", OrgID: "org_2", Permissions: []string{app.PermLabel}}
	if v, _ := svc.Get(ctx, other); !v.Due {
		t.Fatalf("organisations are independent: %+v", v)
	}
}

func TestPickupPromptNeverIsForever(t *testing.T) {
	clock := time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)
	svc := app.NewPickupPromptService(newMemPrompts(), func() time.Time { return clock })
	ctx := context.Background()
	seller := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_1", Permissions: []string{app.PermLabel}}
	if _, err := svc.Answer(ctx, seller, "NEVER"); err != nil {
		t.Fatal(err)
	}
	clock = clock.AddDate(5, 0, 0)
	if v, _ := svc.Get(ctx, seller); v.Due || v.State != domain.PromptNever {
		t.Fatalf("never asks again, even years later: %+v", v)
	}
	if _, err := svc.Answer(ctx, seller, "later"); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("unknown answer: %v", err)
	}
}

func TestPickupPromptSettingIsStaffOnlyAndBounded(t *testing.T) {
	repo := newMemPrompts()
	now := time.Date(2026, 11, 1, 9, 0, 0, 0, time.UTC)
	svc := app.NewPickupPromptService(repo, func() time.Time { return now })
	ctx := context.Background()
	seller := auth.Actor{Subject: "u1", Type: "SELLER_MEMBER", OrgID: "org_1", Permissions: []string{app.PermLabel}}
	staff := auth.Actor{Subject: "s1", Type: "STAFF", Permissions: []string{app.PermAdmin}}
	if _, err := svc.SetReappearDays(ctx, seller, 7); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("sellers cannot change it: %v", err)
	}
	for _, bad := range []int{0, -3, 366} {
		if _, err := svc.SetReappearDays(ctx, staff, bad); !errors.Is(err, domain.ErrInvalid) {
			t.Fatalf("%d days: %v", bad, err)
		}
	}
	if _, err := svc.SetReappearDays(ctx, staff, 7); err != nil {
		t.Fatal(err)
	}
	v, err := svc.Answer(ctx, seller, "SNOOZE")
	if err != nil || v.ReappearDays != 7 || v.SnoozedUntil == nil || !v.SnoozedUntil.Equal(now.AddDate(0, 0, 7)) {
		t.Fatalf("snooze follows the staff setting: %+v %v", v, err)
	}
	if _, err := svc.Get(ctx, auth.Actor{Type: "CUSTOMER"}); !errors.Is(err, app.ErrForbidden) {
		t.Fatalf("customers have no card: %v", err)
	}
}

func TestSellerPickupSwitchOnNeedsConfirmedHoursAndIsAudited(t *testing.T) {
	repo := &memPickups{m: map[string]domain.PickupLocation{}}
	clock := time.Date(2026, 11, 2, 10, 30, 0, 0, time.UTC)
	svc := app.NewPickupService(app.Carriers{}, allAccounts(), repo, newMemStore(), func() time.Time { return clock })
	ctx := context.Background()
	seller := auth.Actor{Subject: "user_7", Type: "SELLER_MEMBER", OrgID: "org_1", Permissions: []string{app.PermLabel}}
	loc := domain.PickupLocation{ID: "addr_1", Kind: domain.PickupSeller, Name: "Mama Put", Timezone: "America/Chicago", Active: true,
		Address: domain.Address{Line1: "9 Bissonnet St", City: "Houston", State: "TX", PostalCode: "77005"},
		Hours:   []domain.OpeningHours{{Weekday: time.Saturday, Open: "10:00", Close: "16:00"}}}

	if _, err := svc.SaveLocation(ctx, seller, loc); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("switching on without confirming the hours must be refused: %v", err)
	}
	if len(repo.m) != 0 {
		t.Fatal("nothing is saved when refused")
	}
	noHours := loc
	noHours.HoursConfirmed, noHours.Hours = true, nil
	if _, err := svc.SaveLocation(ctx, seller, noHours); !errors.Is(err, domain.ErrInvalid) {
		t.Fatalf("confirming without any hours must be refused: %v", err)
	}
	// Off is always allowed to be saved, and does not claim an enablement.
	off := loc
	off.Active = false
	if saved, err := svc.SaveLocation(ctx, seller, off); err != nil || saved.EnabledAt != nil || saved.EnabledBy != "" {
		t.Fatalf("saving it off: %+v %v", saved, err)
	}
	// The caller cannot forge the audit fields.
	forged := time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)
	loc.HoursConfirmed, loc.EnabledBy, loc.EnabledAt = true, "someone_else", &forged
	on, err := svc.SaveLocation(ctx, seller, loc)
	if err != nil || !on.Active || on.EnabledBy != "user_7" || on.EnabledAt == nil || !on.EnabledAt.Equal(clock) || on.HoursConfirmed {
		t.Fatalf("switched on and audited: %+v %v", on, err)
	}
	// An edit that keeps it on keeps who and when, and needs no new confirmation.
	clock = clock.Add(48 * time.Hour)
	edit := on
	edit.ReadyHours = 4
	edit.EnabledBy, edit.EnabledAt = "", nil
	kept, err := svc.SaveLocation(ctx, auth.Actor{Subject: "user_8", Type: "SELLER_MEMBER", OrgID: "org_1", Permissions: []string{app.PermLabel}}, edit)
	if err != nil || kept.EnabledBy != "user_7" || !kept.EnabledAt.Equal(on.EnabledAt.UTC()) {
		t.Fatalf("edit keeps the first enablement record: %+v %v", kept, err)
	}
}
