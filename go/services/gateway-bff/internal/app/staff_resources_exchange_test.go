package app

import "testing"

func TestFormatPaidShowsTheCurrencyPaidInItsOwnMinorUnits(t *testing.T) {
	cases := []struct {
		minor    int64
		currency string
		want     string
	}{
		{12000, "MXN", "MXN 120.00"},
		{5, "NGN", "NGN 0.05"},
		{123456789, "GHS", "GHS 1,234,567.89"},
		{6000, "XOF", "XOF 6,000"},
		{0, "USD", "USD 0.00"},
		{-250, "EUR", "EUR -2.50"},
	}
	for _, c := range cases {
		if got := formatPaid(c.minor, c.currency); got != c.want {
			t.Errorf("formatPaid(%d, %s) = %q, want %q", c.minor, c.currency, got, c.want)
		}
	}
}

func TestRateIDRoundTrips(t *testing.T) {
	id := rateID(jobj{"currency": "MXN", "rateDate": "2026-10-24"})
	cur, day, ok := rateKey(id)
	if id != "MXN_2026-10-24" || !ok || cur != "MXN" || day != "2026-10-24" {
		t.Fatalf("id %q → %q %q %v", id, cur, day, ok)
	}
	if _, _, ok := rateKey("MXN"); ok {
		t.Fatal("an id without a day is not a rate")
	}
}
