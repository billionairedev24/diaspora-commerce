package app

import (
	"testing"
	"time"
)

// The monolith wires SellerDeps without a Clock: the statement's default month and month picker use time.Now instead
// of panicking (GET /v1/seller/tax/statement was a 500 nil pointer dereference).
func TestSellerTaxMonthWithoutAClockUsesTheCurrentMonth(t *testing.T) {
	s := NewSellerTaxService(SellerDeps{}).(*sellerTaxImpl)
	got, err := s.month("")
	if err != nil {
		t.Fatal(err)
	}
	if want := time.Now().Format("2006-01"); got != want {
		t.Fatalf("month = %q, want %q", got, want)
	}
}
