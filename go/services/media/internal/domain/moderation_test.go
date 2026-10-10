package domain_test

import (
	"errors"
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/media/internal/domain"
)

func TestModerateCombinesAIAndDuplicates(t *testing.T) {
	closed := domain.ModerationPolicy{DuplicateDistance: 6}
	open := domain.ModerationPolicy{AIFailOpen: true, DuplicateDistance: 6}
	allow := &domain.AIVerdict{Decision: domain.AIAllow}
	cases := []struct {
		name   string
		policy domain.ModerationPolicy
		in     domain.ModerationInput
		want   domain.ModerationVerdict
		ai     domain.AICheck
		dup    string
	}{
		{"allowed", closed, domain.ModerationInput{OwnerOrg: "a", AI: allow}, domain.VerdictPassed, domain.AIChecked, ""},
		{"review holds", closed, domain.ModerationInput{AI: &domain.AIVerdict{Decision: domain.AIReview}},
			domain.VerdictHeld, domain.AIChecked, ""},
		{"block blocks", closed, domain.ModerationInput{AI: &domain.AIVerdict{Decision: domain.AIBlock}},
			domain.VerdictBlocked, domain.AIChecked, ""},
		{"unknown decision holds", closed, domain.ModerationInput{AI: &domain.AIVerdict{Decision: "MAYBE"}},
			domain.VerdictHeld, domain.AIChecked, ""},
		{"outage, fail closed", closed, domain.ModerationInput{AIErr: errors.New("timeout")},
			domain.VerdictHeld, domain.AIUnavailable, ""},
		{"outage, fail open", open, domain.ModerationInput{AIErr: errors.New("timeout")},
			domain.VerdictPassed, domain.AIUnavailable, ""},
		{"no AI configured", closed, domain.ModerationInput{AIOff: true}, domain.VerdictPassed, domain.AIOff, ""},
		{"own duplicate is a note", closed, domain.ModerationInput{OwnerOrg: "a", AI: allow,
			Similar: []domain.SimilarImage{{AssetID: "x1", OwnerOrg: "a", Distance: 2}}},
			domain.VerdictPassed, domain.AIChecked, "x1"},
		{"another seller's photo is held", closed, domain.ModerationInput{OwnerOrg: "a", AI: allow,
			Similar: []domain.SimilarImage{{AssetID: "x1", OwnerOrg: "a", Distance: 1}, {AssetID: "y1", OwnerOrg: "b", Distance: 3}}},
			domain.VerdictHeld, domain.AIChecked, "y1"},
		{"too far is not a duplicate", closed, domain.ModerationInput{OwnerOrg: "a", AI: allow,
			Similar: []domain.SimilarImage{{AssetID: "y1", OwnerOrg: "b", Distance: 7}}},
			domain.VerdictPassed, domain.AIChecked, ""},
		{"duplicates disabled", domain.ModerationPolicy{DuplicateDistance: -1}, domain.ModerationInput{OwnerOrg: "a",
			AI: allow, Similar: []domain.SimilarImage{{AssetID: "y1", OwnerOrg: "b"}}}, domain.VerdictPassed, domain.AIChecked, ""},
		{"block wins over a duplicate hold", closed, domain.ModerationInput{OwnerOrg: "a",
			AI:      &domain.AIVerdict{Decision: domain.AIBlock},
			Similar: []domain.SimilarImage{{AssetID: "y1", OwnerOrg: "b"}}}, domain.VerdictBlocked, domain.AIChecked, "y1"},
	}
	for _, c := range cases {
		m := domain.Moderate(c.policy, c.in)
		if m.Verdict != c.want || m.AI != c.ai || m.DuplicateOf != c.dup {
			t.Errorf("%s: got %s / %s / %q, want %s / %s / %q (%v)", c.name, m.Verdict, m.AI, m.DuplicateOf, c.want,
				c.ai, c.dup, m.Reasons)
		}
		if m.Verdict != domain.VerdictPassed && len(m.Reasons) == 0 {
			t.Errorf("%s: a held or blocked image needs a reason", c.name)
		}
	}
	if !(*domain.Moderation)(nil).Published() {
		t.Error("assets without moderation (documents) are not gated")
	}
}

func TestHashDistanceAndStatusForFlags(t *testing.T) {
	if domain.HashDistance(0b1011, 0b0110) != 3 || domain.HashDistance(42, 42) != 0 {
		t.Fatal("Hamming distance")
	}
	if domain.StatusForFlags([]string{"eligible as main image"}) != domain.StatusReady ||
		domain.StatusForFlags([]string{"background is not white (needed for a main image)"}) != domain.StatusFlagged ||
		domain.StatusForFlags(nil) != domain.StatusReady {
		t.Fatal("status for flags")
	}
}
