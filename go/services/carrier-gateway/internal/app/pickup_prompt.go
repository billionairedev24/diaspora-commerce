package app

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/pkg/auth"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/domain"
	"github.com/billionairedev24/diaspora-commerce/go/services/carrier-gateway/internal/port"
)

// SettingReappearDays is the carrier_setting key of "ask again after N days" for the pickup card.
const SettingReappearDays = "pickupPrompt.reappearDays"

// Answers to the card.
const (
	PromptActionSnooze = "SNOOZE"
	PromptActionNever  = "NEVER"
)

// PromptView is the card's server-side state for one organisation.
type PromptView struct {
	State        domain.PromptState `json:"state"`
	SnoozedUntil *time.Time         `json:"snoozedUntil,omitempty"`
	// Due is true when the card may be shown now (never answered, or the snooze has ended). Whether the seller is
	// eligible at all (an approved organisation with a ship-from address, no pickup place yet) is decided by the caller.
	Due          bool `json:"due"`
	ReappearDays int  `json:"reappearDays"`
}

// PickupPromptService keeps the answer to the "let customers collect orders here" card per seller organisation and the
// staff-editable number of days after which "Not now" asks again.
type PickupPromptService interface {
	// Get is the calling seller organisation's answer.
	Get(ctx context.Context, actor auth.Actor) (PromptView, error)
	// Answer records "Not now" (PromptActionSnooze) or "Don't ask again" (PromptActionNever).
	Answer(ctx context.Context, actor auth.Actor, action string) (PromptView, error)
	// ReappearDays is the current setting; SetReappearDays changes it (shipping:admin).
	ReappearDays(ctx context.Context) (int, error)
	SetReappearDays(ctx context.Context, actor auth.Actor, days int) (int, error)
}

type pickupPromptImpl struct {
	repo port.PickupPromptRepository
	now  func() time.Time
}

var _ PickupPromptService = (*pickupPromptImpl)(nil)

// NewPickupPromptService returns a PickupPromptService.
func NewPickupPromptService(repo port.PickupPromptRepository, now func() time.Time) PickupPromptService {
	return &pickupPromptImpl{repo: repo, now: now}
}

func sellerOrg(actor auth.Actor) (string, error) {
	if actor.OrgID == "" || !actor.Can(PermLabel) {
		return "", fmt.Errorf("%w: only a seller organisation answers this", ErrForbidden)
	}
	return actor.OrgID, nil
}

func (s *pickupPromptImpl) ReappearDays(ctx context.Context) (int, error) {
	v, ok, err := s.repo.Setting(ctx, SettingReappearDays)
	if err != nil {
		return 0, fmt.Errorf("reappear days: %w", err)
	}
	if !ok || v < domain.MinReappearDays || v > domain.MaxReappearDays {
		return domain.DefaultReappearDays, nil
	}
	return v, nil
}

func (s *pickupPromptImpl) view(ctx context.Context, p domain.PickupPrompt) (PromptView, error) {
	days, err := s.ReappearDays(ctx)
	if err != nil {
		return PromptView{}, err
	}
	return PromptView{State: p.State, SnoozedUntil: p.SnoozedUntil, Due: p.Due(s.now()), ReappearDays: days}, nil
}

func (s *pickupPromptImpl) Get(ctx context.Context, actor auth.Actor) (PromptView, error) {
	org, err := sellerOrg(actor)
	if err != nil {
		return PromptView{}, err
	}
	p, err := s.repo.GetPrompt(ctx, org)
	if err != nil {
		return PromptView{}, fmt.Errorf("get pickup prompt: %w", err)
	}
	return s.view(ctx, p)
}

func (s *pickupPromptImpl) Answer(ctx context.Context, actor auth.Actor, action string) (PromptView, error) {
	org, err := sellerOrg(actor)
	if err != nil {
		return PromptView{}, err
	}
	now := s.now().UTC()
	p := domain.PickupPrompt{OwnerOrg: org, DecidedBy: actor.Subject, DecidedAt: &now}
	switch strings.ToUpper(strings.TrimSpace(action)) {
	case PromptActionSnooze:
		days, err := s.ReappearDays(ctx)
		if err != nil {
			return PromptView{}, err
		}
		until := now.AddDate(0, 0, days)
		p.State, p.SnoozedUntil = domain.PromptSnoozed, &until
	case PromptActionNever:
		p.State = domain.PromptNever
	default:
		return PromptView{}, fmt.Errorf("%w: action must be SNOOZE or NEVER", domain.ErrInvalid)
	}
	if err := s.repo.SavePrompt(ctx, p); err != nil {
		return PromptView{}, fmt.Errorf("save pickup prompt: %w", err)
	}
	return s.view(ctx, p)
}

func (s *pickupPromptImpl) SetReappearDays(ctx context.Context, actor auth.Actor, days int) (int, error) {
	if !actor.Can(PermAdmin) {
		return 0, fmt.Errorf("%w: needs shipping:admin", ErrForbidden)
	}
	if days < domain.MinReappearDays || days > domain.MaxReappearDays {
		return 0, fmt.Errorf("%w: days must be between %d and %d", domain.ErrInvalid, domain.MinReappearDays, domain.MaxReappearDays)
	}
	if err := s.repo.SaveSetting(ctx, SettingReappearDays, days, actor.Subject, s.now().UTC()); err != nil {
		return 0, fmt.Errorf("save reappear days: %w", err)
	}
	return days, nil
}
