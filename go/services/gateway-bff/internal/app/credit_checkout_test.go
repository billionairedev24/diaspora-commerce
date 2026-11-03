package app

import "testing"

// creditSplit previews what payments does when the order is placed: credit first, never more than the balance, and at
// least 0.50 left for the card or credit pays everything.
func TestCreditSplit(t *testing.T) {
	cases := []struct {
		name                 string
		balance, total       int64
		wantApplied, wantDue int64
	}{
		{"balance below the total", 2000, 5000, 2000, 3000},
		{"balance covers the order", 8000, 5000, 5000, 0},
		{"exactly the total", 5000, 5000, 5000, 0},
		{"a remainder under 0.50 is left for the card at 0.50", 4980, 5000, 4950, 50},
		{"a remainder of exactly 0.50", 4950, 5000, 4950, 50},
		{"no credit", 0, 5000, 0, 5000},
		{"credit that covers a tiny order pays it", 100, 30, 30, 0},
		{"credit short of a tiny order stays unused: the card cannot take less than 0.50", 10, 30, 0, 30},
	}
	for _, c := range cases {
		applied, due := creditSplit(c.balance, c.total)
		if applied != c.wantApplied || due != c.wantDue || applied+due != c.total || applied > c.balance {
			t.Errorf("%s: creditSplit(%d, %d) = %d, %d; want %d, %d", c.name, c.balance, c.total, applied, due, c.wantApplied, c.wantDue)
		}
	}
}
