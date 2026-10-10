package domain_test

import (
	"testing"

	"github.com/billionairedev24/diaspora-commerce/go/services/search/internal/domain"
)

func TestFuseRRF(t *testing.T) {
	got := domain.FuseRRF([]string{"a", "b", "c"}, []string{"c", "d", "a"}, 60)
	ids := make([]string, 0, len(got))
	for _, f := range got {
		ids = append(ids, f.ID)
	}
	// a: 1/61 + 1/63, c: 1/63 + 1/61 (tie; lexical order keeps a first), b: 1/62, d: 1/62 (tie; b first).
	want := []string{"a", "c", "b", "d"}
	for i := range want {
		if ids[i] != want[i] {
			t.Fatalf("order %v, want %v", ids, want)
		}
	}
	if got[0].Lexical != 1 || got[0].Semantic != 3 || got[3].Lexical != 0 || got[3].Semantic != 2 {
		t.Fatalf("ranks %+v", got)
	}
	if only := domain.FuseRRF(nil, []string{"x", "x", "y"}, 0); len(only) != 2 || only[0].ID != "x" || only[0].Semantic != 1 {
		t.Fatalf("semantic only with a duplicate: %+v", only)
	}
}

func TestStockSignal(t *testing.T) {
	cases := []struct {
		inStock bool
		qty     int
		want    domain.Availability
		low     bool
	}{
		{true, 1, domain.AvailabilityLowStock, true},
		{true, 5, domain.AvailabilityLowStock, true},
		{true, 6, domain.AvailabilityInStock, false},
		{false, 0, domain.AvailabilityOutOfStock, false},
		{true, 0, domain.AvailabilityOutOfStock, false}, // stale flag without units
		{false, 3, domain.AvailabilityOutOfStock, false},
	}
	for _, c := range cases {
		a, low := domain.StockSignal(c.inStock, c.qty)
		if a != c.want || low != c.low {
			t.Errorf("inStock=%v qty=%d: %s %v, want %s %v", c.inStock, c.qty, a, low, c.want, c.low)
		}
	}
}
