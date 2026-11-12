package domain

import "time"

// PromptState is what a seller organisation answered to the one-time "let customers collect orders here" card.
type PromptState string

// States of a PickupPrompt.
const (
	// PromptNone: never answered, the card is due.
	PromptNone PromptState = "NONE"
	// PromptSnoozed: "Not now"; the card is due again after SnoozedUntil.
	PromptSnoozed PromptState = "SNOOZED"
	// PromptNever: "Don't ask again"; the card is never shown again.
	PromptNever PromptState = "NEVER"
)

// Bounds and default of the staff-editable "ask again after" setting, in days.
const (
	DefaultReappearDays = 30
	MinReappearDays     = 1
	MaxReappearDays     = 365
)

// PickupPrompt is one seller organisation's answer to the card, kept per organisation so every member sees the same.
type PickupPrompt struct {
	OwnerOrg     string      `json:"ownerOrg"`
	State        PromptState `json:"state"`
	SnoozedUntil *time.Time  `json:"snoozedUntil,omitempty"`
	DecidedBy    string      `json:"decidedBy,omitempty"`
	DecidedAt    *time.Time  `json:"decidedAt,omitempty"`
}

// Due reports whether the card may be shown at now: never after "Don't ask again", not before the snooze ended.
func (p PickupPrompt) Due(now time.Time) bool {
	switch p.State {
	case PromptNever:
		return false
	case PromptSnoozed:
		return p.SnoozedUntil == nil || !now.Before(*p.SnoozedUntil)
	default:
		return true
	}
}
