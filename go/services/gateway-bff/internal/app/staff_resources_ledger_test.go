package app

import "testing"

func TestCorrectionLinesMakeTheLedgerMatchStripe(t *testing.T) {
	// The ledger holds $2.50 more than Stripe: take it out of clearing, book it against the fee account.
	lines, err := correctionLines(250, "5000", "")
	if err != nil {
		t.Fatal(err)
	}
	if lines[0]["account"] != "1000" || lines[0]["side"] != "CREDIT" || lines[0]["amountMinor"] != int64(250) {
		t.Errorf("clearing line = %v", lines[0])
	}
	if lines[1]["account"] != "5000" || lines[1]["side"] != "DEBIT" || lines[1]["amountMinor"] != int64(250) {
		t.Errorf("counter line = %v", lines[1])
	}

	// Stripe holds $1.00 more than the ledger: add it to clearing, credit the seller payable of that seller.
	lines, err = correctionLines(-100, "2000", "org_abc")
	if err != nil {
		t.Fatal(err)
	}
	if lines[0]["side"] != "DEBIT" || lines[1]["side"] != "CREDIT" || lines[1]["orgId"] != "org_abc" || lines[1]["amountMinor"] != int64(100) {
		t.Errorf("lines = %v", lines)
	}

	if _, err := correctionLines(0, "5000", ""); err == nil {
		t.Error("an agreeing difference needs no correction")
	}
}

func TestStripeIDsMayCarryTheSeller(t *testing.T) {
	if ref, org := lookupID("po_123456 org_abc"); ref != "po_123456" || org != "org_abc" {
		t.Errorf("got %q %q", ref, org)
	}
	if ref, org := lookupID("ch_123456"); ref != "ch_123456" || org != "" {
		t.Errorf("got %q %q", ref, org)
	}
}

func TestWhatNeedsAttentionOnAPayout(t *testing.T) {
	paidAtStripe := jobj{"object": map[string]any{"status": "paid"}}
	if stripeMismatch(jobj{"status": "IN_TRANSIT"}, paidAtStripe) == "" {
		t.Error("in transit here but paid at Stripe should be flagged")
	}
	if stripeMismatch(jobj{"status": "PAID"}, paidAtStripe) != "" {
		t.Error("paid on both sides is fine")
	}
}
