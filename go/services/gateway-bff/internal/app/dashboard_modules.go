package app

import (
	"fmt"
	"time"

	"github.com/billionairedev24/diaspora-commerce/go/services/gateway-bff/internal/domain"
)

// Builders shared by the seller, provider and staff landing pages. A module is built only from figures an upstream
// returned; a figure that cannot be computed is left out and a module with no source answers state "unavailable".

func lastDays(days int) *string {
	s := fmt.Sprintf("Last %d days", days)
	return &s
}

func moduleAction(label, href string) domain.ModuleAction {
	return domain.ModuleAction{Label: label, Href: href}
}

// readyModule starts a module with figures.
func readyModule(id, icon, title string) domain.ModuleSummary {
	return domain.ModuleSummary{ID: id, Icon: icon, Title: title, State: domain.ModuleReady, Actions: []domain.ModuleAction{}}
}

// emptyModule is a module with nothing yet: a prompt with one primary action, never zeros.
func emptyModule(id, icon, title, art, prompt string, action domain.ModuleAction) domain.ModuleSummary {
	return domain.ModuleSummary{ID: id, Icon: icon, Title: title, State: domain.ModuleEmpty, Actions: []domain.ModuleAction{},
		Empty: &domain.ModuleEmptyPrompt{Prompt: prompt, Action: action, Art: art}}
}

// unavailableModule is a module whose source could not answer; the page offers Try again.
func unavailableModule(id, icon, title string) domain.ModuleSummary {
	return domain.ModuleSummary{ID: id, Icon: icon, Title: title, State: domain.ModuleUnavailable, Actions: []domain.ModuleAction{}}
}

func headline(value, label string, period *string, tone string) *domain.ModuleHeadline {
	return &domain.ModuleHeadline{Value: value, Label: label, Period: period, Tone: tone}
}

func toneAttention(attention bool) string {
	if attention {
		return "attention"
	}
	return "positive"
}

func chartOf(kind, label, format string, points []domain.ModulePoint) *domain.ModuleChart {
	if points == nil {
		points = []domain.ModulePoint{}
	}
	return &domain.ModuleChart{Kind: kind, Label: label, ValueFormat: format, Points: points}
}

func counted(n int, one, many string) string {
	if n == 1 {
		return fmt.Sprintf("%d %s", n, one)
	}
	return fmt.Sprintf("%d %s", n, many)
}

func dayLabel(t time.Time) string { return t.Format("Jan 2") }
