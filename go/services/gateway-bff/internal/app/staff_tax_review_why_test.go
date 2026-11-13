package app

import "testing"

func TestReviewWhyUsesTheCauseTaxReturnsNamesForAWaitingRecord(t *testing.T) {
	waiting := jobj{"status": "PENDING_REVIEW", "reviewReason": "Conflict: Flavored water & soda has an open decision about which tax code applies.",
		"resolution": map[string]any{"status": "CONFLICT_OPEN"}}
	if got := reviewWhy(waiting); got != "Conflict: Flavored water & soda has an open decision about which tax code applies." {
		t.Fatalf("a waiting record shows the cause: %q", got)
	}
}

func TestReviewWhyFallsBackWhenTaxReturnsNamesNoCause(t *testing.T) {
	cases := map[string]jobj{
		"The category waits on an open decision about which code applies.":              {"status": "PENDING_REVIEW", "resolution": map[string]any{"status": "CONFLICT_OPEN"}},
		"New or changed listing: check the category, the answers and the tax decision.": {"status": "PENDING_REVIEW", "resolution": map[string]any{"status": "RESOLVED"}},
		"Approved: the stored decision is used at checkout.":                            {"status": "APPROVED", "reviewReason": "stale text that must not show"},
		"The seller still has to answer the tax questions.":                             {"status": "MISSING_FACTS"},
	}
	for want, it := range cases {
		if got := reviewWhy(it); got != want {
			t.Fatalf("reviewWhy(%v) = %q, want %q", it, got, want)
		}
	}
}
