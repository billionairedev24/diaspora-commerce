package app

import (
	"strings"
	"testing"
)

func TestFilingRowShowsWhatWasPaidOnlyWhenARemittanceExists(t *testing.T) {
	open := filingRow(jobj{"month": "2026-09", "jurisdiction": "US-TX", "currency": "USD", "dueMinor": float64(133600), "status": "NOT_FILED", "outstandingMinor": float64(133600)})
	if open["id"] != "2026-09|US-TX|USD" || open["statusLabel"] != "Not filed" {
		t.Fatalf("open row: %v", open)
	}
	if _, ok := open["paidMinor"]; ok {
		t.Fatalf("an unfiled month has no paid amount: %v", open)
	}

	filed := filingRow(jobj{"month": "2026-08", "jurisdiction": "US-TX", "currency": "USD", "dueMinor": float64(100000), "status": "SETTLED",
		"remittance": map[string]any{"id": "rem-1", "paidMinor": float64(100000), "dueMinor": float64(100000), "filedOn": "2026-09-15", "confirmationRef": "TX-1"}})
	if filed["paidMinor"] != float64(100000) || filed["remittanceId"] != "rem-1" || filed["confirmationRef"] != "TX-1" {
		t.Fatalf("filed row: %v", filed)
	}
}

func TestFilingIDRoundTripsAndRejectsGarbage(t *testing.T) {
	m, j, c := parseFilingID(filingID("2026-09", "US-TX", "USD"))
	if m != "2026-09" || j != "US-TX" || c != "USD" {
		t.Fatalf("round trip: %s %s %s", m, j, c)
	}
	if m, _, _ := parseFilingID("nonsense"); m != "" {
		t.Fatal("garbage must not parse")
	}
}

func TestFilingMeaningExplainsEachStatus(t *testing.T) {
	for state, want := range map[string]string{"NOT_FILED": "nothing is recorded", "SETTLED": "matches", "OWES_MORE": "more is due", "OVERPAID": "More was paid", "NOTHING_DUE": "No tax is due"} {
		got := filingMeaning(jobj{"state": state, "jurisdiction": "US-TX", "outstandingMinor": float64(100)})
		if len(got) == 0 || !strings.Contains(got, want) {
			t.Errorf("%s: %q should mention %q", state, got, want)
		}
	}
}
